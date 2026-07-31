package com.roamly.tracking

import android.location.Location
import android.util.Log

private const val TAG = "DriftAnchor"

// Below this Doppler speed the chip is telling us we aren't actually moving, even if the
// position estimate wanders (~1.5 mph). Aligned with LocationTrackingService.MIN_LOGGED_SPEED_MPS.
private const val STILL_SPEED_MPS = 0.7f
// At/above this Doppler speed we have real-movement evidence (~1.8 mph). Deliberately below
// walking pace (1.2–1.4 m/s): at the old 1.5 m/s a walk away from a parked anchor never
// cleared the bar, so departures on foot were snapped back until the hard break 300 m later.
private const val MOVE_SPEED_MPS = 0.8f
// Displacement-based release, for fixes that carry no Doppler at all. PRIORITY_BALANCED
// (which the service auto-degrades to after two misses) frequently reports no speed, and
// without this the only escape left was HARD_BREAK_M.
private const val DEPART_RADIUS_M = 60f
// Consecutive fixes beyond DEPART_RADIUS_M needed to release on displacement alone.
private const val EXIT_DISPLACE_STREAK = 3
// A lone fix beyond HARD_BREAK_M is a glitch until the next fix corroborates it: the second
// fix must also be far from the anchor *and* this close to the first one.
private const val BREAK_CLUSTER_M = 150f
// Consecutive not-moving fixes needed to drop an anchor.
private const val ENTER_STILL_STREAK = 3
// Candidate still-fixes must cluster within this to be one stationary spot (m).
private const val ENTER_RADIUS_M = 40f
// While anchored, only fixes this close nudge the anchor — a big drift excursion never drags it (m).
private const val ANCHOR_UPDATE_RADIUS_M = 30f
// EMA weight for the small anchor nudge from a nearby fix.
private const val ANCHOR_EMA_ALPHA = 0.2
// Consecutive moving fixes needed to release an anchor.
private const val EXIT_MOVE_STREAK = 2
// Displacement that always releases the anchor, even with unreliable/absent Doppler (m).
private const val HARD_BREAK_M = 300f

/**
 * Suppresses stationary GPS drift by snapping fixes to an anchor while the device is parked.
 *
 * When a phone sits still for a long time the GPS position estimate can wander hundreds of
 * metres — a line of "moving" points at plausible speeds. The teleport guard in
 * [LocationFilter] can't catch it (a 30 mph drift is far below its 357 m/s cutoff) and it's
 * consistent, not a spike, so the spike corrector misses it too.
 *
 * The tell is the chip's **Doppler speed** ([Location.getSpeed]): when truly stationary it
 * stays ≈ 0 even as the position drifts. This class watches for a cluster of low-Doppler
 * fixes, drops an anchor at their centroid, and thereafter returns drift fixes **snapped**
 * to the anchor (speed 0) — so a parked device logs one stable point per interval instead of
 * a drift spider. It releases the anchor the moment Doppler shows real movement, on sustained
 * displacement (in case Doppler is unreliable or absent), or on a corroborated large
 * relocation, so a real departure passes through unchanged with no clipped start.
 *
 * A large relocation needs **two** fixes, not one. Releasing on the first fix past
 * [HARD_BREAK_M] meant a single glitch fix passed straight through unmodified — before any
 * speed check — and then became the baseline for everything after it: exactly the isolated
 * far-away, high-speed outlier that shows up next to a stationary cluster on the map. A real
 * departure still releases, one interval later; a lone spike is snapped and never recorded
 * off-position.
 *
 * Single-writer: [resolve]/[reset] are `@Synchronized` because both the continuous stream
 * callback and the fix-cycle coroutine reach `savePoint`.
 */
