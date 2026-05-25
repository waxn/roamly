package com.roamly.tracker.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.roamly.tracker.R
import com.roamly.tracker.db.AppDatabase
import com.roamly.tracker.db.CachedPoint
import com.roamly.tracker.ui.MainActivity
import com.roamly.tracker.util.LocationFilter
import com.roamly.tracker.worker.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "roamly_tracking"

/** Intent actions for the notification buttons. */
const val ACTION_STOP_TRACKING  = "com.roamly.tracker.STOP"
const val ACTION_PAUSE_TRACKING = "com.roamly.tracker.PAUSE"
const val ACTION_RESUME_TRACKING = "com.roamly.tracker.RESUME"

/**
 * Foreground service that continuously collects GPS points.
 *
 * Lifecycle:
 *  - Started by [MainActivity] or [com.roamly.tracker.receiver.BootReceiver]
 *  - Runs indefinitely until explicitly stopped (notification button or settings)
 *  - [android:stopWithTask="false"] in the manifest keeps it alive after app swipe-away
 *  - Uses FusedLocationProviderClient; priority + interval come from SharedPreferences
 *  - Every accepted point is written to the Room DB immediately (offline-safe)
 *  - Triggers [UploadWorker] after every N accepted points for near-real-time sync
 */
@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var db: AppDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val filter = LocationFilter()
    private var isPaused = false

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        registerActionReceiver()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRACKING  -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE_TRACKING -> { pauseTracking(); return START_STICKY }
            ACTION_RESUME_TRACKING -> { resumeTracking(); return START_STICKY }
        }

        // Normal start — load prefs and begin tracking
        applyPrefs()
        startForeground(NOTIFICATION_ID, buildNotification(paused = false))
        startLocationUpdates()

        // Mark as active in prefs so BootReceiver knows to restart us
        prefs.edit().putBoolean(PREF_TRACKING_ACTIVE, true).apply()

        Log.i(TAG, "Tracking started")
        return START_STICKY   // system will restart us if killed
    }

    override fun onDestroy() {
        prefs.edit().putBoolean(PREF_TRACKING_ACTIVE, false).apply()
        stopLocationUpdates()
        unregisterReceiver(actionReceiver)
        scope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Location updates ───────────────────────────────────────────────────

    private fun applyPrefs() {
        val mode = prefs.getString(PREF_TRACKING_MODE, MODE_BALANCED) ?: MODE_BALANCED
        filter.maxAccuracyMetres = prefs.getInt(PREF_MAX_ACCURACY_M, 100).toFloat()
        filter.minDisplacementMetres = prefs.getInt(PREF_MIN_DISPLACEMENT_M, 5).toFloat()
        // minIntervalMs handled by the FusedLocation request itself
    }

    private fun buildLocationRequest(): LocationRequest {
        val mode = prefs.getString(PREF_TRACKING_MODE, MODE_BALANCED) ?: MODE_BALANCED
        return when (mode) {
            MODE_PRECISION -> LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5_000L
            ).setMinUpdateDistanceMeters(2f).build()

            MODE_LOW_POWER -> LocationRequest.Builder(
                Priority.PRIORITY_LOW_POWER, 5 * 60_000L
            ).setMinUpdateDistanceMeters(50f).build()

            else -> /* BALANCED */ LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L
            ).setMinUpdateDistanceMeters(5f).build()
        }
    }

    @Suppress("MissingPermission")   // permissions checked before service is started
    private fun startLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    if (!isPaused && filter.accept(loc)) {
                        scope.launch { savePoint(loc) }
                    }
                }
            }
        }
        fusedClient.requestLocationUpdates(
            buildLocationRequest(),
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    private suspend fun savePoint(location: android.location.Location) {
        val batteryLevel = getBatteryLevel()
        val point = CachedPoint(
            latitude  = location.latitude,
            longitude = location.longitude,
            accuracy  = if (location.hasAccuracy()) location.accuracy else null,
            altitude  = if (location.hasAltitude()) location.altitude else null,
            speed     = if (location.hasSpeed()) location.speed else null,
            battery   = batteryLevel,
            timestamp = location.time,
            provider  = location.provider
        )
        db.pointDao().insert(point)
        Log.d(TAG, "Saved: ${location.latitude},${location.longitude} acc=${location.accuracy}m")

        // Trigger an immediate upload attempt if we have pending points
        val unsynced = db.pointDao().unsyncedCount()
        if (unsynced >= UPLOAD_TRIGGER_THRESHOLD) {
            UploadWorker.scheduleNow(applicationContext)
        }
    }

    // ── Pause / resume ─────────────────────────────────────────────────────

    private fun pauseTracking() {
        isPaused = true
        filter.reset()
        updateNotification(paused = true)
        Log.i(TAG, "Tracking paused")
    }

    private fun resumeTracking() {
        isPaused = false
        updateNotification(paused = false)
        Log.i(TAG, "Tracking resumed")
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW   // silent — no sound/vibration
        ).apply {
            description = "Shows while Roamly is tracking your location"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(paused: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP_TRACKING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (paused) {
            val resumeIntent = PendingIntent.getService(
                this, 2,
                Intent(this, LocationTrackingService::class.java).apply { action = ACTION_RESUME_TRACKING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_play, "Resume", resumeIntent)
        } else {
            val pauseIntent = PendingIntent.getService(
                this, 2,
                Intent(this, LocationTrackingService::class.java).apply { action = ACTION_PAUSE_TRACKING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", pauseIntent)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "Roamly — Paused" else "Roamly — Tracking")
            .setContentText(if (paused) "Tap to open app" else "Collecting location in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(toggleAction)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(paused: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(paused))
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level < 0) null else level
    }

    // Receiver for notification button intents broadcast directly
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP_TRACKING   -> stopSelf()
                ACTION_PAUSE_TRACKING  -> pauseTracking()
                ACTION_RESUME_TRACKING -> resumeTracking()
            }
        }
    }

    private fun registerActionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_TRACKING)
            addAction(ACTION_PAUSE_TRACKING)
            addAction(ACTION_RESUME_TRACKING)
        }
        ContextCompat.registerReceiver(
            this, actionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    companion object {
        const val PREF_TRACKING_ACTIVE    = "tracking_active"
        const val PREF_TRACKING_MODE      = "tracking_mode"
        const val PREF_MAX_ACCURACY_M     = "max_accuracy_m"
        const val PREF_MIN_DISPLACEMENT_M = "min_displacement_m"

        const val MODE_PRECISION = "precision"
        const val MODE_BALANCED  = "balanced"
        const val MODE_LOW_POWER = "low_power"

        /** Number of unsynced points that triggers an immediate upload attempt. */
        private const val UPLOAD_TRIGGER_THRESHOLD = 10

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }
}
