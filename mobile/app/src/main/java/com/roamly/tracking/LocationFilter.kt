package com.roamly.tracking

import android.location.Location
import android.util.Log

private const val TAG = "LocationFilter"

// Implied speed above which a fix is treated as a GPS glitch / teleport and dropped.
// 357 m/s ≈ 1285 km/h — faster than an airliner, so anything beyond it between two
// consecutive accepted fixes is a bad jump, not real travel. (Ported from GPSLogger.)
private const val MAX_PLAUSIBLE_SPEED_MPS = 357.0

/**
 * Rejects fixes that are stale, duplicated, or physically impossible, and de-duplicates
 * fixes that arrive faster than the tracking interval. It **never filters by movement** —
 * a stationary user keeps logging a point every interval on purpose, so dwelling in one
 * place shows up as a dense cluster ("big dot") on the map.
 *
 * Accuracy is **not** a hard reject here — it's a *target* applied during acquisition
 * (`acquireBestFix` in the service keeps the most accurate fix it can get within the cycle
 * budget). A too-strict accuracy cutoff used to silently drop every indoor/stationary fix
 * and create the very gaps tracking is meant to avoid; instead we always log the best fix
 * available and store its accuracy so it can be judged later.
 */
class LocationFilter(
    /** Reject fixes older than this (ms). Set to a couple of intervals by the service so
     *  a just-acquired or post-wake fix isn't dropped for being slightly behind now(). */
    var maxAgeMs: Long = 60_000L,
    /** Drop fixes that arrive closer together in time than this (the tracking
     *  interval). 0 disables de-dup. Purely time-based — no displacement check. */
    var minTimeBetweenMs: Long = 0L,
) {
    private var lastAcceptedTimeMs: Long = 0L
    private var lastAccepted: Location? = null

    fun accept(loc: Location): Boolean {
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > maxAgeMs) {
            Log.d(TAG, "Rejected stale fix: age=${ageMs}ms")
            return false
        }
        // Cached/repeat fix: its timestamp is at or before the last one we kept. FusedLocation
        // and getCurrentLocation can hand back a previously-seen fix; never log it twice.
        if (lastAccepted != null && loc.time <= lastAcceptedTimeMs) {
            Log.d(TAG, "Rejected duplicate/older fix: ${loc.time} <= $lastAcceptedTimeMs")
            return false
        }
        // Teleport guard: a physically impossible jump from the last accepted fix is a
        // GPS glitch. Compare against the previous *accepted* point so jitter that the
        // accuracy gate already passed can't masquerade as thousand-km/h travel.
        lastAccepted?.let { prev ->
            val dtSec = (loc.time - prev.time) / 1000.0
            if (dtSec > 0) {
                val speed = loc.distanceTo(prev) / dtSec
                if (speed > MAX_PLAUSIBLE_SPEED_MPS) {
                    Log.w(TAG, "Rejected teleport: ${speed.toInt()}m/s over ${dtSec}s")
                    return false
                }
            }
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
        lastAccepted = loc
        return true
    }

    fun reset() {
        lastAcceptedTimeMs = 0L
        lastAccepted = null
    }
}
