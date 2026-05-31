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

    suspend fun startTrackingOnLaunchIfEnabled(context: Context, prefs: UserPreferences) {
        val shouldAutoStart = prefs.autoStartTracking.first()
        if (!shouldAutoStart || !canTrack(context)) return
        LocationTrackingService.start(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}
