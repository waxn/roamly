package com.roamly.tracking

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A location point mirrored from the server into a local store. This is the
 * read model that powers the map, "have I been here?", and any other view that
 * needs history — distinct from [CachedPoint], which is the *write* queue for
 * points this device captured and still needs to upload.
 *
 * The primary key is the server's location id, so re-syncing the same point is a
 * natural upsert (INSERT OR REPLACE) with no duplicates. [timestampMs] is the
 * epoch-millis copy used for fast time-range queries; [timestampIso] is kept so
 * the value maps straight back onto the API's `LocationPoint.timestamp`.
 */
@Entity(
    tableName = "synced_locations",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["lat", "lng"]),
    ],
)
data class SyncedLocation(
    @PrimaryKey val id: Int,
    val lat: Double,
    val lng: Double,
    val timestampMs: Long,
    val timestampIso: String,
    val deviceId: String,
    val altitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val battery: Double?,
    val city: String?,
    val state: String?,
    val country: String?,
    val countryCode: String?,
)
