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

        val (startOnBoot, syncOnMobileData) = runBlocking {
            Pair(
                prefs.autoStartTracking.first(),
                prefs.syncOnMobileData.first(),
            )
        }
        Log.i(TAG, "Boot received — startOnBoot=$startOnBoot")
        // "Start tracking on boot" is the single source of truth here. A full Stop
        // clears it, so after the user stops, reboot leaves everything off; if the
        // user has it on (even while tracking was off), reboot turns tracking on.
        if (startOnBoot && TrackingCoordinator.canTrack(context)) {
            LocationTrackingService.start(context)
            // Arm the Doze-piercing heartbeat directly too, in case the OS throttles
            // the service start at boot — the alarm will then bring it up.
            TrackingAlarmReceiver.schedule(context)
            UploadWorker.schedulePeriodic(context, syncOnMobileData)
        }
    }
}
