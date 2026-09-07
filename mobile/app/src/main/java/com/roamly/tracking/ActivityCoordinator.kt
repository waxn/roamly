package com.roamly.tracking

import android.content.Context
import android.util.Log
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Start and stop a recorded activity — the sibling of [TrackingCoordinator], and
 * deliberately much smaller than it.
 *
 * The whole state is one DataStore session ([UserPreferences.activitySession]).
 * [LocationTrackingService] observes it and swaps its capture config accordingly,
 * so nothing here talks to the service directly beyond nudging it awake.
 *
 * **Nothing is stashed, so nothing has to be put back.** The recording constants
 * live in the service; the user's own interval, priority and drift preferences are
 * never written to. That is what makes [stop] safe against a process kill halfway
 * through: clearing the session *is* the restore, and it is a single atomic edit.
 */
object ActivityCoordinator {

    private const val TAG = "ActivityCoordinator"

    /**
     * Begin recording. Requires tracking to already be running: a recording is a
     * higher-fidelity mode of the existing capture loop, not a second one, and
     * requiring it means there is genuinely no prior state to restore on stop.
     *
     * Returns the new session, or null if tracking is off or location is denied.
     */
    suspend fun start(context: Context, prefs: UserPreferences, kind: String): ActivitySession? {
        if (!TrackingCoordinator.canTrack(context)) {
            Log.w(TAG, "Cannot start an activity without location permission")
            return null
        }
        if (!prefs.trackingEnabled.first()) {
            Log.w(TAG, "Cannot start an activity while tracking is off")
            return null
        }
        val session = ActivitySession(UUID.randomUUID().toString(), System.currentTimeMillis(), kind)
        prefs.startActivity(session.id, session.kind, session.startedAtMs)
        // The service picks the change up from the flow on its own; this only covers
        // the case where it is not running yet (a start it would otherwise miss).
        LocationTrackingService.start(context)
        Log.i(TAG, "Recording started: ${session.kind} (${session.id})")
        return session
    }

    /**
     * Finish recording and hand the envelope off to be saved.
     *
     * Order matters: clear the session **first** so capture returns to the user's
     * normal settings immediately, then flush points, then enqueue the save. A
     * crash anywhere after the clear leaves tracking correct and costs at most the
     * activity record, which the queued worker will still deliver.
     */
    suspend fun stop(context: Context, prefs: UserPreferences): ActivitySession? {
        val session = prefs.activitySession.first() ?: return null
        prefs.clearActivity()
        runCatching { UploadWorker.scheduleNow(context, prefs.syncOnMobileData.first(), replace = true) }
        ActivitySaveWorker.enqueue(context, session, System.currentTimeMillis())
        Log.i(TAG, "Recording stopped: ${session.kind} (${session.id})")
        return session
    }

    /**
     * Abandon the recording without saving an activity.
     *
     * The captured points stay in normal history, which is the correct behaviour
     * rather than an oversight: an activity is a view over the track, so discarding
     * the view must not punch a hole in the location history behind it.
     */
    suspend fun discard(prefs: UserPreferences) {
        prefs.clearActivity()
        Log.i(TAG, "Recording discarded — points kept in normal history")
    }
}
