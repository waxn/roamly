package com.roamly.tracker.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A GPS point waiting to be uploaded to the Roamly server.
 *
 * Points are written immediately on acquisition (offline-safe) and
 * marked [synced] = true after a successful server acknowledgement.
 * Old synced points are pruned periodically to keep the DB lean.
 */
@Entity(
    tableName = "cached_points",
    indices = [
        Index(value = ["synced", "timestamp"]),   // for efficient upload queries
        Index(value = ["timestamp"])              // for pruning old records
    ]
)
data class CachedPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val latitude: Double,
    val longitude: Double,

    /** Horizontal accuracy in metres (95% confidence circle). Null if unavailable. */
    val accuracy: Float?,

    /** Altitude in metres above sea level. Null if unavailable. */
    val altitude: Double?,

    /** Speed in metres per second. Null if unavailable. */
    val speed: Float?,

    /** Battery level 0–100. Null if unavailable. */
    val battery: Int?,

    /** Unix time in milliseconds (device clock at acquisition). */
    val timestamp: Long,

    /** True once the server has acknowledged this point. */
    @ColumnInfo(defaultValue = "0")
    val synced: Boolean = false,

    /**
     * Name of the location provider ("fused", "gps", "network").
     * Stored for debugging / filter auditing.
     */
    val provider: String? = null
)
