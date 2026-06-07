package com.roamly.tracking

import android.location.Location
import android.util.Log

private const val TAG = "LocationFilter"

/**
 * Rejects fixes that are stale or too inaccurate, and de-duplicates fixes that
 * arrive faster than the tracking interval. It **never filters by movement** — a
 * stationary user keeps logging a point every interval on purpose, so dwelling in
 * one place shows up as a dense cluster ("big dot") on the map.
 */
class LocationFilter(
    /** Reject fixes with accuracy circle wider than this (metres). */
    var maxAccuracyMetres: Float = 100f,
    /** Reject fixes older than this (ms). */
    var maxAgeMs: Long = 30_000L,
    /** Drop fixes that arrive closer together in time than this (the tracking
     *  interval). 0 disables de-dup. Purely time-based — no displacement check. */
    var minTimeBetweenMs: Long = 0L,
) {
    private var lastAcceptedTimeMs: Long = 0L

    fun accept(loc: Location): Boolean {
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > maxAgeMs) {
            Log.d(TAG, "Rejected stale fix: age=${ageMs}ms")
            return false
        }
        if (loc.hasAccuracy() && loc.accuracy > maxAccuracyMetres) {
            Log.d(TAG, "Rejected inaccurate fix: ${loc.accuracy}m > max ${maxAccuracyMetres}m")
            return false
        }
        // Time-based de-dup only (with 80% slack so an early fire doesn't skip a
        // cycle). Never a distance check — stationary points are kept by design.
        if (minTimeBetweenMs > 0L && lastAcceptedTimeMs > 0L &&
            (loc.time - lastAcceptedTimeMs) < minTimeBetweenMs * 8 / 10
        ) {
            Log.d(TAG, "Rejected fix arriving faster than the interval")
            return false
        }
        lastAcceptedTimeMs = loc.time
        return true
    }

    fun reset() {
        lastAcceptedTimeMs = 0L
    }
}
