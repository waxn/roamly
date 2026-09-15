package com.roamly.tracking

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One observation of one cell tower from one position.
 *
 * Mirrors the server's `CellSample` field for field. Lives in
 * [TrackingDatabase] alongside [CachedPoint] for the same reason that one does:
 * an un-uploaded sample exists nowhere else — the modem keeps no history, so a
 * destructive migration here would lose it permanently.
 *
 * Nullable almost everywhere, and that is not laziness. Every `CellIdentity*`
 * getter can return [android.telephony.CellInfo.UNAVAILABLE]; [CellScanner]
 * maps those to null rather than letting `2147483647` reach a column where it
 * would look like a real TAC.
 */
@Entity(
    tableName = "cell_samples",
    indices = [
        Index(value = ["synced", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class CellSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Idempotency key sent to the server; the uploader re-sends routinely. */
    val clientId: String,
    /** Unix millis of the GPS fix this reading was taken alongside. */
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    /** One of `lte` / `nr` / `wcdma` / `gsm`. */
    val rat: String,
    /** One of `serving` / `secondary` / `neighbour`. */
    val role: String,
    /** -1 when READ_PHONE_STATE was declined and only the default SIM is visible. */
    val simSlot: Int = -1,
    val carrier: String? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    /** TAC / LAC. */
    val tac: Int? = null,
    /** CI / NCI / CID — the global identity. Null means this row cannot be a tower. */
    val cid: Long? = null,
    /** PCI / PSC / BSIC. */
    val pci: Int? = null,
    /** EARFCN / NRARFCN / UARFCN / ARFCN. */
    val earfcn: Int? = null,
    /** API 30+ only; null on 26-29, where [earfcn] is kept instead. */
    val band: Int? = null,
    /** RSRP / SS-RSRP / RSCP / RSSI. */
    val dbm: Int? = null,
    val asu: Int? = null,
    /** 0-4 bars. The one signal figure every RAT reports. */
    val level: Int? = null,
    val rsrq: Int? = null,
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,
)
