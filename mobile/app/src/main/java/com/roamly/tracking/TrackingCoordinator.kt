package com.roamly.tracking

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.roamly.data.prefs.UserPreferences
import kotlinx.coroutines.flow.first

object TrackingCoordinator {

    fun canTrack(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PermissionChecker.PERMISSION_GRANTED
    }

    /**
     * Resume tracking after a reboot or app launch — only when the user previously
     * turned it on (durable intent) AND has "auto-resume" enabled AND we can track.
     * This is what makes tracking "keep working" across reboots and OS kills.
     */
    suspend fun resumeTrackingIfNeeded(context: Context, prefs: UserPreferences) {
        val enabled = prefs.trackingEnabled.first()
        val autoResume = prefs.autoStartTracking.first()
        if (!enabled || !autoResume || !canTrack(context)) return
        LocationTrackingService.start(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}
