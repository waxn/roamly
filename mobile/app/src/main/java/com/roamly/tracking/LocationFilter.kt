package com.roamly.tracking

import android.location.Location
import android.os.SystemClock
import android.util.Log

private const val TAG = "LocationFilter"

/** Stateful filter — call [accept] on every raw location fix. */
class LocationFilter(
    var maxAccuracyMetres: Float = 100f,
    var maxAgeMs: Long = 30_000L,
    var minDisplacementMetres: Float = 5f,
) {
    private var last: Location? = null
    private var lastRealtimeMs: Long = 0L

    fun accept(loc: Location): Boolean {
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > maxAgeMs) {
            Log.d(TAG, "Stale: age=${ageMs}ms")
            return false
        }
        if (loc.hasAccuracy() && loc.accuracy > maxAccuracyMetres) {
            Log.d(TAG, "Low accuracy: ${loc.accuracy}m")
            return false
        }
        val prev = last
        if (prev != null && minDisplacementMetres > 0 && prev.distanceTo(loc) < minDisplacementMetres) {
            Log.d(TAG, "Stationary: dist=${prev.distanceTo(loc)}m")
            return false
        }
        last = loc
        lastRealtimeMs = SystemClock.elapsedRealtime()
        return true
    }

    fun reset() { last = null; lastRealtimeMs = 0L }
}
