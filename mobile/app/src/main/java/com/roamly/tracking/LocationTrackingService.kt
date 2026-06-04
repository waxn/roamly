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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001
private const val UPLOAD_SCHEDULE_MIN_INTERVAL_MS = 60_000L
private const val UPLOAD_BATCH_TRIGGER_COUNT = 10
private const val REQUEST_ACCURATE_LOCATION_MS = 30_000L
private const val WATCHDOG_CHECK_MS = 60_000L

const val ACTION_STOP    = "com.roamly.STOP"
const val ACTION_PAUSE   = "com.roamly.PAUSE"
const val ACTION_RESUME  = "com.roamly.RESUME"

/** Snapshot of the user's tracking knobs. Changes restart the location request live. */
private data class TrackingConfig(
    val intervalMs: Long,
    val priority: String,
    val minDistanceM: Float,
    val maxAccuracyM: Float,
)

@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var db: TrackingDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val filter = LocationFilter()
    private var isPaused = false
    private var lastUploadScheduleAt = System.currentTimeMillis()
    private var syncOnMobileData = true
    @Volatile private var currentConfig: TrackingConfig? = null
    @Volatile private var lastAcceptedLocation: android.location.Location? = null
    @Volatile private var lastAcceptedAtMs: Long = 0L
    private var configJob: Job? = null
    private var watchdogJob: Job? = null
    private var syncPrefJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> {
                // The user explicitly stopped — clear the intent flag so the watchdog,
                // boot receiver and START_STICKY restart all leave it stopped.
                runBlocking { prefs.setTrackingEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE  -> { isPaused = true;  filter.reset(); updateNotification(); return START_STICKY }
            ACTION_RESUME -> { isPaused = false; updateNotification(); return START_STICKY }
        }

        // Normal start (user pressed Start, boot, app launch, or START_STICKY restart).
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            prefs.setTrackingEnabled(true)
            prefs.setTrackingActive(true)
        }
        observeRuntimePreferences()
        observeConfig()
        startWatchdog()
        seedLastLocation()
        Log.i(TAG, "Tracking started")
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        configJob?.cancel()
        watchdogJob?.cancel()
        syncPrefJob?.cancel()
        scope.cancel()
        // scope is cancelled, so use runBlocking to make sure the flag actually lands.
        runBlocking { prefs.setTrackingActive(false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Config (live) ────────────────────────────────────────────────────────

    /** Observe the tracking knobs; whenever any change, re-request location updates. */
    private fun observeConfig() {
        configJob?.cancel()
        configJob = scope.launch {
            combine(
                prefs.trackingIntervalSecs,
                prefs.locationPriority,
                prefs.minDistanceM,
                prefs.maxAccuracyM,
            ) { interval, priority, minDist, maxAcc ->
                TrackingConfig(
                    intervalMs = interval.coerceIn(5, 3600) * 1000L,
                    priority = priority,
                    minDistanceM = minDist.coerceAtLeast(0).toFloat(),
                    maxAccuracyM = maxAcc.coerceAtLeast(1).toFloat(),
                )
            }.distinctUntilChanged().collect { cfg ->
                currentConfig = cfg
                filter.minDistanceMetres = cfg.minDistanceM
                filter.maxAccuracyMetres = cfg.maxAccuracyM
                Log.i(TAG, "Applying config: interval=${cfg.intervalMs}ms priority=${cfg.priority} minDist=${cfg.minDistanceM}m maxAcc=${cfg.maxAccuracyM}m")
                startLocationUpdates(cfg)
            }
        }
    }

    private fun observeRuntimePreferences() {
        syncPrefJob?.cancel()
        syncPrefJob = scope.launch {
            prefs.syncOnMobileData.collect { syncOnMobileData = it }
        }
    }

    // ── Location ───────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun startLocationUpdates(cfg: TrackingConfig) {
        stopLocationUpdates()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    if (!isPaused && filter.accept(loc)) {
                        scope.launch { savePoint(loc) }
                    }
                }
            }
        }
        locationCallback = callback
        runCatching {
            fusedClient.requestLocationUpdates(buildRequest(cfg), callback, Looper.getMainLooper())
        }.onFailure { Log.e(TAG, "Failed to request location updates", it) }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    /** Grab the last known fix immediately so the first point doesn't wait a full interval. */
    @Suppress("MissingPermission")
    private fun seedLastLocation() {
        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !isPaused && filter.accept(loc)) {
                    scope.launch { savePoint(loc) }
                }
            }
        }
    }

    private fun buildRequest(cfg: TrackingConfig): LocationRequest {
        val intervalMs = cfg.intervalMs
        val fastestIntervalMs = (intervalMs / 2).coerceAtLeast(5_000L)
        val maxDelayMs = when {
            intervalMs <= 15_000L -> intervalMs
            intervalMs <= 60_000L -> intervalMs * 2
            else -> minOf(intervalMs * 3, TimeUnit.MINUTES.toMillis(15))
        }
        val priority = when (cfg.priority) {
            "high"     -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low"      -> Priority.PRIORITY_LOW_POWER
            else       -> if (intervalMs <= REQUEST_ACCURATE_LOCATION_MS)
                Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        return LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(fastestIntervalMs)
            .setMaxUpdateDelayMillis(maxDelayMs)
            .setMinUpdateDistanceMeters(cfg.minDistanceM)
            .setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
            .build()
    }

    /** Periodically check fixes are still flowing; re-arm the request if they stall.
     *  This recovers from the OS quietly dropping updates under Doze/battery saver. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                if (isPaused) continue
                val cfg = currentConfig ?: continue
                val staleThreshold = maxOf(cfg.intervalMs * 4, 90_000L)
                val sinceLast = System.currentTimeMillis() - lastAcceptedAtMs
                if (lastAcceptedAtMs == 0L || sinceLast > staleThreshold) {
                    Log.w(TAG, "Watchdog: no fix for ${sinceLast}ms — re-arming location updates")
                    startLocationUpdates(cfg)
                    seedLastLocation()
                }
                updateNotification()
            }
        }
    }

    private suspend fun savePoint(loc: android.location.Location) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }

        val point = CachedPoint(
            latitude  = loc.latitude,
            longitude = loc.longitude,
            accuracy  = if (loc.hasAccuracy()) loc.accuracy else null,
            altitude  = if (loc.hasAltitude()) loc.altitude else null,
            speed     = if (loc.hasSpeed()) loc.speed else null,
            battery   = battery,
            timestamp = loc.time,
            provider  = loc.provider,
        )
        db.pointDao().insert(point)
        lastAcceptedLocation = loc
        lastAcceptedAtMs = System.currentTimeMillis()
        runCatching { CsvPointLogger.appendPoint(applicationContext, point) }
            .onFailure { Log.e(TAG, "Failed to append point CSV", it) }
        Log.d(TAG, "Saved ${loc.latitude},${loc.longitude} acc=${loc.accuracy}m")
        val now = System.currentTimeMillis()
        val reachedTimeThreshold = now - lastUploadScheduleAt >= UPLOAD_SCHEDULE_MIN_INTERVAL_MS
        val shouldSchedule = reachedTimeThreshold || db.pointDao().unsyncedCount() >= UPLOAD_BATCH_TRIGGER_COUNT
        if (shouldSchedule) {
            lastUploadScheduleAt = now
            UploadWorker.scheduleNow(applicationContext, syncOnMobileData)
        }
        updateNotification()
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
            .setContentTitle(if (isPaused) "Roamly paused" else "Roamly tracking")
            .setContentText(notificationBody())
            .setSmallIcon(R.drawable.ic_roamly_mark)
            .setContentIntent(openIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .build()
    }

    private fun notificationBody(): String {
        if (isPaused) return "Tracking is paused"
        val loc = lastAcceptedLocation ?: return "Waiting for the next fix"
        val ageSec = ((System.currentTimeMillis() - lastAcceptedAtMs) / 1000L).coerceAtLeast(0)
        val accuracy = if (loc.hasAccuracy()) "${loc.accuracy.toInt()}m" else "unknown accuracy"
        val age = when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
        return "Last fix $age · $accuracy"
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
