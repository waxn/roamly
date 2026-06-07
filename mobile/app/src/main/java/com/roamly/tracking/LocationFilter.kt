package com.roamly.tracking

import android.location.Location
import android.util.Log

private const val TAG = "LocationFilter"

/**
 * Rejects fixes that are stale or too inaccurate, and de-duplicates fixes that
 * land on essentially the same spot — but never at the cost of the time cadence:
 * once [minTimeBetweenMs] has elapsed since the last accepted point a fix is kept
 * even if the device hasn't moved, so a stationary user still logs a point every
 * interval instead of dropping out for minutes.
 */
class LocationFilter(
    /** Reject fixes with accuracy circle wider than this (metres). */
    var maxAccuracyMetres: Float = 100f,
    /** Reject fixes older than this (ms). */
    var maxAgeMs: Long = 30_000L,
    /** De-dup threshold: a closer fix is dropped unless [minTimeBetweenMs] has passed. */
    var minDistanceMetres: Float = 10f,
    /** Always accept once this long has passed since the last accepted fix, even if
     *  the device hasn't moved. Set to the tracking interval; 0 disables the escape. */
    var minTimeBetweenMs: Long = 0L,
) {
    private var lastAcceptedLocation: Location? = null
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
        lastAcceptedLocation?.let { prev ->
            val movedEnough = loc.distanceTo(prev) >= minDistanceMetres
            // Allow a little slack (80%) so the OS firing slightly early doesn't skip
            // a whole cadence cycle.
            val waitedEnough = minTimeBetweenMs > 0L &&
                (loc.time - lastAcceptedTimeMs) >= minTimeBetweenMs * 8 / 10
            if (!movedEnough && !waitedEnough) {
                Log.d(TAG, "Rejected duplicate fix: < ${minDistanceMetres}m and within cadence")
                return false
            }
        }
        lastAcceptedLocation = Location(loc)
        lastAcceptedTimeMs = loc.time
        return true
    }

    fun reset() {
        lastAcceptedLocation = null
        lastAcceptedTimeMs = 0L
    }
}
