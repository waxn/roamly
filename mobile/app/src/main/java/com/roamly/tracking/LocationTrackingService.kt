package com.roamly.tracking

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.roamly.MainActivity
import com.roamly.R
import com.roamly.RoamlyApp
import com.roamly.data.prefs.UserPreferences
import com.roamly.receiver.RestarterReceiver
import com.roamly.receiver.TrackingAlarmReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001
private const val UPLOAD_SCHEDULE_MIN_INTERVAL_MS = 60_000L
private const val UPLOAD_BATCH_TRIGGER_COUNT = 10
// Backlog size that signals the uploader is wedged (in WorkManager backoff) rather
// than just keeping pace — at which point we REPLACE the stuck job instead of KEEP.
// ~40 points ≈ 6+ min of a 10s-interval track: well beyond a healthy 0–10 backlog.
private const val UPLOAD_STUCK_THRESHOLD = 40
private const val REQUEST_ACCURATE_LOCATION_MS = 30_000L
private const val WATCHDOG_CHECK_MS = 60_000L
// Only pin the CPU awake for short, high-fidelity intervals. There the GPS is in
// continuous mode and the CPU is waking constantly anyway, so the marginal battery
// cost is small and it keeps fixes/watchdog from stalling. At longer intervals we
// let the CPU sleep between fixes to save battery.
private const val WAKELOCK_MAX_INTERVAL_MS = 15_000L

const val ACTION_STOP    = "com.roamly.STOP"
const val ACTION_PAUSE   = "com.roamly.PAUSE"
const val ACTION_RESUME  = "com.roamly.RESUME"

