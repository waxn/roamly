package com.roamly.tracking

import android.content.Context
import android.util.Log
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Start and stop a recorded activity — the sibling of [TrackingCoordinator], and
 * deliberately much smaller than it.
 *
 * The whole state is one DataStore session ([UserPreferences.activitySession]).
 * [LocationTrackingService] observes it and hands capture to [ActivityRecorder]
 * while it is set, so nothing here talks to the service directly beyond nudging it
 * awake.
 *
 * **Nothing is stashed, so nothing has to be put back.** The recording constants
 * live in the service; the user's own interval, priority and drift preferences are
 * never written to. That is what makes [stop] safe against a process kill halfway
 * through: clearing the session *is* the restore, and it is a single atomic edit.
 */
object ActivityCoordinator {

    private const val TAG = "ActivityCoordinator"

    /**
     * Begin recording. Requires tracking to already be running: the recorder lives
     * inside the tracking service, and requiring it means there is genuinely no
     * prior state to restore on stop.
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
     * Finish recording and hand the envelope and track off to be saved.
     *
     * Order matters: clear the session **first** so capture returns to the user's
     * normal settings immediately (the service stops the recorder, which writes out
     * its buffer), then enqueue the save. The worker waits for that last write to
     * land before it reads the track. A crash anywhere after the clear leaves
     * tracking correct; the recorded fixes are already on disk and the queued
     * worker still delivers them.
     */
    suspend fun stop(context: Context, prefs: UserPreferences): ActivitySession? {
        val session = prefs.activitySession.first() ?: return null
        prefs.clearActivity()
        ActivitySaveWorker.enqueue(context, session, System.currentTimeMillis())
        Log.i(TAG, "Recording stopped: ${session.kind} (${session.id})")
        return session
    }

    /**
     * Abandon the recording: nothing is saved and the recorded fixes are deleted.
     *
     * The recorded track lives only in the phone's private recording buffer until
     * it uploads, and all-day tracking was suspended while it ran — so a discarded
     * ride leaves a gap in history for its duration. That is what "discard" means;
     * the confirmation dialog says so.
     */
    suspend fun discard(context: Context, prefs: UserPreferences) {
        val session = prefs.activitySession.first() ?: return
        prefs.clearActivity()
        // Give the service a moment to stop the recorder and write its buffer, so
        // the delete below catches those rows too rather than leaving orphans.
        delay(1_500L)
        TrackingDatabase.getInstance(context).activityPointDao().deleteActivity(session.id)
        Log.i(TAG, "Recording discarded: ${session.id}")
    }
}