class DriftAnchor(
    /** When false, [resolve] is a pass-through (today's exact behaviour). */
    @Volatile var enabled: Boolean = true,
) {
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var anchorAccuracy: Float? = null
    private var moveStreak: Int = 0
    private var departStreak: Int = 0

    // A fix beyond HARD_BREAK_M awaiting corroboration from the next one.
    private var pendingBreakLat: Double = 0.0
    private var pendingBreakLon: Double = 0.0
    private var hasPendingBreak: Boolean = false

    // Candidate stationary cluster while not yet anchored.
    private var candLat: Double = 0.0
    private var candLon: Double = 0.0
    private var stillStreak: Int = 0

    /**
     * Given an accepted fix, return either it unchanged (we're moving) or a snapped point at
     * the current anchor (we're parked / drifting).
     */
    @Synchronized
    fun resolve(loc: Location): Location {
        val dop: Float? = if (loc.hasSpeed()) loc.speed else null

        val aLat = anchorLat
        val aLon = anchorLon
        if (aLat != null && aLon != null) {
            val d = distanceM(loc.latitude, loc.longitude, aLat, aLon)

            // Real movement — trust the chip's Doppler and release after a short streak.
            if (dop != null && dop >= MOVE_SPEED_MPS) {
                moveStreak++
                if (moveStreak >= EXIT_MOVE_STREAK) {
                    Log.d(TAG, "Anchor released (moving: ${dop} m/s)")
                    clearAnchor()
                    return loc
                }
            } else {
                moveStreak = 0
            }

            // Sustained displacement — release even when Doppler is absent entirely.
            if (d > DEPART_RADIUS_M) {
                departStreak++
                if (departStreak >= EXIT_DISPLACE_STREAK) {
                    Log.d(TAG, "Anchor released (departed ${d.toInt()}m over $departStreak fixes)")
                    clearAnchor()
                    return loc
                }
            } else {
                departStreak = 0
            }

            // Hard relocation, but only once a second fix corroborates it — a lone far fix is
            // a glitch, and letting it through is what puts a stray high-speed point on the map.
            if (d > HARD_BREAK_M) {
                val corroborated = hasPendingBreak &&
                    distanceM(loc.latitude, loc.longitude, pendingBreakLat, pendingBreakLon) <= BREAK_CLUSTER_M
                if (corroborated) {
                    Log.d(TAG, "Anchor released (relocated ${d.toInt()}m, corroborated)")
                    clearAnchor()
                    return loc
                }
                pendingBreakLat = loc.latitude
                pendingBreakLon = loc.longitude
                hasPendingBreak = true
                Log.d(TAG, "Suppressed uncorroborated ${d.toInt()}m jump — snapping, awaiting next fix")
                CaptureStats.bump(CaptureStats.Counter.SPIKE_SUPPRESSED)
                return snapped(loc)
            }
            hasPendingBreak = false

            // Still moving-streak but not yet at the exit threshold: keep the real fix so we
            // don't snap the first step of a departure back onto the anchor.
            if (dop != null && dop >= MOVE_SPEED_MPS) return loc

            // Drift: snap to the anchor. Nudge the anchor only for genuinely-near fixes.
            CaptureStats.bump(CaptureStats.Counter.DRIFT_SNAP)
            if (d <= ANCHOR_UPDATE_RADIUS_M) {
                anchorLat = aLat + (loc.latitude - aLat) * ANCHOR_EMA_ALPHA
                anchorLon = aLon + (loc.longitude - aLon) * ANCHOR_EMA_ALPHA
            }
            return snapped(loc)
        }

        // Not anchored. Grow / start a candidate cluster when the fix reads stationary.
        val stationary = when {
            dop != null -> dop < STILL_SPEED_MPS
            // No Doppler: fall back to displacement from the running candidate centroid.
            stillStreak > 0 -> distanceM(loc.latitude, loc.longitude, candLat, candLon) < ENTER_RADIUS_M
            else -> false
        }
        if (!stationary) {
            stillStreak = 0
            return loc
        }
        if (stillStreak == 0) {
            candLat = loc.latitude
            candLon = loc.longitude
        } else {
            candLat += (loc.latitude - candLat) * ANCHOR_EMA_ALPHA
            candLon += (loc.longitude - candLon) * ANCHOR_EMA_ALPHA
        }
        stillStreak++
        if (stillStreak >= ENTER_STILL_STREAK) {
            anchorLat = candLat
            anchorLon = candLon
            anchorAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
            moveStreak = 0
            departStreak = 0
            hasPendingBreak = false
            Log.d(TAG, "Anchor dropped at $candLat,$candLon")
            return snapped(loc)
        }
        return loc
    }

    /** Drop all state so a new tracking session starts clean. */
    @Synchronized
    fun reset() {
        clearAnchor()
        stillStreak = 0
    }

    private fun clearAnchor() {
        anchorLat = null
        anchorLon = null
        anchorAccuracy = null
        moveStreak = 0
        departStreak = 0
        hasPendingBreak = false
        stillStreak = 0
    }

    /** A copy of [loc] repositioned onto the anchor with zero speed. */
    private fun snapped(loc: Location): Location =
        Location(loc.provider ?: "anchor").apply {
            latitude = anchorLat ?: loc.latitude
            longitude = anchorLon ?: loc.longitude
            (anchorAccuracy ?: loc.accuracy.takeIf { loc.hasAccuracy() })?.let { accuracy = it }
            if (loc.hasAltitude()) altitude = loc.altitude
            speed = 0f
            time = loc.time
            elapsedRealtimeNanos = loc.elapsedRealtimeNanos
        }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }
}
