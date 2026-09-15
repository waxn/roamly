package com.roamly.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the cell towers each SIM can see, and decides which readings are worth
 * storing.
 *
 * **Every telephony type in the app appears in this file and nowhere else** —
 * the same containment rule [LocationSource] applies to Play Services types,
 * for the same reason: one file to reason about when the platform behaves
 * differently than documented, and one file to stub if it ever has to be.
 *
 * ## The gate is the whole reason this feature is affordable
 *
 * Serving plus neighbour cells on a dual-SIM phone is roughly 8-12 [CellInfo]
 * entries per reading. Taken once per GPS fix at the default 30s interval that
 * is ~29,000 rows/day — ten times what tracking itself produces, for a feature
 * that is off by default. Two things cut it by ~12x:
 *
 *  1. [CELL_MIN_SCAN_INTERVAL_MS] decouples scanning from the capture cadence,
 *     so dropping to a 2s interval to record a ride does *not* multiply cell
 *     rows the way a naive per-fix scan would.
 *  2. A per-cell gate: a row is written only when that cell is newly seen, or
 *     you have moved [CELL_MIN_MOVE_M] since its last sample, or its heartbeat
 *     has elapsed. Neighbours are gated harder still, and only ride along in a
 *     scan where their SIM's serving cell also qualified.
 *
 * There is deliberately **no** "signal changed by >= 10 dB" clause. Indoor RSRP
 * genuinely wanders +/- 10 dB on a phone sitting still, so it would roughly
 * double the stationary row count for information the heartbeat already
 * samples. It is the first thing to add if the data proves too coarse.
 *
 * ## Permissions
 *
 * Scanning needs only `ACCESS_FINE_LOCATION`, which tracking already holds.
 * `READ_PHONE_STATE` is purely the multi-SIM upgrade: without it
 * [SubscriptionManager] will not enumerate subscriptions, so the scan falls
 * back to the default one and reports `simSlot = -1`. Declining degrades the
 * feature; it never breaks it.
 */
object CellScanner {

    private const val TAG = "CellScanner"

    /** Never ask telephony more than once a minute, whatever the capture interval is. */
    const val CELL_MIN_SCAN_INTERVAL_MS = 60_000L

    /** Readings older than this are discarded — [TelephonyManager.getAllCellInfo] is
     *  throttled on Android 10+ and answers from cache, so "what the modem last
     *  happened to record" can be arbitrarily stale. */
    private const val CELL_MAX_AGE_MS = 60_000L

    /** Distance from a cell's last stored sample that re-qualifies it. */
    private const val CELL_MIN_MOVE_M = 150.0

    private const val CELL_HEARTBEAT_MS = 10 * 60_000L
    private const val CELL_NEIGHBOUR_HEARTBEAT_MS = 30 * 60_000L

    /** Per SIM per scan. Bounds the worst case at 6x the serving volume. */
    private const val CELL_MAX_NEIGHBOURS = 6

    private const val CELL_GATE_CACHE_SIZE = 256

    /** Budget for [TelephonyManager.requestCellInfoUpdate] to answer. */
    private const val CELL_UPDATE_TIMEOUT_MS = 10_000L

    const val ROLE_SERVING = "serving"
    const val ROLE_SECONDARY = "secondary"
    const val ROLE_NEIGHBOUR = "neighbour"

    private data class GateEntry(val lat: Double, val lon: Double, val atMs: Long)

    /** In-memory, not persisted. A service restart re-records one row per visible
     *  cell, which is cheap and is itself a useful marker in the data. */
    private val gate = LruCache<String, GateEntry>(CELL_GATE_CACHE_SIZE)

    @Volatile private var lastScanAt = 0L

