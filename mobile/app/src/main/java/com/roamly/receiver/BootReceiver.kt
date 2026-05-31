package com.roamly.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.LocationTrackingService
import com.roamly.tracking.TrackingCoordinator
import com.roamly.tracking.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

private const val TAG = "BootReceiver"

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) return

        val (shouldAutoStart, syncOnMobileData) = runBlocking {
            prefs.autoStartTracking.first() to prefs.syncOnMobileData.first()
        }
        Log.i(TAG, "Boot received — auto start tracking: $shouldAutoStart")
        if (shouldAutoStart && TrackingCoordinator.canTrack(context)) {
            LocationTrackingService.start(context)
        }
        UploadWorker.schedulePeriodic(context, syncOnMobileData)
    }
}
