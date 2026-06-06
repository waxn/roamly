package com.roamly.tracking

import android.Manifest
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.roamly.data.prefs.UserPreferences
import com.roamly.receiver.TrackingAlarmReceiver
import kotlinx.coroutines.flow.first

object TrackingCoordinator {

    fun canTrack(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PermissionChecker.PERMISSION_GRANTED
    }

    /**
     * Resume tracking on app launch — keyed on the durable `trackingEnabled` flag
     * (the authoritative "tracking is supposed to be running" intent), which a full
     * Stop clears. This is independent of the "start on boot" option, which only
     * governs the boot/update receiver. So: a running session resumes after a
     * process kill; a stopped one stays stopped.
     */
    suspend fun resumeTrackingIfNeeded(context: Context, prefs: UserPreferences) {
        val enabled = prefs.trackingEnabled.first()
        if (!enabled || !canTrack(context)) return
        LocationTrackingService.start(context)
    }

    /**
     * Authoritative full stop. Turns off every piece of the survivability stack so
     * tracking is *completely* off and stays off across reboots:
     *  - clears the durable `trackingEnabled` flag (so START_STICKY/null-intent
     *    restart, the heartbeat and the restarter all bow out)
     *  - clears `autoStartTracking` so the boot/update receiver won't bring it back
     *  - cancels the Doze-piercing heartbeat alarm
     *  - cancels the recurring background upload worker (one final flush is kicked
     *    off so already-recorded points still make it to the server)
     *  - stops the foreground service
     *
     * Safe to call whether or not the service is currently running.
     */
    suspend fun stopCompletely(context: Context, prefs: UserPreferences) {
        Log.i("TrackingCoordinator", "Full stop requested — disabling everything")
        prefs.setTrackingEnabled(false)
        prefs.setAutoStartTracking(false)
        prefs.setTrackingActive(false)
        TrackingAlarmReceiver.cancel(context)
        UploadWorker.cancelPeriodic(context)
        // Flush whatever is still cached so a stop never silently drops points.
        runCatching { UploadWorker.scheduleNow(context, prefs.syncOnMobileData.first()) }
        LocationTrackingService.stop(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}