    /** One reading, before the gate has decided whether to keep it. */
    private data class Reading(
        val rat: String,
        val role: String,
        val mcc: String?,
        val mnc: String?,
        val tac: Int?,
        val cid: Long?,
        val pci: Int?,
        val earfcn: Int?,
        val band: Int?,
        val dbm: Int?,
        val asu: Int?,
        val level: Int?,
        val rsrq: Int?,
    ) {
        /** Identity used by the gate and by the server's tower aggregation. A cell
         *  with no global id can still be gated on its PCI, but it can never
         *  become a tower. */
        val key: String get() = if (cid != null) "$rat:$mcc:$mnc:$tac:$cid" else "$rat:pci:$pci"
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun available(context: Context): Boolean =
        telephony(context)?.let { it.phoneType != TelephonyManager.PHONE_TYPE_NONE } ?: false

    /** One line for Diagnostics. A phone recording nothing because it has no
     *  modem, or because the permission is missing, must not look identical to
     *  one parked somewhere with no towers in range. */
    fun describe(context: Context): String {
        val tm = telephony(context) ?: return "No telephony service"
        if (tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) return "No cellular radio"
        val sims = if (hasPhoneStatePermission(context)) {
            val n = subscriptions(context).size
            if (n > 0) "$n SIM${if (n == 1) "" else "s"}" else "no active SIM"
        } else {
            "default SIM only (Phone permission not granted)"
        }
        return "$sims · scans every ${CELL_MIN_SCAN_INTERVAL_MS / 1000}s"
    }

    /** Forget the gate. Called when tracking pauses, mirroring `driftAnchor.reset()`. */
    fun reset() {
        gate.evictAll()
        lastScanAt = 0L
    }

    /**
     * Take a reading, gate it, and return the rows worth storing.
     *
     * Returns an empty list — never throws — when the scan floor has not
     * elapsed, telephony is unavailable, the permission is missing, or nothing
     * qualified. Callers treat all of those the same way.
     */
    suspend fun scan(
        context: Context,
        latitude: Double,
        longitude: Double,
        accuracy: Float?,
        timestampMs: Long,
    ): List<CellSample> {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < CELL_MIN_SCAN_INTERVAL_MS) return emptyList()
        lastScanAt = now

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val out = mutableListOf<CellSample>()
        for ((slot, tm) in perSimManagers(context)) {
            val infos = readCellInfo(tm) ?: continue
            CaptureStats.bump(CaptureStats.Counter.CELL_SCANNED)
            val readings = infos.mapNotNull { toReading(it) }
            out += gateAndBuild(readings, slot, carrierOf(tm), latitude, longitude, accuracy, timestampMs)
        }
        return out
    }

    // ── Gate ─────────────────────────────────────────────────────────────────

    private fun gateAndBuild(
        readings: List<Reading>,
        slot: Int,
        carrier: String?,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        timestampMs: Long,
    ): List<CellSample> {
        val nowMs = System.currentTimeMillis()
        val serving = readings.filter { it.role != ROLE_NEIGHBOUR }
        val neighbours = readings.filter { it.role == ROLE_NEIGHBOUR }

        val kept = mutableListOf<Reading>()
        var servingQualified = false
        for (r in serving) {
            if (qualifies(r, slot, lat, lon, nowMs, CELL_HEARTBEAT_MS)) {
                kept += r
                servingQualified = true
            } else {
                CaptureStats.bump(CaptureStats.Counter.CELL_GATED)
            }
        }

        // Neighbours only ride along in a scan where this SIM's serving cell also
        // qualified. Without that clause a stationary phone whose serving cell is
        // held back by its heartbeat would still write neighbour rows every scan,
        // which is most of the volume this gate exists to remove.
        if (servingQualified) {
            var taken = 0
            for (r in neighbours.sortedByDescending { it.dbm ?: Int.MIN_VALUE }) {
                if (taken >= CELL_MAX_NEIGHBOURS) break
                // A neighbour with no global identity cannot become a tower and
                // cannot be told apart from a different cell reusing that PCI two
                // towns over. It is noise with a row cost.
                if (r.cid == null) {
                    CaptureStats.bump(CaptureStats.Counter.CELL_NO_IDENTITY)
                    continue
                }
                if (qualifies(r, slot, lat, lon, nowMs, CELL_NEIGHBOUR_HEARTBEAT_MS)) {
                    kept += r
                    taken++
                } else {
                    CaptureStats.bump(CaptureStats.Counter.CELL_GATED)
                }
            }
        } else {
            CaptureStats.bump(CaptureStats.Counter.CELL_GATED, neighbours.size)
        }

        return kept.map { r ->
            CellSample(
                clientId = UUID.randomUUID().toString().replace("-", ""),
                timestamp = timestampMs,
                latitude = lat, longitude = lon, accuracy = accuracy,
                rat = r.rat, role = r.role,
                simSlot = slot, carrier = carrier,
                mcc = r.mcc, mnc = r.mnc, tac = r.tac, cid = r.cid, pci = r.pci,
                earfcn = r.earfcn, band = r.band,
                dbm = r.dbm, asu = r.asu, level = r.level, rsrq = r.rsrq,
            )
        }
    }

