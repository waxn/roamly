package com.roamly.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.LocationTrackingService
import com.roamly.tracking.TrackingCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

private const val TAG = "TrackingAlarmReceiver"
private const val ALARM_REQUEST_CODE = 7012
/** How often the Doze-piercing alarm fires to verify the service is alive. */
private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

/**
 * Doze-piercing heartbeat. `setExactAndAllowWhileIdle` + `ELAPSED_REALTIME_WAKEUP`
 * fires even in deep Doze, where coroutine timers and normal alarms are frozen.
 * On each fire we make sure the foreground service is running (restart it if the
 * OS killed it) and then reschedule the next alarm — a self-healing loop that
 * keeps re-arming itself for as long as the user wants tracking on.
 */
@AndroidEntryPoint
class TrackingAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT) return
        val enabled = runBlocking { prefs.trackingEnabled.first() }
        Log.i(TAG, "Heartbeat — trackingEnabled=$enabled")
        if (enabled && TrackingCoordinator.canTrack(context)) {
            // START_STICKY makes a repeat start cheap; if the service is already
            // running this is a no-op refresh, otherwise it brings it back.
            LocationTrackingService.start(context)
            schedule(context) // re-arm the loop
        } else {
            cancel(context)
        }
    }

    companion object {
        const val ACTION_HEARTBEAT = "com.roamly.TRACKING_HEARTBEAT"

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TrackingAlarmReceiver::class.java).setAction(ACTION_HEARTBEAT)
            return PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Arm the next heartbeat, using an exact Doze-piercing alarm when allowed. */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
            val pi = pendingIntent(context)
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    // No exact-alarm permission: inexact still pierces Doze, just with
                    // OS-chosen timing. Better than nothing and needs no permission.
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }
}
