package com.roamly.ui.map

import com.roamly.data.api.LocationPoint
import com.roamly.tracking.FixAccuracy
import com.roamly.tracking.LocationSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// "Have I been here?": the answer is computed from the on-device history.

data class NearHereDay(
    val date: LocalDate,
    val pointCount: Int,
    val closestMeters: Double,
    val lat: Double,
    val lng: Double,
    val timeSpentSeconds: Int = 0,
)

data class NearHereResult(
    val region: String?,
    val veryCloseDays: Int,
    val totalRegionPoints: Int,
    val closestMeters: Double?,
    val days: List<NearHereDay>,
)

// ---- Have-I-been-here computation ----

internal fun checkNearHere(
    context: android.content.Context,
    viewModel: MapViewModel,
    onResult: (NearHereResult?) -> Unit,
) {
    // The same source the tracking service uses, so this answers on a de-Googled build too.
    // It went straight to Play Services before, which on GrapheneOS meant every check
    // silently reported "no location" rather than saying it had no provider.
    val source = LocationSource.get(context)
    val compute = { lat: Double, lng: Double ->
        viewModel.checkHaveIBeenHere(lat, lng) { onResult(it) }
    }
    try {
        source.lastKnown { last ->
            if (last != null) {
                compute(last.latitude, last.longitude)
            } else {
                source.currentFix(FixAccuracy.BALANCED) { cur ->
                    if (cur != null) compute(cur.latitude, cur.longitude) else onResult(null)
                }
            }
        }
    } catch (_: SecurityException) {
        onResult(null)
    }
}

private const val SAME_SPOT_METERS = 200.0
private const val REGION_KM = 5.0

internal fun buildNearHere(
    userLat: Double,
    userLng: Double,
    locations: List<LocationPoint>,
): NearHereResult {
    val today = LocalDate.now()

    var regionCity: String? = null
    var regionState: String? = null
    var nearestPastDistKm = Double.MAX_VALUE
    for (pt in locations) {
        val day = parseDate(pt.timestamp)
        if (day == today) continue
        val distKm = haversineKm(userLat, userLng, pt.lat, pt.lng)
        if (distKm < nearestPastDistKm) {
            nearestPastDistKm = distKm
            regionCity = pt.city?.takeIf { it.isNotBlank() }
            regionState = pt.state?.takeIf { it.isNotBlank() }
        }
    }
    val regionLabel = listOfNotNull(regionCity, regionState).joinToString(", ").ifBlank { null }

    val regionPoints = locations.filter { pt ->
        val day = parseDate(pt.timestamp)
        if (day == today) return@filter false
        if (regionCity != null && !pt.city.isNullOrBlank()) {
            pt.city.equals(regionCity, ignoreCase = true)
        } else {
            haversineKm(userLat, userLng, pt.lat, pt.lng) <= REGION_KM
        }
    }

    data class DayAgg(
        var count: Int = 0,
        var bestDistM: Double = Double.MAX_VALUE,
        var lat: Double = 0.0,
        var lng: Double = 0.0,
        val timestamps: MutableList<Long> = mutableListOf(),
    )
    val byDay = HashMap<LocalDate, DayAgg>()
    var globalClosestM = Double.MAX_VALUE

    for (pt in regionPoints) {
        val distM = haversineKm(userLat, userLng, pt.lat, pt.lng) * 1000.0
        if (distM > SAME_SPOT_METERS) continue
        if (distM < globalClosestM) globalClosestM = distM
        val day = parseDate(pt.timestamp) ?: continue
        val tsMs = parseIsoMs(pt.timestamp) ?: continue
        val agg = byDay.getOrPut(day) { DayAgg() }
        agg.count++
        agg.timestamps.add(tsMs)
        if (distM < agg.bestDistM) {
            agg.bestDistM = distM
            agg.lat = pt.lat
            agg.lng = pt.lng
        }
    }

    val days = byDay.entries
        .sortedByDescending { it.key }
        .map { (date, agg) ->
            val timeSpent = computeTimeSpentSeconds(agg.timestamps)
            NearHereDay(
                date = date,
                pointCount = agg.count,
                closestMeters = agg.bestDistM,
                lat = agg.lat,
                lng = agg.lng,
                timeSpentSeconds = timeSpent,
            )
        }

    return NearHereResult(
        region = regionLabel,
        veryCloseDays = days.size,
        totalRegionPoints = regionPoints.size,
        closestMeters = if (globalClosestM == Double.MAX_VALUE) null else globalClosestM,
        days = days,
    )
}

private fun computeTimeSpentSeconds(timestampsMs: List<Long>): Int {
    if (timestampsMs.size < 2) return 0
    val sorted = timestampsMs.sorted()
    var totalMs = 0L
    for (i in 1 until sorted.size) {
        val gap = sorted[i] - sorted[i - 1]
        totalMs += minOf(gap, 3_600_000L)  // cap each gap at 1 hour like the server does
    }
    return (totalMs / 1000L).toInt()
}

private fun parseIsoMs(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(ts).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(ts).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { null }
    }
}

private fun parseDate(ts: String?): LocalDate? {
    if (ts.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(ts).toLocalDate()
        } catch (_: DateTimeParseException) {
            try { LocalDate.parse(ts.take(10)) } catch (_: DateTimeParseException) { null }
        }
    }
}

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double = haversineKm(lat1, lng1, lat2, lng2) * 1000.0
