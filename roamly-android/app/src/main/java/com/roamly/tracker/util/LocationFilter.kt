package com.roamly.tracker.util

import android.location.Location
import android.os.SystemClock
import android.util.Log

private const val TAG = "LocationFilter"

/**
 * Stateful filter that decides whether a new [Location] should be accepted.
 *
 * Rules applied in order:
 * 1. **Staleness** — reject if the fix is older than [maxAgeMs] (default 30s)
 * 2. **Accuracy** — reject if `accuracy > [maxAccuracyMetres]` (default 100m)
 * 3. **Minimum displacement** — reject if moved less than [minDisplacementMetres] since
 *    the last accepted fix (default 5m). Prevents flooding the DB while standing still.
 * 4. **Minimum time** — reject if less than [minIntervalMs] has elapsed since the last
 *    accepted fix (default 0 — the FusedLocationProvider interval handles this upstream).
 *
 * All thresholds are configurable so the caller can wire them to user preferences.
 */
class LocationFilter(
    var maxAccuracyMetres: Float = 100f,
    var maxAgeMs: Long = 30_000L,
    var minDisplacementMetres: Float = 5f,
    var minIntervalMs: Long = 0L
) {

    private var lastAccepted: Location? = null
    private var lastAcceptedRealtimeMs: Long = 0L

    /**
     * Returns true if [location] passes all filters and should be stored / uploaded.
     */
    fun accept(location: Location): Boolean {
        // 1. Staleness — compare fix timestamp to wall clock
        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs > maxAgeMs) {
            Log.d(TAG, "Rejected stale fix: age=${ageMs}ms > max=${maxAgeMs}ms")
            return false
        }

        // 2. Accuracy
        if (location.hasAccuracy() && location.accuracy > maxAccuracyMetres) {
            Log.d(TAG, "Rejected low-accuracy fix: ${location.accuracy}m > max=${maxAccuracyMetres}m")
            return false
        }

        val now = SystemClock.elapsedRealtime()

        // 3. Minimum time since last accepted
        if (minIntervalMs > 0 && lastAccepted != null) {
            val elapsed = now - lastAcceptedRealtimeMs
            if (elapsed < minIntervalMs) {
                Log.d(TAG, "Rejected too-soon fix: elapsed=${elapsed}ms < min=${minIntervalMs}ms")
                return false
            }
        }

        // 4. Minimum displacement
        val prev = lastAccepted
        if (prev != null && minDisplacementMetres > 0) {
            val dist = prev.distanceTo(location)
            if (dist < minDisplacementMetres) {
                Log.d(TAG, "Rejected stationary fix: dist=${dist}m < min=${minDisplacementMetres}m")
                return false
            }
        }

        // Accepted — update state
        lastAccepted = location
        lastAcceptedRealtimeMs = now
        return true
    }

    /** Reset state (e.g. when tracking is stopped and restarted). */
    fun reset() {
        lastAccepted = null
        lastAcceptedRealtimeMs = 0L
    }
}
