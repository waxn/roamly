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
import com.google.android.gms.tasks.CancellationTokenSource
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
import kotlin.coroutines.resume
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001
private const val UPLOAD_SCHEDULE_MIN_INTERVAL_MS = 60_000L
private const val UPLOAD_BATCH_TRIGGER_COUNT = 10
// Backlog size that signals the uploader is wedged (in WorkManager backoff) rather
// than just keeping pace — at which point we REPLACE the stuck job instead of KEEP.
// ~40 points ≈ 6+ min of a 10s-interval track: well beyond a healthy 0–10 backlog.
private const val UPLOAD_STUCK_THRESHOLD = 40
private const val WATCHDOG_CHECK_MS = 60_000L
// Capture-mode ceilings (interval at/below → *continuous* stream + pinned wake lock;
// above → *alarm* cadence, one discrete fix per exact-alarm wake while the CPU sleeps).
// The alarm cadence is the GPSLogger model that survives Doze (the exact
// `setExactAndAllowWhileIdle` alarm is the per-point heartbeat) but cold-starts the GPS
// each fix. Continuous keeps the GPS *warm* and the cadence tight — but only stays alive
// screen-off if the CPU stays awake, so we only choose it when pinning the CPU is worth it:
//  - Not battery-exempt → the OS throttles a backgrounded stream in Doze regardless, so
//    only the very fastest interval (≤5s, where the CPU is awake anyway) uses continuous.
//  - Battery-exempt → the app may hold the CPU awake through Doze, so short intervals
//    (≤20s, e.g. a 10s track) use warm continuous for smoothness; longer intervals still
//    prefer alarm mode (pinning the CPU for 30s+ between fixes wastes battery, and an
//    exempt app's exact alarms fire on time anyway).
private const val CONTINUOUS_MAX_INTERVAL_MS = 5_000L
private const val WARM_CONTINUOUS_MAX_MS = 20_000L
// How long a single fix request may run before we give up on this cycle and reschedule.
private const val FIX_ACQUISITION_TIMEOUT_MS = 25_000L
private const val FIX_WAKELOCK_MARGIN_MS = 5_000L
// After a missed fix (null/slow/filtered) retry this soon instead of waiting a whole
// interval, so one dropped fix doesn't become an interval-long gap...
private const val FIX_RETRY_DELAY_MS = 4_000L
// ...but only a few times — then back off to the normal interval so a persistently
// unavailable GPS (indoors, no sky) doesn't hammer the chip every few seconds.
private const val MAX_FAST_RETRIES = 3
private const val FIX_ALARM_REQUEST_CODE = 7013
// GPS reports a phantom sub-walking-pace speed when the device is actually stationary
// (each fix lands a metre or two from the last). Floor anything under ~1 mph to 0 so a
// standing-still track shows 0, not a misleading 0.3 mph drift.
private const val MIN_LOGGED_SPEED_MPS = 0.45f  // ≈ 1 mph (0.44704 m/s)

