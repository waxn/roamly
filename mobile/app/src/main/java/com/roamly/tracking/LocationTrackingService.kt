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
import kotlin.coroutines.resume
import javax.inject.Inject

private const val TAG = "LocationTrackingService"
private const val NOTIFICATION_ID = 1001
private const val UPLOAD_SCHEDULE_MIN_INTERVAL_MS = 60_000L
private const val UPLOAD_BATCH_TRIGGER_COUNT = 10
// Backlog size that signals the uploader is wedged (in WorkManager backoff) rather
// than just keeping pace — a count-based backstop to REPLACE the stuck job. A raw
// count scales badly with the interval (40 points ≈ 6 min at 10s but ~20 min at the
// 30s default), so it's only the ceiling; the primary trigger is time-based below.
private const val UPLOAD_STUCK_THRESHOLD = 40
// Time since the last *successful* delivery after which a non-empty backlog means the
// uploader is wedged — almost certainly asleep in WorkManager's exponential backoff
// because connectivity dropped mid-drive. WorkManager's backoff is time-based (not
// connectivity-based), so the job keeps sleeping for minutes even after signal returns,
// and a KEEP enqueue can't wake it — only a REPLACE can. Interval-independent, so a 30s
// track recovers as fast as a 10s one (vs. the ~20 min the count ceiling alone took).
private const val UPLOAD_STUCK_AGE_MS = 120_000L
private const val WATCHDOG_CHECK_MS = 60_000L
// Capture model: the exact-alarm cadence (one discrete fix per `setExactAndAllowWhileIdle`
// wake) is the ALWAYS-ON, Doze-proof floor — it force-wakes the device to take a fix even in
// deep Doze, which a continuous stream cannot survive (the OS batches/suspends streamed
// location updates in Doze no matter how many wake locks we hold — that suspension is what
// caused the multi-minute screen-off gaps). The continuous warm stream is a *screen-on-only*
// add-on: while the screen is on, Doze is not engaged, so the stream gives smooth high-rate
// capture and the alarm idles (each fix cycle is skipped because a stream point already
// landed); when the screen goes off we drop the stream (Doze would suspend it anyway) and
// rely entirely on the alarm cadence. So mode is chosen by *screen state*, not by interval.
//
// Smallest gap between alarm-driven fixes. GPS cold-starts after Doze can take ~15-30s, so
// scheduling fixes faster than this just guarantees misses; the screen-on stream provides any
// finer cadence. 15s keeps screen-off capture within 3× of even a 10s interval.
private const val MIN_ALARM_FLOOR_MS = 15_000L
// The catch-up backstop alarm fires at this multiple of the interval, re-armed on every fix,
// so a dropped primary fix alarm / wedged cycle / hard kill recovers within 3× — not 15 min.
private const val CATCHUP_MULTIPLIER = 3
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
private const val FIX_CATCHUP_REQUEST_CODE = 7014
// After MAX_FAST_RETRIES quick retries at 4 s (GPS cold-starting), retry at this
// medium cadence to give the chip longer to warm up before backing off entirely.
private const val MEDIUM_RETRY_DELAY_MS = 10_000L
private const val MAX_MEDIUM_RETRIES = 3
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
    // Dedicated thread for location callbacks so fixes don't wait on the main thread.
    // Some OEM devices throttle the main thread of background services; a HandlerThread
    // ensures callbacks fire promptly regardless.
    private lateinit var callbackThread: HandlerThread
    private val callbackLooper get() = callbackThread.looper
    private var locationCallback: LocationCallback? = null
    private val filter = LocationFilter()
    private var isPaused = false
    private var initialized = false
    private var lastUploadScheduleAt = System.currentTimeMillis()
    private var syncOnMobileData = true
    /** True while the screen is on (interactive). The continuous warm stream runs only
     *  while this is true; screen-off relies entirely on the alarm cadence. */
    @Volatile private var screenOn = true
    /** True while the continuous FusedLocation stream is currently armed (screen-on). */
    @Volatile private var streaming = false
    @Volatile private var fixInProgress = false
    @Volatile private var fixCycleStartedAt = 0L
    @Volatile private var consecutiveMisses = 0
    @Volatile private var currentConfig: TrackingConfig? = null
    @Volatile private var lastAcceptedLocation: android.location.Location? = null
    @Volatile private var lastAcceptedAtMs: Long = 0L
    private var configJob: Job? = null
    private var watchdogJob: Job? = null
    private var syncPrefJob: Job? = null
    private var providerReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        callbackThread = HandlerThread("RoamlyLocCb").also { it.start() }
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
                isPaused = true; filter.reset(); cancelNextFix()
                stopLocationUpdates(); streaming = false; releaseWakeLock(); updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                currentConfig?.let { applyCapture(it) }
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
                } else if (!isPaused) {
                    // The per-point (or catch-up) exact alarm fired: take a fix and re-arm.
                    // runFixCycle skips the GPS request if the stream already covered this
                    // interval, but always reschedules so the cadence never dies.
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
        } else if (!isPaused) {
            // A repeat start of an already-running service (the 15-min heartbeat, a
            // provider change, etc.). Only nudge the cadence if it looks stalled, so a
            // healthy tracker isn't perturbed into extra fixes.
            val cfg = currentConfig
            val stale = cfg != null && (System.currentTimeMillis() - lastAcceptedAtMs) > alarmIntervalMs(cfg) * 2
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
        screenOn = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        observeRuntimePreferences()
        observeConfig()  // first emit → applyCapture(): starts the cadence (+ stream if screen-on)
        startWatchdog()
        registerProviderChangeReceiver()
        registerScreenReceiver()
        TrackingAlarmReceiver.schedule(applicationContext)
        initialized = true
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
        unregisterScreenReceiver()
        configJob?.cancel()
        watchdogJob?.cancel()
        syncPrefJob?.cancel()
        scope.cancel()
        callbackThread.quitSafely()
        // scope is cancelled, so use runBlocking to make sure the flags actually land.
        runBlocking { prefs.setTrackingActive(false) }
        // Self-resurrection: if we're being destroyed but the user still wants tracking
        // on (i.e. this wasn't an explicit STOP), ask the standalone RestarterReceiver
        // to bring us straight back. A dying service can't reliably restart itself, but
        // a separate receiver can. The pending fix alarm (if any) is left armed on
        // purpose — it's an extra independent restart path; the revived service re-anchors
        // the cadence cleanly via applyCapture().
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
                applyCapture(cfg)
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

    // ── Screen on/off (mode selection) ─────────────────────────────────────────

    /** Track screen state. The continuous warm stream is only worthwhile while the screen
     *  is on (Doze isn't engaged, so streamed updates flow smoothly); screen-off it would
     *  just be suspended by Doze, so we drop it and let the exact-alarm cadence carry on.
     *  Switching is done through [applyCapture]. */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val on = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    Intent.ACTION_SCREEN_OFF -> false
                    else -> return
                }
                if (on == screenOn) return
                screenOn = on
                Log.i(TAG, "Screen ${if (on) "on" else "off"} — re-selecting capture mode")
                if (!isPaused) currentConfig?.let { applyCapture(it) }
            }
        }
        screenReceiver = rcv
        val filterScreen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(rcv, filterScreen) }
            .onFailure { Log.e(TAG, "Failed to register screen receiver", it) }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
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
                applyCapture(cfg)
            }
        }
    }

    /** The cadence the exact-alarm floor actually runs at — never faster than the GPS can
     *  cold-acquire, so a short interval doesn't schedule guaranteed misses. The screen-on
     *  stream provides any finer rate. */
    private fun alarmIntervalMs(cfg: TrackingConfig): Long = maxOf(cfg.intervalMs, MIN_ALARM_FLOOR_MS)

    /** (Re)establish capture. The exact-alarm cadence is the always-on, Doze-proof floor and
     *  is armed unconditionally (it force-wakes the device to take a fix even in deep Doze).
     *  The continuous warm stream + pinned wake lock are layered on *only while the screen is
     *  on* — screen-off Doze suspends the stream regardless, so we drop it and save the
     *  battery, letting the alarm carry the cadence. */
    private fun applyCapture(cfg: TrackingConfig) {
        if (screenOn && !isPaused) {
            startLocationUpdates(cfg)    // smooth warm stream while the screen is on
            acquireWakeLock()            // pin the CPU so stream + watchdog stay alive
            streaming = true
            seedLastLocation()           // immediate first point
        } else {
            stopLocationUpdates()        // screen-off: Doze would suspend the stream anyway
            streaming = false
            releaseWakeLock()            // alarm mode uses a short time-boxed lock per fix
        }
        // Always (re)anchor the Doze-proof alarm cadence.
        cancelNextFix()
        if (!isPaused) runFixCycle()     // take one now; it schedules the next + the catch-up
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
        if (isPaused) return
        val cfg = currentConfig ?: return
        if (fixInProgress) {
            // Normally the in-flight cycle reschedules; but if it wedged (a hung provider
            // callback the timeout somehow didn't unblock) the cadence would die silently.
            // After the acquisition budget elapses, force-reset and take over.
            val wedgedFor = System.currentTimeMillis() - fixCycleStartedAt
            if (wedgedFor <= FIX_ACQUISITION_TIMEOUT_MS + FIX_WAKELOCK_MARGIN_MS + 5_000L) return
            Log.w(TAG, "Fix cycle wedged for ${wedgedFor}ms — forcing reset")
            fixInProgress = false
        }
        // The screen-on stream already delivers a point per interval; if a fresh fix landed
        // within the interval, skip the (cold, battery-costly) GPS request but still re-arm
        // the next alarm so the Doze-proof cadence never stops.
        val now = System.currentTimeMillis()
        if (lastAcceptedAtMs != 0L && now - lastAcceptedAtMs < cfg.intervalMs) {
            scheduleNextFix(alarmIntervalMs(cfg))
            return
        }
        fixInProgress = true
        fixCycleStartedAt = now
        // Time-boxed lock: covers the acquisition window and auto-releases if anything
        // interrupts the cycle, so it can never leak and pin the CPU between fixes. A no-op
        // when the pinned streaming lock is already held.
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
                if (!streaming) releaseWakeLock()  // keep the pinned lock while streaming
                fixInProgress = false
                consecutiveMisses = if (got) 0 else consecutiveMisses + 1
                // Re-arm from the *current* config so an interval change mid-cycle takes
                // effect on the next wake. Three retry tiers:
                //  1. Quick (4 s × 3): covers a brief GPS stall or stale cached fix.
                //  2. Medium (10 s × 3): GPS chip warming up after Doze; BALANCED priority
                //     kicks in here so network location can provide a fallback fix.
                //  3. Normal interval: GPS is persistently unavailable (indoors); keep
                //     trying but stop burning the chip on back-to-back failures.
                if (!isPaused) currentConfig?.let { c ->
                    val floor = alarmIntervalMs(c)
                    val nextMs = when {
                        consecutiveMisses in 1..MAX_FAST_RETRIES ->
                            minOf(floor, FIX_RETRY_DELAY_MS)
                        consecutiveMisses in (MAX_FAST_RETRIES + 1)..(MAX_FAST_RETRIES + MAX_MEDIUM_RETRIES) ->
                            minOf(floor, MEDIUM_RETRY_DELAY_MS)
                        else -> floor
                    }
                    scheduleNextFix(nextMs)
                }
                updateNotification()
            }
        }
    }

    /** Pick the GPS priority for a fix attempt, automatically degrading from HIGH_ACCURACY
     *  to BALANCED after consecutive misses so network location fills indoor gaps. Only
     *  degrades the "auto" setting — explicit user choices ("high"/"balanced"/"low") are
     *  honoured exactly. */
    private fun priorityForCurrentState(cfg: TrackingConfig): Int {
        val base = when (cfg.priority) {
            "high"     -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low"      -> Priority.PRIORITY_LOW_POWER
            else       -> Priority.PRIORITY_HIGH_ACCURACY
        }
        // Degrade after just a couple of misses so network/Wi-Fi location can supply a coarse
        // fix and keep the cadence rather than letting a slow GPS cold-start become a gap.
        return if (cfg.priority == "auto" && consecutiveMisses >= 2)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else
            base
    }

    /** Request a single fresh location using requestLocationUpdates(maxUpdates=1) on a
     *  dedicated HandlerThread so the callback fires immediately regardless of main-thread
     *  load. Uses a fixed generous timeout — GPS can cold-start from Doze in up to ~30s
     *  and shortening the timeout based on the interval causes unnecessary misses. */
    @Suppress("MissingPermission")
    private suspend fun requestSingleFix(cfg: TrackingConfig): android.location.Location? {
        val priority = priorityForCurrentState(cfg)
        val fresh = try {
            withTimeoutOrNull(FIX_ACQUISITION_TIMEOUT_MS) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    val request = LocationRequest.Builder(priority, 0L)
                        .setMaxUpdates(1)
                        .setWaitForAccurateLocation(false)
                        .build()
                    val cb = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            if (cont.isActive) cont.resume(result.lastLocation)
                        }
                    }
                    runCatching {
                        fusedClient.requestLocationUpdates(request, cb, callbackLooper)
                    }.onFailure {
                        Log.e(TAG, "requestLocationUpdates failed", it)
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation {
                        runCatching { fusedClient.removeLocationUpdates(cb) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Single fix request failed", t)
            null
        }
        if (fresh != null) return fresh
        // The fresh request timed out (cold GPS, indoors). Fall back to the fused last-known
        // fix so a recent point still anchors the cadence — the LocationFilter rejects it if
        // it's actually stale or a duplicate, so this can only *help* never *backdate*.
        return runCatching {
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                }
            }
        }.getOrNull()
    }

    /** Schedule the next discrete fix via an exact, Doze-piercing alarm targeting this
     *  service directly (the GPSLogger pattern). `setExactAndAllowWhileIdle` fires even in
     *  deep Doze, and on time when the app is battery-optimization-exempt. Also (re)arms a
     *  catch-up alarm at [CATCHUP_MULTIPLIER]× the interval — a second, independent
     *  Doze-piercing wake that recovers the cadence within 3× if this primary alarm is ever
     *  dropped or the service is killed, instead of waiting for the 15-min heartbeat. */
    private fun scheduleNextFix(intervalMs: Long) {
        scheduleFixAlarm(FIX_ALARM_REQUEST_CODE, intervalMs)
        val cfg = currentConfig
        val catchupMs = (cfg?.let { alarmIntervalMs(it) } ?: intervalMs) * CATCHUP_MULTIPLIER
        scheduleFixAlarm(FIX_CATCHUP_REQUEST_CODE, catchupMs)
    }

    private fun scheduleFixAlarm(requestCode: Int, delayMs: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val pi = fixPendingIntent(requestCode)
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
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.cancel(fixPendingIntent(FIX_ALARM_REQUEST_CODE))
        am.cancel(fixPendingIntent(FIX_CATCHUP_REQUEST_CODE))
    }

    private fun fixPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, LocationTrackingService::class.java).setAction(ACTION_TAKE_FIX)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        else
            PendingIntent.getService(this, requestCode, intent, flags)
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
            fusedClient.requestLocationUpdates(buildRequest(cfg), callback, callbackLooper)
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

    /** Continuous-stream watchdog: re-arm the live stream if fixes stall while it's running.
     *  It only applies while [streaming] (screen-on) — screen-off the exact-alarm cadence
     *  carries everything, and coroutine delay() is frozen in Doze anyway. The interval-scaled
     *  catch-up alarm + the 15-min heartbeat are the deep-Doze liveness checks. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                if (isPaused) continue
                updateNotification()
                if (!streaming) continue
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
            // Detect a wedged uploader and break it with REPLACE; a KEEP enqueue is
            // silently dropped while a prior job sleeps in WorkManager's exponential
            // backoff, which strands captured points for many minutes after signal
            // returns (the "nothing uploaded for ages while driving" gap). The primary
            // signal is time-based — a non-empty backlog plus no *successful* delivery
            // for UPLOAD_STUCK_AGE_MS — so recovery fires in ~2 min at any interval,
            // not after ~20 min of points pile up. The point count stays as a backstop.
            // Gated on the time threshold so REPLACE recurs at most once per cycle and
            // never interrupts a healthy in-flight flush (which clears in well under it).
            val lastSyncAt = prefs.lastSyncTime.first()
            val lastSyncOk = prefs.lastSyncSuccess.first()
            val deliveryStale = unsynced > 0 &&
                (lastSyncAt == 0L || (!lastSyncOk && now - lastSyncAt >= UPLOAD_STUCK_AGE_MS))
            val backloggedStuck = reachedTimeThreshold &&
                (deliveryStale || unsynced >= UPLOAD_STUCK_THRESHOLD)
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

        val body = notificationBody()
        return NotificationCompat.Builder(this, RoamlyApp.CHANNEL_TRACKING)
            .setContentTitle(if (isPaused) "Roamly paused" else "Roamly tracking")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
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
        // Surface the make-or-break setting right in the ongoing notification: without the
        // battery-optimization exemption, Doze produces the multi-minute gaps this whole
        // service is built to avoid.
        val warn = if (!TrackingCoordinator.isIgnoringBatteryOptimizations(this))
            "\n⚠ Battery optimization on — tracking may have gaps" else ""
        if (isPaused) return "Tracking is paused$warn"
        val loc = lastAcceptedLocation ?: return "Waiting for the next fix$warn"
        val ageSec = ((System.currentTimeMillis() - lastAcceptedAtMs) / 1000L).coerceAtLeast(0)
        val accuracy = if (loc.hasAccuracy()) "${loc.accuracy.toInt()}m" else "unknown accuracy"
        val age = when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
        return "Last fix $age · $accuracy$warn"
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
