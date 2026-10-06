package com.roamly.tracking

import android.location.Location

/**
 * Per-sport physics for a recording. Mirrors `SPORTS` in the server's
 * `tracker/activity_track.py` — the server re-cleans the track anyway, so the two
 * only need to agree closely enough that the phone never throws away a fix the
 * server would have kept.
 *
 * [ceilingMps] is the fastest the sport plausibly goes (teleport rejection),
 * [processNoise] how hard it can accelerate or turn (Kalman Q, m²/s³), and
 * [movingMps] the speed below which time counts as paused.
 */
enum class ActivitySport(val ceilingMps: Float, val processNoise: Double, val movingMps: Double) {
    WALK(4f, 0.3, 0.4),
    HIKE(4f, 0.3, 0.4),
    RUN(9f, 1.0, 0.8),
    ROW(8f, 0.5, 0.5),
    RIDE(30f, 2.0, 1.0),
    OTHER(70f, 4.0, 0.8);

    companion object {
        fun of(kind: String?): ActivitySport = when (kind?.lowercase()) {
            "walk" -> WALK
            "hike" -> HIKE
            "run" -> RUN
            "row" -> ROW
            "ride" -> RIDE
            else -> OTHER
        }
    }
}

/**
 * Accept or reject one raw fix of a recording, in real time.
 *
 * Deliberately not [LocationFilter]: that one guards the all-day queue with a de-dup
 * window (keep the *first* fix in a window, not the best), a 357 m/s teleport bar and
 * the user's 100 m accuracy preference — none of which suit a 1 Hz ride. This keeps
 * only what can be decided fix by fix:
 *
 *  - **Accuracy.** Worse than [GOOD_ACCURACY_M] is dropped. A sustained run of such
 *    fixes (a forest, an urban canyon) relaxes the bar to [POOR_ACCURACY_M] so the
 *    stretch is recorded coarsely rather than not at all; one good fix restores it.
 *  - **Order.** Duplicate or older timestamps are dropped — batched delivery can
 *    hand back a fix the stream already delivered.
 *  - **Physics.** A fix implying a speed the sport cannot reach from the last
 *    accepted one is dropped, with slack for both fixes' own accuracy. After
 *    [REANCHOR_AFTER] rejections in a row the *anchor* is assumed to be the bad
 *    fix (or the rider really moved while the signal was gone) and the filter
 *    re-anchors rather than rejecting the rest of the ride.
 *
 * What it does not do is smooth or substitute: there are no dwell points here,
 * ever. A missing fix is a gap, and the server's backward smoothing pass handles
 * what a forward filter cannot.
 */
class ActivityFilter(private val sport: ActivitySport) {

    private var last: Location? = null
    private var rejectedRun = 0
    private var poorAccuracyRun = 0

    /** Whether [loc] belongs in the track. Advances state only when it does. */
    fun accept(loc: Location): Boolean {
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val limit = if (poorAccuracyRun >= RELAX_AFTER) POOR_ACCURACY_M else GOOD_ACCURACY_M
        if (acc != null && acc > limit) {
            if (acc <= POOR_ACCURACY_M) poorAccuracyRun++
            return false
        }
        if (acc != null && acc <= GOOD_ACCURACY_M) poorAccuracyRun = 0

        val prev = last
        if (prev == null) {
            // A cached fix handed back at stream start can be minutes old.
            if (System.currentTimeMillis() - loc.time > FIRST_FIX_MAX_AGE_MS) return false
            last = loc
            return true
        }
        if (loc.time <= prev.time) return false
        val dtS = (loc.time - prev.time) / 1000f
        val slack = (if (prev.hasAccuracy()) prev.accuracy else DEFAULT_ACC_M) +
            (acc ?: DEFAULT_ACC_M)
        val allowed = sport.ceilingMps * dtS + slack
        if (loc.distanceTo(prev) > allowed && dtS <= GAP_RESET_S && rejectedRun < REANCHOR_AFTER) {
            rejectedRun++
            return false
        }
        rejectedRun = 0
        last = loc
        return true
    }

    private companion object {
        const val GOOD_ACCURACY_M = 30f
        const val POOR_ACCURACY_M = 50f
        const val RELAX_AFTER = 10
        const val DEFAULT_ACC_M = 15f
        const val REANCHOR_AFTER = 5
        /** Past this gap the speed check means nothing; take the fix. */
        const val GAP_RESET_S = 60f
        const val FIRST_FIX_MAX_AGE_MS = 30_000L
    }
}
