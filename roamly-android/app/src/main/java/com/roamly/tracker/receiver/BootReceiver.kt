package com.roamly.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.roamly.tracker.service.LocationTrackingService
import com.roamly.tracker.worker.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.content.SharedPreferences

private const val TAG = "BootReceiver"

/**
 * Restarts tracking after device reboot (or app update) if the user had
 * tracking enabled when the device was last on.
 *
 * Reliability note:
 * - Works perfectly on stock Android / Pixel.
 * - Samsung, Xiaomi, Oppo, etc. may block this unless the user enables
 *   "Allow auto-launch" in the device's battery / app management settings.
 *   The app detects this via [com.roamly.tracker.util.BatteryOptimizationHelper]
 *   and guides the user through it.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: SharedPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) return

        val wasActive = prefs.getBoolean(LocationTrackingService.PREF_TRACKING_ACTIVE, false)
        Log.i(TAG, "Boot/update received — tracking was active: $wasActive")

        if (wasActive) {
            LocationTrackingService.start(context)
        }

        // Always (re)schedule periodic upload so it survives reboots
        UploadWorker.schedulePeriodicSync(context)
    }
}
