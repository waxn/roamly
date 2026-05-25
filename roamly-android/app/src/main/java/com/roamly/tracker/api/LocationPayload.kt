package com.roamly.tracker.api

import com.google.gson.annotations.SerializedName

/**
 * JSON body sent to POST /api/push/.
 *
 * Matches the Roamly server's push_location() view exactly.
 * All optional fields are nullable; Gson omits nulls by default when
 * serializeNulls is NOT set (which is the default).
 */
data class LocationPayload(
    @SerializedName("device_id")    val deviceId: String,
    @SerializedName("latitude")     val latitude: Double,
    @SerializedName("longitude")    val longitude: Double,
    /** ISO-8601, e.g. "2026-05-25T12:34:56Z" */
    @SerializedName("timestamp")    val timestamp: String,
    @SerializedName("altitude")     val altitude: Double?,
    @SerializedName("accuracy")     val accuracy: Float?,
    /** Speed in m/s */
    @SerializedName("speed")        val speed: Float?,
    /** Battery 0–100 */
    @SerializedName("battery")      val battery: Int?
)

data class PushResponse(
    @SerializedName("status")       val status: String,
    @SerializedName("location_id")  val locationId: Long?,
    @SerializedName("device")       val device: String?
)
