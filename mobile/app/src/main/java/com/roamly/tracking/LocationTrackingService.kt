package com.roamly.tracking

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.roamly.MainActivity
import com.roamly.R
import com.roamly.RoamlyApp
import com.roamly.data.prefs.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001

const val ACTION_STOP    = "com.roamly.STOP"
const val ACTION_PAUSE   = "com.roamly.PAUSE"
const val ACTION_RESUME  = "com.roamly.RESUME"

@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var db: TrackingDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val filter = LocationFilter()
    private var isPaused = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE  -> { isPaused = true;  filter.reset(); updateNotification(); return START_STICKY }
            ACTION_RESUME -> { isPaused = false; updateNotification(); return START_STICKY }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        applyFilterSettings()
        startLocationUpdates()
        scope.launch { prefs.setTrackingActive(true) }
        Log.i(TAG, "Tracking started")
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch { prefs.setTrackingActive(false) }
        stopLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Location ───────────────────────────────────────────────────────────

    private fun applyFilterSettings() {
        scope.launch {
            filter.maxAccuracyMetres = prefs.maxAccuracyM.first().toFloat()
            filter.minDisplacementMetres = prefs.minDisplacementM.first().toFloat()
        }
    }

    @Suppress("MissingPermission")
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
        fusedClient.requestLocationUpdates(buildRequest(), locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun buildRequest(): LocationRequest {
        return when (runBlocking { prefs.trackingMode.first() }) {
            "precision" -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateDistanceMeters(2f).build()
            "low_power" -> LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 5 * 60_000L)
                .setMinUpdateDistanceMeters(50f).build()
            else        -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                .setMinUpdateDistanceMeters(5f).build()
        }
    }

    private suspend fun savePoint(loc: android.location.Location) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }

        db.pointDao().insert(
            CachedPoint(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                accuracy  = if (loc.hasAccuracy()) loc.accuracy else null,
                altitude  = if (loc.hasAltitude()) loc.altitude else null,
                speed     = if (loc.hasSpeed()) loc.speed else null,
                battery   = battery,
                timestamp = loc.time,
                provider  = loc.provider,
            )
        )
        Log.d(TAG, "Saved ${loc.latitude},${loc.longitude} acc=${loc.accuracy}m")

        if (db.pointDao().unsyncedCount() >= 10) {
            UploadWorker.scheduleNow(applicationContext)
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val toggleIntent = if (isPaused) {
            PendingIntent.getService(this, 2,
                Intent(this, LocationTrackingService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 2,
                Intent(this, LocationTrackingService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, RoamlyApp.CHANNEL_TRACKING)
            .setContentTitle(if (isPaused) "Roamly — Paused" else "Roamly — Tracking")
            .setContentText(if (isPaused) "Tap to open" else "Collecting location in background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause", toggleIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, LocationTrackingService::class.java).apply { action = ACTION_STOP })
    }
}