const val ACTION_STOP     = "com.roamly.STOP"
const val ACTION_PAUSE    = "com.roamly.PAUSE"
const val ACTION_RESUME   = "com.roamly.RESUME"
const val ACTION_TAKE_FIX = "com.roamly.TAKE_FIX"

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
    private var initialized = false
    private var lastUploadScheduleAt = System.currentTimeMillis()
    private var syncOnMobileData = true
    /** True while running the alarm-driven discrete-fix cadence (interval > continuous max);
     *  false while running the continuous FusedLocation stream. */
    @Volatile private var alarmMode = false
    @Volatile private var fixInProgress = false
    @Volatile private var consecutiveMisses = 0
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
                // leave it stopped — and cancel the heartbeat + recurring upload + the
                // per-point fix alarm so no invasive background work survives.
                runBlocking {
                    prefs.setTrackingEnabled(false)
                    prefs.setAutoStartTracking(false)
                }
                cancelNextFix()
                TrackingAlarmReceiver.cancel(applicationContext)
                UploadWorker.cancelPeriodic(applicationContext)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE  -> {
                isPaused = true; filter.reset(); cancelNextFix(); releaseWakeLock(); updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                currentConfig?.let { applyMode(it) } ?: updateWakeLock()
                updateNotification()
                return START_STICKY
            }
            ACTION_TAKE_FIX -> {
                // The per-point exact alarm fired. Take one fix and re-arm the next.
                val enabled = runBlocking { prefs.trackingEnabled.first() }
                if (!enabled) { cancelNextFix(); stopSelf(); return START_NOT_STICKY }
                if (!initialized) {
                    // The OS killed us and the alarm is recreating us cold — full init
                    // re-establishes config/observers and kicks the first fix itself.
                    initTracking()
                } else if (alarmMode && !isPaused) {
                    runFixCycle()
                }
                goForeground()
                return START_STICKY
            }
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

        if (!initialized) {
            initTracking()
        } else if (alarmMode && !isPaused) {
            // A repeat start of an already-running service (the 15-min heartbeat, a
            // provider change, etc.). Only nudge the cadence if it looks stalled, so a
            // healthy tracker isn't perturbed into extra fixes.
            val cfg = currentConfig
            val stale = cfg != null && (System.currentTimeMillis() - lastAcceptedAtMs) > cfg.intervalMs * 2
            if (lastAcceptedAtMs == 0L || stale) runFixCycle()
        }

        // Defensive #3: startForeground is idempotent — a final call guarantees the
        // foreground state held even if an earlier call raced with setup.
        goForeground()

        Log.i(TAG, "Tracking started")
        return START_STICKY
    }

    /** One-time setup of the live observers, watchdog and heartbeat. The config
     *  observer's first emission picks the mode and takes the first point. */
    private fun initTracking() {
        scope.launch {
            prefs.setTrackingEnabled(true)
            prefs.setTrackingActive(true)
        }
        observeRuntimePreferences()
        observeConfig()  // first emit → applyMode(): starts the stream or the first fix
        startWatchdog()
        registerProviderChangeReceiver()
        TrackingAlarmReceiver.schedule(applicationContext)
        initialized = true
    }

    /** Acquire or release the pinned wake lock based on mode + pause state. Continuous
     *  mode always pins it (the CPU must stay awake to keep the live stream + GPS alive
     *  screen-off); alarm mode uses a short time-boxed lock per fix instead, so the CPU
     *  can sleep between fixes and save battery. */
    private fun updateWakeLock() {
        if (!isPaused && !alarmMode) acquireWakeLock() else releaseWakeLock()
    }

    /** Hold a partial wake lock so the CPU doesn't sleep during a fix (which would defer
     *  the location callback). With a positive [timeoutMs] the lock auto-releases, so a
     *  per-fix lock can never leak even if a cycle is interrupted. An app exempt from
     *  battery optimization may hold this through Doze. Not reference-counted, so
     *  acquire/release are idempotent. */
    private fun acquireWakeLock(timeoutMs: Long = 0L) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roamly::tracking").apply {
                setReferenceCounted(false)
                if (timeoutMs > 0L) acquire(timeoutMs) else acquire()
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
        // a separate receiver can. The pending fix alarm (if any) is left armed on
        // purpose — it's an extra independent restart path; the revived service re-anchors
        // the cadence cleanly via applyMode().
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

    /** Re-arm location capture whenever the set of enabled providers changes (e.g. the
     *  user toggles GPS, or it flips on entering/leaving a tunnel). FusedLocation already
     *  fuses GPS + network + passive internally; this makes it recover immediately
     *  instead of waiting out the next interval. */
    private fun registerProviderChangeReceiver() {
        if (providerReceiver != null) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cfg = currentConfig ?: return
                if (isPaused) return
                Log.i(TAG, "Location providers changed — re-arming capture")
                if (alarmMode) runFixCycle()
                else { startLocationUpdates(cfg); seedLastLocation() }
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

    /** Observe the tracking knobs; whenever any change, re-apply the capture mode. */
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
                // Allow a fix to be up to two intervals old before it's "stale", so a
                // freshly-acquired or post-wake fix is never dropped for lagging now().
                filter.maxAgeMs = (cfg.intervalMs * 2).coerceAtLeast(30_000L)
                Log.i(TAG, "Applying config: interval=${cfg.intervalMs}ms priority=${cfg.priority} maxAcc=${cfg.maxAccuracyM}m")
                applyMode(cfg)
            }
        }
    }

    /** Pick continuous vs alarm mode for the current interval and (re)establish capture.
     *  Battery-exempt apps can hold the CPU awake through Doze, so for short intervals the
     *  warm continuous stream is both viable and smoother than cold-starting the GPS on
     *  every alarm; non-exempt apps get a stream throttled in Doze, so only the fastest
     *  interval uses it and everything else rides the exact-alarm cadence. */
    private fun applyMode(cfg: TrackingConfig) {
        val exempt = TrackingCoordinator.isIgnoringBatteryOptimizations(this)
        val continuousCeiling = if (exempt) WARM_CONTINUOUS_MAX_MS else CONTINUOUS_MAX_INTERVAL_MS
        alarmMode = cfg.intervalMs > continuousCeiling
        Log.i(TAG, "applyMode: interval=${cfg.intervalMs}ms exempt=$exempt → ${if (alarmMode) "alarm" else "continuous"}")
        if (alarmMode) {
            stopLocationUpdates()        // no continuous stream in alarm mode
            updateWakeLock()             // releases the pinned lock
            cancelNextFix()              // re-anchor the cadence cleanly
            if (!isPaused) runFixCycle() // take one now; it schedules the next
        } else {
            cancelNextFix()              // leaving alarm mode: drop any pending fix alarm
            startLocationUpdates(cfg)    // live stream
            updateWakeLock()             // pin the lock
            seedLastLocation()           // immediate first point
        }
    }

    private fun observeRuntimePreferences() {
        syncPrefJob?.cancel()
        syncPrefJob = scope.launch {
            prefs.syncOnMobileData.collect { syncOnMobileData = it }
        }
    }

    // ── Location: alarm-driven discrete fix ───────────────────────────────────

    /** One cycle of the GPSLogger-style loop: wake → single fresh fix → save → schedule
     *  the next exact alarm. The exact alarm (not a continuous stream) is what carries
     *  the cadence through Doze. Reschedules even on a missed fix so the loop never dies. */
    private fun runFixCycle() {
        if (isPaused || fixInProgress) return
        val cfg = currentConfig ?: return
        fixInProgress = true
        // Time-boxed lock: covers the acquisition window and auto-releases if anything
        // interrupts the cycle, so it can never leak and pin the CPU between fixes.
        acquireWakeLock(timeoutMs = FIX_ACQUISITION_TIMEOUT_MS + FIX_WAKELOCK_MARGIN_MS)
        scope.launch {
            var got = false
            try {
                val loc = requestSingleFix(cfg)
                got = loc != null && !isPaused && filter.accept(loc)
                if (got) savePoint(loc!!)
                else Log.d(TAG, "Fix cycle produced no usable point (null/filtered)")
            } catch (t: Throwable) {
                Log.e(TAG, "Fix cycle failed", t)
            } finally {
                releaseWakeLock()
                fixInProgress = false
                consecutiveMisses = if (got) 0 else consecutiveMisses + 1
                // Re-arm from the *current* config so an interval change mid-cycle takes
                // effect on the next wake. Only while still in alarm mode and not paused.
                // A transient miss retries soon (so a single dropped fix isn't a full gap),
                // backing off to the normal interval once GPS is persistently unavailable.
                if (alarmMode && !isPaused) currentConfig?.let { c ->
                    val nextMs = if (consecutiveMisses in 1..MAX_FAST_RETRIES)
                        minOf(c.intervalMs, FIX_RETRY_DELAY_MS) else c.intervalMs
                    scheduleNextFix(nextMs)
                }
                updateNotification()
            }
        }
    }

    /** Request a single fresh location, honouring the user's priority ("auto" → high
     *  accuracy for reliability), bounded by an acquisition timeout so a cycle can't hang. */
    @Suppress("MissingPermission")
    private suspend fun requestSingleFix(cfg: TrackingConfig): android.location.Location? {
        val priority = when (cfg.priority) {
            "high"     -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low"      -> Priority.PRIORITY_LOW_POWER
            else       -> Priority.PRIORITY_HIGH_ACCURACY  // "auto": reliability-first
        }
        val timeoutMs = minOf(cfg.intervalMs - 2_000L, FIX_ACQUISITION_TIMEOUT_MS).coerceAtLeast(4_000L)
        val cts = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    fusedClient.getCurrentLocation(priority, cts.token)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Single fix request failed", t)
            null
        } finally {
            cts.cancel()
        }
    }

    /** Schedule the next discrete fix via an exact, Doze-piercing alarm targeting this
     *  service directly (the GPSLogger pattern). `setExactAndAllowWhileIdle` fires even in
     *  deep Doze, and on time when the app is battery-optimization-exempt. */
    private fun scheduleNextFix(intervalMs: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        val pi = fixPendingIntent()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelNextFix() {
        getSystemService(AlarmManager::class.java)?.cancel(fixPendingIntent())
    }

    private fun fixPendingIntent(): PendingIntent {
        val intent = Intent(this, LocationTrackingService::class.java).setAction(ACTION_TAKE_FIX)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(this, FIX_ALARM_REQUEST_CODE, intent, flags)
        else
            PendingIntent.getService(this, FIX_ALARM_REQUEST_CODE, intent, flags)
    }

    // ── Location: continuous stream (short intervals only) ─────────────────────

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
            else       -> Priority.PRIORITY_HIGH_ACCURACY  // "auto" continuous: short interval → high
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

    /** Continuous-mode watchdog: re-arm the live stream if fixes stall. It's a no-op in
     *  alarm mode (the exact alarm carries the cadence there) and inherently can't run in
     *  Doze anyway — coroutine delay() is frozen while the CPU sleeps, which is exactly
     *  why alarm mode exists. The 15-min exact heartbeat is the deep-Doze liveness check. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                if (isPaused) continue
                updateNotification()
                if (alarmMode) continue
                val cfg = currentConfig ?: continue
                val staleThreshold = maxOf(cfg.intervalMs * 4, 90_000L)
                val sinceLast = System.currentTimeMillis() - lastAcceptedAtMs
                if (lastAcceptedAtMs == 0L || sinceLast > staleThreshold) {
                    Log.w(TAG, "Watchdog: no fix for ${sinceLast}ms — re-arming location updates")
                    startLocationUpdates(cfg)
                    seedLastLocation()
                }
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
            speed     = when {
                !loc.hasSpeed()                    -> null
                loc.speed >= MIN_LOGGED_SPEED_MPS  -> loc.speed
                else                               -> 0f   // stationary: drop GPS jitter speed
            },
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
