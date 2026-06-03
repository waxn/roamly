package com.roamly.tracking

import android.location.Location
import android.util.Log

private const val TAG = "LocationFilter"

/**
 * Stateless-ish filter — rejects fixes that are stale or too inaccurate.
 * Every point that passes is sent; no displacement filtering.
 */
class LocationFilter(
    /** Reject fixes with accuracy circle wider than this (metres). */
    var maxAccuracyMetres: Float = 100f,
    /** Reject fixes older than this (ms). */
    var maxAgeMs: Long = 30_000L,
    /** Reject fixes that are basically the same spot again. */
    var minDistanceMetres: Float = 10f,
) {
    private var lastAcceptedLocation: Location? = null

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
            if (loc.distanceTo(prev) < minDistanceMetres) {
                Log.d(TAG, "Rejected duplicate fix: < ${minDistanceMetres}m from last accepted point")
                return false
            }
        }
        lastAcceptedLocation = Location(loc)
        return true
    }

    fun reset() {
        lastAcceptedLocation = null
    }
}