/** Snapshot of the user's tracking knobs. Changes restart the location request live. */
private data class TrackingConfig(
    val intervalMs: Long,
    val priority: String,
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
    private var providerReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive #1: enter the foreground immediately on EVERY start path, before
        // anything else, so we always satisfy Android 8+'s 5-second deadline for a
        // started foreground service and never get ANR-killed.
        goForeground()

        when (intent?.action) {
            ACTION_STOP   -> {
                // Authoritative teardown: clear BOTH durable flags so the watchdog,
                // boot/update receiver, alarm heartbeat, restarter and START_STICKY all
                // leave it stopped — and cancel the heartbeat + recurring upload so no
                // invasive background work survives.
                runBlocking {
                    prefs.setTrackingEnabled(false)
                    prefs.setAutoStartTracking(false)
                }
                TrackingAlarmReceiver.cancel(applicationContext)
                UploadWorker.cancelPeriodic(applicationContext)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE  -> { isPaused = true;  filter.reset(); releaseWakeLock(); updateNotification(); return START_STICKY }
            ACTION_RESUME -> { isPaused = false; updateWakeLock(); updateNotification(); return START_STICKY }
        }

        // A null intent means the OS re-created us after a low-memory kill (START_STICKY).
        // Resume only if the user still wants tracking — read from persisted state, which
        // survives process death.
        if (intent == null) {
            val shouldRun = runBlocking { prefs.trackingEnabled.first() }
            if (!shouldRun) {
                Log.i(TAG, "Null-intent restart but tracking disabled — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "Null-intent restart — resuming from persisted state")
        }

        // Defensive #2.
        goForeground()

        scope.launch {
            prefs.setTrackingEnabled(true)
            prefs.setTrackingActive(true)
        }
        observeRuntimePreferences()
        observeConfig()  // applies the live config and calls updateWakeLock()
        startWatchdog()
        seedLastLocation()
        registerProviderChangeReceiver()
        TrackingAlarmReceiver.schedule(applicationContext)

        // Defensive #3: startForeground is idempotent — a final call guarantees the
        // foreground state held even if an earlier call raced with setup.
        goForeground()

        Log.i(TAG, "Tracking started")
        return START_STICKY
    }

    /** Acquire or release the wake lock based on the current interval + pause state:
     *  pinned only for short intervals (<= [WAKELOCK_MAX_INTERVAL_MS]) where it's
     *  cheap relative to the always-on GPS; otherwise released so the CPU can sleep
     *  between fixes and save battery. */
    private fun updateWakeLock() {
        val cfg = currentConfig
        if (!isPaused && cfg != null && cfg.intervalMs <= WAKELOCK_MAX_INTERVAL_MS) acquireWakeLock()
        else releaseWakeLock()
    }

    /** Hold a partial wake lock so the CPU doesn't sleep between frequent fixes
     *  (which would defer location callbacks and freeze the watchdog's delay() loop).
     *  An app exempt from battery optimization may hold this through Doze. Gated by
     *  [updateWakeLock] to short intervals only. Not reference-counted, so
     *  acquire/release are idempotent. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roamly::tracking").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.e(TAG, "Failed to acquire wake lock", it) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    /** Promote to a foreground service. Idempotent; safe to call repeatedly. */
    private fun goForeground() {
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { Log.e(TAG, "startForeground failed", it) }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        releaseWakeLock()
        unregisterProviderChangeReceiver()
        configJob?.cancel()
        watchdogJob?.cancel()
        syncPrefJob?.cancel()
        scope.cancel()
        // scope is cancelled, so use runBlocking to make sure the flags actually land.
        runBlocking { prefs.setTrackingActive(false) }
        // Self-resurrection: if we're being destroyed but the user still wants tracking
        // on (i.e. this wasn't an explicit STOP), ask the standalone RestarterReceiver
        // to bring us straight back. A dying service can't reliably restart itself, but
        // a separate receiver can.
        val shouldRun = runBlocking { prefs.trackingEnabled.first() }
        if (shouldRun) {
            Log.w(TAG, "Destroyed while still enabled — broadcasting restart")
            RestarterReceiver.broadcast(applicationContext)
        }
        super.onDestroy()
    }

    /** Fired when the user swipes the app off the recents screen. With
     *  stopWithTask=false the service keeps running, but we also rebroadcast a
     *  restart as insurance on OEMs that kill the process anyway. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val shouldRun = runCatching { runBlocking { prefs.trackingEnabled.first() } }.getOrDefault(false)
        if (shouldRun) RestarterReceiver.broadcast(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Provider toggle (multi-provider graceful degradation) ─────────────────

    /** Re-arm location updates whenever the set of enabled providers changes
     *  (e.g. the user toggles GPS, or it flips on entering/leaving a tunnel).
     *  FusedLocation already fuses GPS + network + passive internally; this makes
     *  it recover immediately instead of waiting out the next interval. */
    private fun registerProviderChangeReceiver() {
        if (providerReceiver != null) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cfg = currentConfig ?: return
                Log.i(TAG, "Location providers changed — re-arming updates")
                startLocationUpdates(cfg)
                seedLastLocation()
            }
        }
        providerReceiver = rcv
        runCatching { registerReceiver(rcv, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)) }
            .onFailure { Log.e(TAG, "Failed to register provider receiver", it) }
    }

    private fun unregisterProviderChangeReceiver() {
        providerReceiver?.let { runCatching { unregisterReceiver(it) } }
        providerReceiver = null
    }

    // ── Config (live) ────────────────────────────────────────────────────────

    /** Observe the tracking knobs; whenever any change, re-request location updates. */
    private fun observeConfig() {
        configJob?.cancel()
        configJob = scope.launch {
            combine(
                prefs.trackingIntervalSecs,
                prefs.locationPriority,
                prefs.maxAccuracyM,
            ) { interval, priority, maxAcc ->
                TrackingConfig(
                    intervalMs = interval.coerceIn(5, 120) * 1000L,
                    priority = priority,
                    maxAccuracyM = maxAcc.coerceAtLeast(1).toFloat(),
                )
            }.distinctUntilChanged().collect { cfg ->
                currentConfig = cfg
                filter.maxAccuracyMetres = cfg.maxAccuracyM
                filter.minTimeBetweenMs = cfg.intervalMs
                Log.i(TAG, "Applying config: interval=${cfg.intervalMs}ms priority=${cfg.priority} maxAcc=${cfg.maxAccuracyM}m")
                startLocationUpdates(cfg)
                updateWakeLock()
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
        val priority = when (cfg.priority) {
            "high"     -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low"      -> Priority.PRIORITY_LOW_POWER
            else       -> if (intervalMs <= REQUEST_ACCURATE_LOCATION_MS)
                Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        // One fix per interval, on time, regardless of movement:
        //  - no setMinUpdateIntervalMillis: the floor defaults to the interval, so
        //    the provider won't deliver (and burn battery on) extra sub-interval fixes.
        //  - maxUpdateDelay == interval: no batching, deliver each fix as produced.
        //  - minUpdateDistance 0: never gate delivery on displacement — stationary
        //    still logs every interval (those stacked dots are the dwell feature).
        //  - don't wait for an "accurate" fix; take what comes.
        return LocationRequest.Builder(priority, intervalMs)
            .setMaxUpdateDelayMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
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
        val unsynced = db.pointDao().unsyncedCount()
        val reachedTimeThreshold = now - lastUploadScheduleAt >= UPLOAD_SCHEDULE_MIN_INTERVAL_MS
        val shouldSchedule = reachedTimeThreshold || unsynced >= UPLOAD_BATCH_TRIGGER_COUNT
        if (shouldSchedule) {
            lastUploadScheduleAt = now
            // If the backlog has grown well past one upload cycle, a prior job is
            // almost certainly wedged in WorkManager's exponential backoff — and a
            // KEEP enqueue would let it keep starving us for up to an hour. Force a
            // fresh run with REPLACE to break the stuck job. Gated on the time
            // threshold so REPLACE recurs at most once per cycle (never interrupting
            // a healthy in-flight flush, which clears the backlog in well under it).
            val backloggedStuck = unsynced >= UPLOAD_STUCK_THRESHOLD && reachedTimeThreshold
            UploadWorker.scheduleNow(applicationContext, syncOnMobileData, replace = backloggedStuck)
        }
        updateNotification()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Stop from the notification opens the app to a confirmation dialog rather
        // than stopping silently — stopping turns off a lot of machinery, so it's
        // gated behind an explicit "yes" the same way the in-app button is.
        val stopIntent = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_CONFIRM_STOP
                putExtra(MainActivity.EXTRA_CONFIRM_STOP, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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
        /**
         * Start (or refresh) the tracker. Guarded because on Android 12+ starting a
         * foreground service from the background throws unless the caller is exempt
         * (exact alarm, boot, or battery-optimization allowlist). The Doze heartbeat
         * alarm IS exempt and is the reliable restart path; other callers degrade
         * gracefully instead of crashing if a particular start is disallowed.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
            }.onFailure { Log.w(TAG, "startForegroundService blocked (will retry via heartbeat)", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, LocationTrackingService::class.java).apply { action = ACTION_STOP })
            }.onFailure { Log.w(TAG, "stop failed", it) }
        }
    }
}
