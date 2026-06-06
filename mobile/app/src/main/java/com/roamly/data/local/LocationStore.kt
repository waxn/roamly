package com.roamly.data.local

import android.util.Log
import com.roamly.data.api.LocationPoint
import com.roamly.data.api.RoamlyApi
import com.roamly.tracking.SyncedLocation
import com.roamly.tracking.SyncedLocationDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocationStore"

/** Points per page when paging the full history. The server caps at 50k. */
private const val SYNC_BATCH = 20_000
/** Safety bound on the paging loop. */
private const val MAX_PAGES = 1_000
/** Re-fetch this much of the tail on an incremental sync so a point that shares
 *  the boundary timestamp is never skipped (upsert dedups the overlap). */
private const val OVERLAP_MS = 2_000L
/** Don't re-run a sync more often than this from the convenience entry point. */
private const val SYNC_THROTTLE_MS = 30_000L

data class SyncState(
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0L,
    val totalPoints: Int = 0,
    val error: String? = null,
)

/**
 * Local-first mirror of the user's location history.
 *
 * The app keeps a full copy of every point on-device (Room) and only ever asks
 * the server for the *delta* since the newest point it already holds. The map,
 * "have I been here?", and any history view then read straight from the local DB
 * — instant, offline-capable, and without hammering the server on every screen.
 *
 * Sync is incremental and idempotent: points are keyed by their server id, paged
 * in ascending time order via the cursor the locations API already exposes.
 */
@Singleton
class LocationStore @Inject constructor(
    private val dao: SyncedLocationDao,
    private val api: RoamlyApi,
) {
    private val syncMutex = Mutex()

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Run a sync only if one hasn't completed recently (rapid tab switches). */
    suspend fun syncIfDue(): Result<Int> {
        val last = _state.value.lastSyncMs
        if (last != 0L && System.currentTimeMillis() - last < SYNC_THROTTLE_MS && !_state.value.syncing) {
            return Result.success(0)
        }
        return sync()
    }

    /**
     * Pull every point newer than what we hold. On a fresh install this downloads
     * the whole history once; afterwards it's just the handful of new points since
     * the last open. Concurrent callers share the single in-flight run.
     */
    suspend fun sync(): Result<Int> = syncMutex.withLock {
        _state.value = _state.value.copy(syncing = true, error = null)
        try {
            val haveAny = dao.count() > 0
            var cursorVal: String? =
                if (haveAny) isoOf((dao.maxTimestampMs() ?: 0L) - OVERLAP_MS) else null
            var cursorId: Int? = null
            var added = 0
            var page = 0
            while (page++ < MAX_PAGES) {
                val resp = api.getLocations(
                    all = 1,
                    limit = SYNC_BATCH,
                    sortDir = "asc",
                    beforeValue = cursorVal,
                    beforeId = cursorId,
                )
                if (!resp.isSuccessful) {
                    val msg = "sync failed: HTTP ${resp.code()}"
                    // Partial progress still counts as a win.
                    return@withLock finish(added, if (added > 0) null else msg)
                }
                val body = resp.body() ?: break
                val rows = body.devices.flatMap { dev ->
                    dev.locations.mapNotNull { it.toRow(dev.deviceId) }
                }
                if (rows.isNotEmpty()) {
                    dao.upsertAll(rows)
                    added += rows.size
                }
                if (!body.hasMore || body.nextBeforeValue == null) break
                cursorVal = body.nextBeforeValue
                cursorId = body.nextBeforeId
            }
            finish(added, null)
        } catch (e: Exception) {
            Log.w(TAG, "sync error", e)
            finish(0, e.message ?: "sync error")
        }
    }

    private suspend fun finish(added: Int, error: String?): Result<Int> {
        _state.value = SyncState(
            syncing = false,
            lastSyncMs = System.currentTimeMillis(),
            totalPoints = dao.count(),
            error = error,
        )
        return if (error == null) Result.success(added) else Result.failure(Exception(error))
    }

    // ── Queries (instant, local) ────────────────────────────────────────────

    suspend fun count(): Int = dao.count()

    /**
     * Overview points for a time window, decimated so the heatmap stays smooth no
     * matter how dense the history is. [hours] = null means all-time.
     */
    suspend fun periodOverview(hours: Int?, maxPoints: Int): List<LocationPoint> {
        val sinceMs = sinceMsFor(hours)
        val total = dao.countInPeriod(sinceMs)
        val rows = if (total > maxPoints) {
            val stride = (total / maxPoints).coerceAtLeast(2)
            dao.inPeriodDecimated(sinceMs, stride)
        } else {
            dao.inPeriod(sinceMs)
        }
        return rows.map { it.toPoint() }
    }

    /** Full-resolution points in a viewport, within the active time window. */
    suspend fun bbox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        hours: Int?, limit: Int,
    ): List<LocationPoint> =
        dao.inBbox(minLat, maxLat, minLng, maxLng, sinceMsFor(hours), limit).map { it.toPoint() }

    /** All-time points around a spot — powers an accurate "have I been here?". */
    suspend fun allTimeAround(
        lat: Double, lng: Double, padDegrees: Double,
    ): List<LocationPoint> =
        dao.allTimeInBbox(lat - padDegrees, lat + padDegrees, lng - padDegrees, lng + padDegrees)
            .map { it.toPoint() }

    suspend fun clear() = dao.deleteAll()

    // ── Mapping / helpers ────────────────────────────────────────────────────

    private fun sinceMsFor(hours: Int?): Long =
        if (hours == null) Long.MIN_VALUE else System.currentTimeMillis() - hours * 3_600_000L

    private fun SyncedLocation.toPoint() = LocationPoint(
        id = id, lat = lat, lng = lng, timestamp = timestampIso,
        altitude = altitude, accuracy = accuracy, speed = speed, battery = battery,
        city = city, state = state, country = country, countryCode = countryCode,
    )

    private fun LocationPoint.toRow(deviceId: String): SyncedLocation? {
        val ms = parseIsoMs(timestamp) ?: return null
        return SyncedLocation(
            id = id, lat = lat, lng = lng, timestampMs = ms, timestampIso = timestamp,
            deviceId = deviceId, altitude = altitude, accuracy = accuracy, speed = speed,
            battery = battery, city = city, state = state, country = country,
            countryCode = countryCode,
        )
    }

    // Explicit +00:00 offset (pattern XXX), not "Z" — Django's
    // datetime.fromisoformat only accepts "Z" on Python 3.11+, so this stays safe
    // on any server version when used as the incremental-sync cursor.
    private val cursorFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    private fun isoOf(ms: Long): String =
        OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneOffset.UTC).format(cursorFmt)

    private fun parseIsoMs(ts: String?): Long? {
        if (ts.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(ts).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
