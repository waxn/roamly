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
) {
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
        return true
    }

    fun reset() { /* nothing stateful to clear */ }
}
