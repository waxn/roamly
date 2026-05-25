package com.roamly.tracking

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_points",
    indices = [
        Index(value = ["synced", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class CachedPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val speed: Float?,
    val battery: Int?,
    /** Unix millis */
    val timestamp: Long,
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,
    val provider: String? = null,
)