    /** Records the sample against the gate as a side effect when it qualifies. */
    private fun qualifies(
        r: Reading, slot: Int, lat: Double, lon: Double, nowMs: Long, heartbeatMs: Long,
    ): Boolean {
        val key = "$slot|${r.key}"
        val prev = gate.get(key)
        val ok = prev == null ||
            nowMs - prev.atMs >= heartbeatMs ||
            haversineM(prev.lat, prev.lon, lat, lon) >= CELL_MIN_MOVE_M
        if (ok) gate.put(key, GateEntry(lat, lon, nowMs))
        return ok
    }

    // ── Telephony access ─────────────────────────────────────────────────────

    private fun telephony(context: Context): TelephonyManager? = runCatching {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }.getOrNull()

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun subscriptions(context: Context): List<SubscriptionInfo> = runCatching {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        sm?.activeSubscriptionInfoList.orEmpty()
    }.getOrElse { emptyList() }

    /**
     * One [TelephonyManager] per SIM, paired with its slot index.
     *
     * Falls back to the default manager with `slot = -1` whenever subscriptions
     * cannot be enumerated — no `READ_PHONE_STATE`, a single-SIM phone, or a
     * manufacturer that simply returns nothing. That fallback is what makes the
     * permission genuinely optional.
     */
    private fun perSimManagers(context: Context): List<Pair<Int, TelephonyManager>> {
        val base = telephony(context) ?: return emptyList()
        if (!hasPhoneStatePermission(context)) return listOf(-1 to base)
        val subs = subscriptions(context)
        if (subs.isEmpty()) return listOf(-1 to base)
        return subs.mapNotNull { info ->
            runCatching { base.createForSubscriptionId(info.subscriptionId) }
                .getOrNull()?.let { info.simSlotIndex to it }
        }.ifEmpty { listOf(-1 to base) }
    }

    private fun carrierOf(tm: TelephonyManager): String? =
        runCatching { tm.networkOperatorName?.takeIf { it.isNotBlank() } }.getOrNull()

