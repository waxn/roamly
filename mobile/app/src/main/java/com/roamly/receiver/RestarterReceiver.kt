package com.roamly.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.LocationTrackingService
import com.roamly.tracking.TrackingCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

private const val TAG = "RestarterReceiver"

/**
 * Self-resurrection. The tracking service broadcasts [ACTION_RESTART] from its
 * onDestroy / onTaskRemoved when it is being torn down while the user still wants
 * tracking on. A *separate* receiver (a distinct process entry point that the OS
 * delivers even as the service dies) then immediately restarts the service.
 *
 * This is the key trick for surviving low-memory kills and task-swipe: the dying
 * service can't restart itself reliably, but it can ask this receiver to.
 */
@AndroidEntryPoint
class RestarterReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTART) return
        val enabled = runBlocking { prefs.trackingEnabled.first() }
        Log.i(TAG, "Restart requested — trackingEnabled=$enabled")
        if (enabled && TrackingCoordinator.canTrack(context)) {
            LocationTrackingService.start(context)
        }
    }

    companion object {
        const val ACTION_RESTART = "com.roamly.RESTART_TRACKING"

        fun broadcast(context: Context) {
            context.sendBroadcast(
                Intent(context, RestarterReceiver::class.java).setAction(ACTION_RESTART)
            )
        }
    }
}
