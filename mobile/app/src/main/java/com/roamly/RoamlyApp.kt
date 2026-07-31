package com.roamly

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.roamly.data.prefs.UserPreferences
import com.roamly.tracking.CaptureStats
import com.roamly.tracking.TrackingCoordinator
import com.roamly.tracking.UploadWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RoamlyApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prefs: UserPreferences
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Capture counters must be readable in Diagnostics even when the tracking service
        // has never started this process.
        CaptureStats.init(this)
        appScope.launch {
            // Only keep background upload running when tracking is on or set to start
            // on boot. After a full Stop both are off, so we schedule nothing — there's
            // no recurring background work while the user has tracking turned off.
            val active = prefs.trackingEnabled.first() || prefs.autoStartTracking.first()
            if (active) UploadWorker.schedulePeriodic(this@RoamlyApp, prefs.syncOnMobileData.first())
            TrackingCoordinator.resumeTrackingIfNeeded(this@RoamlyApp, prefs)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Roamly is tracking your location"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "roamly_tracking"
    }
}