    /**
     * Current cell info, or null if nothing usable came back.
     *
     * On API 29+ this asks for a *fresh* reading rather than taking
     * [TelephonyManager.getAllCellInfo]'s cached answer, which the platform
     * throttles. Stale entries are dropped either way — a reading has to
     * describe where the phone is now to be worth storing against this fix.
     */
    @SuppressLint("MissingPermission")
    private suspend fun readCellInfo(tm: TelephonyManager): List<CellInfo>? {
        val infos: List<CellInfo>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            withTimeoutOrNull(CELL_UPDATE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<CellInfo>?> { cont ->
                    val done = AtomicBoolean(false)
                    val cb = object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (done.compareAndSet(false, true)) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            Log.w(TAG, "requestCellInfoUpdate failed: $errorCode", detail)
                            if (done.compareAndSet(false, true)) cont.resume(null)
                        }
                    }
                    runCatching { tm.requestCellInfoUpdate({ it.run() }, cb) }
                        .onFailure { if (done.compareAndSet(false, true)) cont.resume(null) }
                }
            }
        } else {
            runCatching { tm.allCellInfo }.getOrNull()
        }
        if (infos.isNullOrEmpty()) return null
        val nowBootMs = SystemClock.elapsedRealtime()
        return infos.filter { nowBootMs - bootMillisOf(it) <= CELL_MAX_AGE_MS }
            .ifEmpty { null }
    }

    /** Milliseconds since boot at which the reading was taken. `getTimestampMillis`
     *  is API 30+; below that the deprecated `timeStamp` is nanoseconds since boot. */
    @Suppress("DEPRECATION")
    private fun bootMillisOf(info: CellInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.timestampMillis
        else info.timeStamp / 1_000_000L

    // ── Per-RAT extraction ───────────────────────────────────────────────────

    /**
     * [CellInfoCdma] is deliberately absent: a fifth identity and signal class
     * pair for a network type switched off in essentially every market.
     */
    private fun toReading(info: CellInfo): Reading? {
        val role = roleOf(info)
        return when (info) {
            is CellInfoLte -> lteReading(info, role)
            is CellInfoWcdma -> wcdmaReading(info, role)
            is CellInfoGsm -> gsmReading(info, role)
            else ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr)
                    nrReading(info, role)
                else null
        }
    }

    private fun roleOf(info: CellInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (info.cellConnectionStatus) {
                CellInfo.CONNECTION_PRIMARY_SERVING -> return ROLE_SERVING
                CellInfo.CONNECTION_SECONDARY_SERVING -> return ROLE_SECONDARY
                CellInfo.CONNECTION_NONE -> return ROLE_NEIGHBOUR
                // CONNECTION_UNKNOWN falls through to isRegistered below.
            }
        }
        return if (info.isRegistered) ROLE_SERVING else ROLE_NEIGHBOUR
    }

    private fun lteReading(info: CellInfoLte, role: String): Reading {
        val id: CellIdentityLte = info.cellIdentity
        val ss: CellSignalStrengthLte = info.cellSignalStrength
        return Reading(
            rat = "lte", role = role,
            mcc = mccOf(id), mnc = mncOf(id),
            tac = id.tac.orNull(), cid = id.ci.orNull()?.toLong(), pci = id.pci.orNull(),
            earfcn = id.earfcn.orNull(),
            band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null,
            dbm = ss.dbm.orNull(), asu = ss.asuLevel.orNull(), level = ss.level,
            rsrq = ss.rsrq.orNull(),
        )
    }

    private fun nrReading(info: CellInfoNr, role: String): Reading? {
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val ss = info.cellSignalStrength as? CellSignalStrengthNr ?: return null
        return Reading(
            rat = "nr", role = role,
            mcc = id.mccString, mnc = id.mncString,
            tac = id.tac.orNull(), cid = id.nci.orNullLong(), pci = id.pci.orNull(),
            earfcn = id.nrarfcn.orNull(),
            band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.firstOrNull() else null,
            dbm = ss.dbm.orNull(), asu = ss.asuLevel.orNull(), level = ss.level,
            rsrq = ss.ssRsrq.orNull(),
        )
    }

    @Suppress("DEPRECATION")
    private fun wcdmaReading(info: CellInfoWcdma, role: String): Reading {
        val id: CellIdentityWcdma = info.cellIdentity
        val ss: CellSignalStrengthWcdma = info.cellSignalStrength
        return Reading(
            rat = "wcdma", role = role,
            mcc = mccOf(id), mnc = mncOf(id),
            tac = id.lac.orNull(), cid = id.cid.orNull()?.toLong(), pci = id.psc.orNull(),
            earfcn = id.uarfcn.orNull(), band = null,
            dbm = ss.dbm.orNull(), asu = ss.asuLevel.orNull(), level = ss.level, rsrq = null,
        )
    }

    @Suppress("DEPRECATION")
    private fun gsmReading(info: CellInfoGsm, role: String): Reading {
        val id: CellIdentityGsm = info.cellIdentity
        val ss: CellSignalStrengthGsm = info.cellSignalStrength
        return Reading(
            rat = "gsm", role = role,
            mcc = mccOf(id), mnc = mncOf(id),
            tac = id.lac.orNull(), cid = id.cid.orNull()?.toLong(), pci = id.bsic.orNull(),
            earfcn = id.arfcn.orNull(), band = null,
            dbm = ss.dbm.orNull(), asu = ss.asuLevel.orNull(), level = ss.level, rsrq = null,
        )
    }

    // ── MCC / MNC ────────────────────────────────────────────────────────────
    //
    // The string getters are API 28+. Below that only the deprecated int form
    // exists, which loses leading zeros — hence the padding. Three near-identical
    // overloads rather than one generic helper because `CellIdentity`, the common
    // supertype, is itself API 30+.

    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityLte): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString
        else id.mcc.orNull()?.toString()?.padStart(3, '0')

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityLte): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString
        else id.mnc.orNull()?.toString()?.padStart(2, '0')

    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityWcdma): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString
        else id.mcc.orNull()?.toString()?.padStart(3, '0')

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityWcdma): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString
        else id.mnc.orNull()?.toString()?.padStart(2, '0')

    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityGsm): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString
        else id.mcc.orNull()?.toString()?.padStart(3, '0')

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityGsm): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString
        else id.mnc.orNull()?.toString()?.padStart(2, '0')

    // ── Sentinels ────────────────────────────────────────────────────────────
    //
    // Nearly every CellIdentity getter returns CellInfo.UNAVAILABLE on a field
    // the modem did not report. Missing one of these writes 2147483647 into a
    // column where it reads as a real TAC — the single highest-density source of
    // bugs in this file, which is why these are applied to *every* getter above
    // with no exceptions.

    private fun Int.orNull(): Int? =
        takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }

    private fun Long.orNullLong(): Long? =
        takeIf { it != Long.MAX_VALUE && it != CellInfo.UNAVAILABLE.toLong() }

    // ── Geometry ─────────────────────────────────────────────────────────────

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
