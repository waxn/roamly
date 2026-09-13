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
import com.roamly.MainActivity
import com.roamly.R
import com.roamly.RoamlyApp
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.TrackingEnabledMirror
import com.roamly.data.prefs.UserPreferences
import com.roamly.receiver.RestarterReceiver
import com.roamly.receiver.TrackingAlarmReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
// Per-cycle "best fix" acquisition. Rather than discard an inaccurate fix and gap, each
// cycle keeps polling for a *better* one — but only within a budget so it never runs into
// the next scheduled fix. It stops early as soon as the fix meets the user's accuracy
// target, or once accuracy has plateaued (the hardware can't do better here right now), or
// when the budget elapses; then it logs the best fix it got (never nothing, so no gap).
private const val ACQUIRE_BUDGET_MAX_MS = 10_000L
private const val ACQUIRE_STALL_MS = 3_500L
// Floor for the gap to the next scheduled fix, so a cycle that used its whole budget doesn't
// busy-loop straight into the next one.
private const val MIN_NEXT_FIX_MS = 1_000L
// When the provider gives no *fresh* fix this cycle (it stationary-throttles screen-off and
// keeps handing back the same cached location), we re-log the last known position stamped
// with the current time — a "dwell point" — so a stationary device still records one point
// per interval (the dwell feature) instead of gapping. Only do this while the last real fix
// is recent enough to still represent where the device is.
private const val DWELL_MAX_AGE_MS = 10 * 60_000L
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

// GPS occasionally reports a wildly inflated instantaneous speed (e.g. 20 mph while
// walking) even though the fix lands where you actually are. When the reported speed
// dwarfs the speed implied by how far we moved from the previous fix, it's a sensor
// glitch — substitute the displacement-derived speed. The delta floor keeps us from
// "correcting" tiny low-speed wobble where the ratio is noisy.
private const val SPEED_SPIKE_FACTOR = 2.5f
private const val SPEED_SPIKE_MIN_DELTA_MPS = 3.0f  // ≈ 6.7 mph

// Activity recording. A deliberately recorded ride/walk captures far harder than
// the background life-log: ~2s fixes with the stream held through screen-off,
// which is the only way a pocketed phone gets more than one fix per
// MIN_ALARM_FLOOR_MS. Paid for by the user explicitly pressing Start, and bounded
// by ACTIVITY_MAX_MS so a forgotten recording cannot flatten the battery overnight.
private const val ACTIVITY_INTERVAL_MS = 2_000L
private const val ACTIVITY_MAX_MS = 12 * 60 * 60_000L

// Adaptive interval (Settings, forced on by Simple Mode): swap between these
// two fixed cadences by recent Doppler speed instead of one flat interval —
// "1-5 minutes, faster when moving". Fixed rather than user-tunable for v1,
// to avoid a second knob interacting with the ordinary trackingIntervalSecs
// setting. ADAPTIVE_MOVE_THRESHOLD_MPS reuses DriftAnchor.MOVE_SPEED_MPS's
// own "is this Doppler reading real movement" bar rather than inventing a
// third speed constant.
private const val ADAPTIVE_MOVING_INTERVAL_MS = 60_000L
private const val ADAPTIVE_STATIONARY_INTERVAL_MS = 300_000L
// MOVE_SPEED_MPS is a top-level constant in DriftAnchor.kt, same package.
private const val ADAPTIVE_MOVE_THRESHOLD_MPS = MOVE_SPEED_MPS
// updateNotification() runs once per saved point. At 2s that is 15x more often
// than the shortest interval the app otherwise allows, so it gets a floor —
// which is a no-op at every one of those intervals.
private const val NOTIFY_MIN_INTERVAL_MS = 2_000L
// Same reasoning for the per-point battery binder call.
private const val BATTERY_READ_MAX_AGE_MS = 30_000L

const val ACTION_STOP     = "com.roamly.STOP"
const val ACTION_PAUSE    = "com.roamly.PAUSE"
const val ACTION_RESUME   = "com.roamly.RESUME"
const val ACTION_TAKE_FIX = "com.roamly.TAKE_FIX"

/** Snapshot of the user's tracking knobs. Changes restart the location request live. */
private data class TrackingConfig(
    val intervalMs: Long,
    val priority: String,
    val maxAccuracyM: Float,
    val suppressDrift: Boolean,
    /** The activity being recorded, or null for ordinary background tracking. */
    val activity: ActivitySession? = null,
    /** Swap the alarm cadence between ADAPTIVE_MOVING/STATIONARY_INTERVAL_MS by
     *  recent Doppler speed instead of using [intervalMs] flat. Read at the point
     *  the cadence is actually computed (alarmIntervalMs), since the input — a
     *  live speed reading — isn't known at config-build time the way every other
     *  field here is. Always false while recording; a live ride's fixed 2s
     *  cadence is a stronger, more deliberate override. */
    val adaptiveInterval: Boolean = false,
) {
    val recording: Boolean get() = activity != null
}

@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var db: TrackingDatabase

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Where fixes come from: Play Services' fused provider when it is genuinely available,
     *  the platform LocationManager otherwise (GrapheneOS and other de-Googled builds, where
     *  the fused client exists but silently answers nothing). See [LocationSource]. */
    private lateinit var locationSource: LocationSource
    // Dedicated thread for location callbacks so fixes don't wait on the main thread.
    // Some OEM devices throttle the main thread of background services; a HandlerThread
    // ensures callbacks fire promptly regardless.
    private lateinit var callbackThread: HandlerThread
    private val callbackLooper get() = callbackThread.looper
    private var locationStream: FixStream? = null
    private val filter = LocationFilter()
    private val driftAnchor = DriftAnchor()
    private var isPaused = false
    private var initialized = false
    private var lastUploadScheduleAt = System.currentTimeMillis()
    private var syncOnMobileData = true
    /** True while the screen is on (interactive). The continuous warm stream runs only
     *  while this is true; screen-off relies entirely on the alarm cadence. */
    @Volatile private var screenOn = true
    /** True while the continuous location stream is currently armed (screen-on). */
    @Volatile private var streaming = false
    @Volatile private var fixInProgress = false
    @Volatile private var fixCycleStartedAt = 0L
    @Volatile private var consecutiveMisses = 0
    @Volatile private var currentConfig: TrackingConfig? = null
    @Volatile private var lastAcceptedLocation: android.location.Location? = null
    @Volatile private var lastAcceptedAtMs: Long = 0L
    /** When a genuine fix last landed. Unlike [lastAcceptedAtMs] this is never advanced by a
     *  dwell point, so the dwell window in `saveOrDwell` can actually expire. */
    @Volatile private var lastRealFixAtMs: Long = 0L
    /** The cleaned (jitter-dropped, spike-corrected) speed of the last real fix —
     *  what adaptive-interval mode reads to pick the next cadence. Never updated
     *  from a dwell point, whose synthetic speed is always 0 regardless of
     *  whether the device is actually moving. */
    @Volatile private var lastFixSpeedMps: Float = 0f
    /** Cached battery level + notification throttle. Both exist only because a
     *  recording saves a point every ~2s, 15x more often than the shortest
     *  interval the app otherwise allows; both are no-ops at those intervals. */
    @Volatile private var lastBatteryPct: Int? = null
    @Volatile private var lastBatteryAtMs: Long = 0L
    @Volatile private var lastNotifyAtMs: Long = 0L
    private var configJob: Job? = null
    private var watchdogJob: Job? = null
    private var syncPrefJob: Job? = null
    private var providerReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Serialises the accept -> commit -> enqueue sequence in [savePoint].
     *
     * [LocationFilter.accept] is pure and [LocationFilter.commit] is what advances
     * the de-dup window, so between a caller's accept() and savePoint()'s commit()
     * another thread could slip a second fix through: the stream callback runs on
     * the RoamlyLocCb HandlerThread while a fix cycle runs on IO, and both reach
     * here. Holding this across the re-check makes the pair atomic.
     */
    private val captureLock = Any()

    /** One point on its way to disk. See [pointWrites]. */
    private data class PendingWrite(val point: CachedPoint, val recording: Boolean)

    /**
     * Persisted points, drained by a single consumer off the capture path.
     *
     * The Room insert, the CSV append (which mkdirs + opens + closes under a global
     * lock) and the upload check (a COUNT plus two DataStore reads) all used to run
     * inline in [savePoint], *before* it advanced the filter and the cadence stamps.
     * So anything that slowed the database — for most of this app's life, the map
     * sharing one SQLite file with the capture queue — did not merely delay a point:
     * fixes arriving meanwhile were measured against a stale de-dup window and
     * rejected outright, and the resulting missed cycles degraded the fix priority.
     * Unbounded on purpose: dropping a captured fix is worse than the memory, and
     * the depth is ~1 at any interval the app allows.
     */
    private val pointWrites = Channel<PendingWrite>(Channel.UNLIMITED)
    private var writerJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        locationSource = LocationSource.get(this)
        callbackThread = HandlerThread("RoamlyLocCb").also { it.start() }
        CaptureStats.init(this)
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
                isPaused = true; filter.reset(); driftAnchor.reset(); cancelNextFix()
                stopLocationUpdates(); streaming = false; releaseWakeLock()
                updateNotification(force = true)
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                currentConfig?.let { applyCapture(it) }
                updateNotification()
                return START_STICKY
            }
            ACTION_TAKE_FIX -> {
                // The per-point exact alarm fired. This runs on the MAIN thread, so
                // everything here is either cheap or dispatched. It used to do a
                // blocking DataStore read per fix, which coupled the capture cadence
                // to whatever the UI was doing in this same process.
                if (!initialized) {
                    // The OS killed us and the alarm is recreating us cold — full init
                    // re-establishes config/observers and kicks the first fix itself.
                    initTracking()
                } else if (!isPaused) {
                    // runFixCycle skips the GPS request if the stream already covered this
                    // interval, but always reschedules so the cadence never dies. Off the
                    // main thread: its prologue makes several AlarmManager binder calls.
                    scope.launch { runFixCycle() }
                }
                // The mirror is authoritative only for *continuing*. To actually stop we
                // confirm against DataStore off-thread, so a stale or absent mirror can
                // never silently kill tracking.
                if (!TrackingEnabledMirror.get(this)) {
                    scope.launch {
                        if (!prefs.trackingEnabled.first()) {
                            cancelNextFix()
                            stopSelf()
                        }
                    }
                }
                // goForeground() already ran at the top of onStartCommand; calling it
                // again here rebuilt the whole notification on every single fix.
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
            if (lastAcceptedAtMs == 0L || stale) scope.launch { runFixCycle() }
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
        startPointWriter()
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
        // Flush anything still queued for disk before the scope (and with it the
        // writer) goes away — otherwise a teardown mid-interval silently drops the
        // most recent fix or two. Bounded so it can never hold up onDestroy.
        runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    pointWrites.close()
                    for (w in pointWrites) { runCatching { persistPoint(w) } }
                }
            }
        }
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
     *  user toggles GPS, or it flips on entering/leaving a tunnel). Both sources cover several
     *  providers at once — fused internally, the platform one by arming them side by side —
     *  so this just makes capture recover immediately instead of waiting out the next
     *  interval. It is also what re-arms a platform source that had to fall back to a
     *  switched-off GPS because nothing else was enabled. */
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
            // Folded into one Pair-valued flow to keep the outer combine below at
            // 5-arity — kotlinx.coroutines has no typed 6-arg combine, and its
            // vararg form requires every flow to share one element type, which
            // these six prefs don't. Same trick the original activitySession-only
            // version of this comment already used, just widened to a pair.
            val activityAndAdaptive = combine(
                prefs.activitySession, prefs.adaptiveIntervalEnabled,
            ) { activity, adaptiveInterval -> activity to adaptiveInterval }

            combine(
                prefs.trackingIntervalSecs,
                prefs.locationPriority,
                prefs.maxAccuracyM,
                prefs.suppressStationaryDrift,
                activityAndAdaptive,
            ) { interval, priority, maxAcc, suppressDrift, (activity, adaptiveInterval) ->
                // A live session replaces the user's interval with the recording
                // constant rather than overwriting their preference — which is why
                // stopping restores nothing: clearing the session *is* the restore.
                val act = activity?.takeIf {
                    System.currentTimeMillis() - it.startedAtMs < ACTIVITY_MAX_MS
                }
                TrackingConfig(
                    intervalMs = if (act != null) ACTIVITY_INTERVAL_MS
                                 else interval.coerceIn(5, 120) * 1000L,
                    priority = priority,
                    maxAccuracyM = maxAcc.coerceAtLeast(1).toFloat(),
                    suppressDrift = suppressDrift,
                    activity = act,
                    // Recording always wins — a live ride's fixed 2s cadence is a
                    // stronger, more deliberate override than ambient adaptive mode.
                    adaptiveInterval = adaptiveInterval && act == null,
                )
            }.distinctUntilChanged().collect { cfg ->
                // The interval can change 15x when a recording starts or stops, so a
                // dedup window and a drift anchor built under the old one must not
                // survive into the new one.
                if (cfg.activity?.id != currentConfig?.activity?.id) {
                    filter.reset()
                    driftAnchor.reset()
                }
                currentConfig = cfg
                filter.minTimeBetweenMs = cfg.intervalMs
                // DriftAnchor's thresholds are fix *counts*, not durations: at 2s an
                // anchor would form after ~6s of near-stillness — a red light, a
                // track-stand, unclipping — and thereafter rewrite position and force
                // speed to 0. Wrong for a ride, right for a parked phone.
                driftAnchor.enabled = cfg.suppressDrift && !cfg.recording
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
    /** The interval that currently governs capture cadence, before the alarm floor
     *  below is applied. Ordinarily [TrackingConfig.intervalMs]; under adaptive-
     *  interval mode, swapped for the moving/stationary constant by the last real
     *  fix's speed instead — the one input that can't be known at config-build
     *  time the way every other TrackingConfig field is. */
    private fun effectiveIntervalMs(cfg: TrackingConfig): Long =
        if (cfg.adaptiveInterval) {
            if (lastFixSpeedMps >= ADAPTIVE_MOVE_THRESHOLD_MPS) ADAPTIVE_MOVING_INTERVAL_MS
            else ADAPTIVE_STATIONARY_INTERVAL_MS
        } else cfg.intervalMs

    private fun alarmIntervalMs(cfg: TrackingConfig): Long = maxOf(effectiveIntervalMs(cfg), MIN_ALARM_FLOOR_MS)

    /** (Re)establish capture with two layers that cover each other:
     *   - The **continuous stream + pinned wake lock** is the *primary* source. It delivers a
     *     smooth per-interval cadence whenever the device is NOT in deep Doze — screen-on,
     *     charging, or moving. (Some devices, e.g. GrapheneOS/microG, throttle even exact
     *     `allow-while-idle` alarms to ~once/minute, so the stream is what gives a real 15s
     *     cadence when the device is awake enough to allow it.)
     *   - The **exact-alarm cadence** is the *backup* that carries through deep Doze, where the
     *     OS suspends the stream and ignores the wake lock. Its per-cycle `runFixCycle` skips
     *     cheaply while the stream is keeping the interval fresh, and takes over (best-fix /
     *     dwell) the moment the stream goes quiet (Doze).
     *  Keeping the stream up screen-off is self-limiting on battery: deep Doze suspends it and
     *  ignores the wake lock, so the heavy path only runs when the device is already awake. */
    private fun applyCapture(cfg: TrackingConfig) {
        if (!isPaused) {
            startLocationUpdates(cfg)    // primary: smooth warm stream whenever not deep-Doze
            // `streaming` means "the warm stream is genuinely carrying the cadence", which is
            // only true screen-on — the screen receiver re-runs applyCapture on every toggle.
            // Hard-coding it true made the screen-off no-skip guard in runFixCycle unreachable
            // (so alarm cycles skipped the GPS request in Doze, the one place they must not),
            // never released the pinned wake lock, and left the watchdog re-arming a stream
            // the OS had already suspended.
            // Recording keeps the stream armed screen-off, which is the whole
            // point: MIN_ALARM_FLOOR_MS caps the alarm path at one fix per 15s, so a
            // pocketed phone would otherwise draw a polygon instead of a ride.
            streaming = screenOn || cfg.recording
            if (streaming) acquireWakeLock()  // pin the CPU so stream + watchdog stay alive
            else releaseWakeLock()
            seedLastLocation()           // immediate first point
        } else {
            stopLocationUpdates()
            streaming = false
            releaseWakeLock()
        }
        updateNotification(force = true)
        // Always (re)anchor the Doze-proof alarm cadence.
        cancelNextFix()
        // Dispatched: applyCapture is reached from the screen-on/off and
        // PROVIDERS_CHANGED receivers, whose onReceive runs on the main thread, and
        // runFixCycle's prologue makes several AlarmManager binder calls.
        if (!isPaused) scope.launch { runFixCycle() }  // take one now; it schedules the next + catch-up
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
        // Only while the screen-on continuous stream is running does a recent point mean the
        // interval is already covered — then skip the redundant (cold, battery-costly) GPS
        // request but still re-arm the alarm. Screen-off there is no stream, so never skip:
        // skipping there would halve the cadence (the acquisition itself takes a few seconds,
        // so the save lands mid-interval and the next on-time alarm would wrongly skip it).
        val now = System.currentTimeMillis()
        // While recording, freshness is judged against the *alarm* cadence, not the
        // 2s capture interval — otherwise this skip could never fire and every alarm
        // would launch a full acquireBestFix burst on top of a perfectly healthy
        // stream, which is the single worst thing this mode could do to the battery.
        // Still evidence-based: if the stream really is suspended, lastAcceptedAtMs
        // goes stale within the window and the alarm takes over regardless.
        val freshWindow = if (cfg.recording) alarmIntervalMs(cfg) else effectiveIntervalMs(cfg)
        if (streaming && lastAcceptedAtMs != 0L && now - lastAcceptedAtMs < freshWindow) {
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
                val loc = acquireBestFix(cfg)
                val accepted = loc?.takeIf { !isPaused && filter.accept(it) }
                if (!isPaused) {
                    got = saveOrDwell(accepted, cfg.maxAccuracyM)
                    if (!got) {
                        Log.d(TAG, "Fix cycle produced no usable point (null/filtered/imprecise)")
                        CaptureStats.bump(CaptureStats.Counter.CYCLE_MISS)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Fix cycle failed", t)
            } finally {
                if (!streaming) releaseWakeLock()  // keep the pinned lock while streaming
                fixInProgress = false
                consecutiveMisses = if (got) 0 else consecutiveMisses + 1
                // Re-arm from the *current* config so an interval change mid-cycle takes
                // effect on the next wake.
                if (!isPaused) currentConfig?.let { c ->
                    val floor = alarmIntervalMs(c)
                    val nextMs = if (got) {
                        // Got a point: keep the cadence ~interval measured from the cycle
                        // *start* — so the time already spent acquiring the best fix counts
                        // toward the interval and points land every ~interval, not interval+budget.
                        val elapsed = System.currentTimeMillis() - fixCycleStartedAt
                        maxOf(floor - elapsed, MIN_NEXT_FIX_MS)
                    } else when {
                        // No fix at all (cold GPS / no signal): retry sooner through the tiers.
                        //  1. Quick (4 s × 3): a brief GPS stall.
                        //  2. Medium (10 s × 3): chip warming up after Doze; BALANCED kicks in.
                        //  3. Floor: persistently unavailable; stop hammering the chip.
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

    /** Pick the accuracy for a fix attempt, automatically degrading from HIGH to BALANCED
     *  after consecutive misses so network location fills indoor gaps. Only degrades the
     *  "auto" setting — explicit user choices ("high"/"balanced"/"low") are honoured exactly. */
    private fun accuracyForCurrentState(cfg: TrackingConfig): FixAccuracy {
        // Never degrade during a recording: BALANCED frequently reports no Doppler,
        // and live speed, max speed and the track's shape all depend on having it.
        if (cfg.recording) return FixAccuracy.HIGH
        // Degrade after just a couple of misses so network/Wi-Fi location can supply a coarse
        // fix and keep the cadence rather than letting a slow GPS cold-start become a gap.
        return if (cfg.priority == "auto" && consecutiveMisses >= 2) FixAccuracy.BALANCED
        else accuracyFor(cfg.priority)
    }

    /** The user's GPS-priority setting as a source-neutral accuracy, so the same choice drives
     *  Play Services' `Priority` and the platform provider selection. */
    private fun accuracyFor(priority: String): FixAccuracy = when (priority) {
        "high"     -> FixAccuracy.HIGH
        "balanced" -> FixAccuracy.BALANCED
        "low"      -> FixAccuracy.LOW
        else       -> FixAccuracy.HIGH
    }

    /** Acquire the **best fix available this cycle** instead of taking the first one and
     *  discarding it if inaccurate. Streams fixes on the dedicated HandlerThread and keeps the
     *  most accurate one, stopping early when any of:
     *   - a fix meets the user's accuracy *target* (`cfg.maxAccuracyM`) — no need to do better;
     *   - accuracy has plateaued (no improvement for `ACQUIRE_STALL_MS` and we already have a
     *     fix) — the hardware can't do better here right now, so stop burning GPS;
     *   - the `budget` elapses (kept below the interval so we never run into the next fix).
     *  Returns the best fix collected (so a stationary indoor cycle logs its ~17m fix rather
     *  than gapping), or the last-known fix if literally nothing arrived. */
    @Suppress("MissingPermission")
    private suspend fun acquireBestFix(cfg: TrackingConfig): android.location.Location? {
        val accuracy = accuracyForCurrentState(cfg)
        val target = cfg.maxAccuracyM
        val budget = minOf(alarmIntervalMs(cfg), ACQUIRE_BUDGET_MAX_MS)
        val best = java.util.concurrent.atomic.AtomicReference<android.location.Location?>(null)
        val lastImproveAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
        var stream: FixStream? = null
        try {
            withTimeoutOrNull(budget) {
                suspendCancellableCoroutine<Unit> { cont ->
                    // Zeros throughout: take every fix the chip can emit, unbatched. This burst
                    // wants the *best* fix of the cycle, not one per interval.
                    val request = FixRequest(
                        intervalMs = 0L,
                        minIntervalMs = 0L,
                        maxDelayMs = 0L,
                        accuracy = accuracy,
                    )
                    val armed = locationSource.requestUpdates(request, callbackLooper) { loc ->
                        val cur = best.get()
                        val better = cur == null ||
                            (loc.hasAccuracy() && (!cur.hasAccuracy() || loc.accuracy < cur.accuracy))
                        if (better) {
                            best.set(loc)
                            lastImproveAt.set(SystemClock.elapsedRealtime())
                        }
                        // Good enough — stop now.
                        if (loc.hasAccuracy() && loc.accuracy <= target) {
                            if (cont.isActive) cont.resume(Unit)
                        // Plateaued — the hardware isn't improving, don't keep the GPS on.
                        } else if (best.get() != null &&
                            SystemClock.elapsedRealtime() - lastImproveAt.get() >= ACQUIRE_STALL_MS
                        ) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    // Assigned before the block returns, and suspendCancellableCoroutine always
                    // runs its block to completion before the coroutine proceeds — so `finally`
                    // below can never miss an armed stream, even if a fix resumes us mid-block.
                    stream = armed
                    if (armed == null) {
                        Log.e(TAG, "Could not arm ${locationSource.label} for a fix")
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation { /* stream stopped in finally */ }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Best-fix acquisition failed", t)
        } finally {
            stream?.cancel()
        }
        val got = best.get()
        if (got != null) return got
        // Nothing arrived in the budget (cold GPS). Fall back to the last-known fix so a
        // recent point still anchors the cadence — the LocationFilter rejects it if it's
        // actually stale or a duplicate, so this can only *help* never *backdate*.
        return runCatching {
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    locationSource.lastKnown { if (cont.isActive) cont.resume(it) }
                }
            }
        }.getOrNull()
    }

    /** Build a dwell point: the last known position re-stamped with the current time and zero
     *  speed, so a stationary device keeps logging one point per interval even when the
     *  provider won't produce a fresh fix. Passes the LocationFilter (newer timestamp, zero
     *  displacement, so no teleport/dup). */
    private fun dwellPointFrom(last: android.location.Location): android.location.Location =
        android.location.Location(last.provider ?: "dwell").apply {
            latitude = last.latitude
            longitude = last.longitude
            if (last.hasAccuracy()) accuracy = last.accuracy
            if (last.hasAltitude()) altitude = last.altitude
            speed = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /** Accuracy gate + dwell fallback shared by every fresh-fix capture path. [loc] must
     *  already have passed [LocationFilter.accept] (or be null, meaning none arrived /
     *  was rejected this round). A fix worse than [maxAccuracyM] is discarded — matching
     *  "Discard fixes worse than" in Settings — but substituted with a dwell point (last
     *  known position, re-stamped now) when one is recent enough, so discarding a bad fix
     *  doesn't open a gap whenever a fallback is available. Returns whether a point was
     *  saved. */
    private fun saveOrDwell(loc: android.location.Location?, maxAccuracyM: Float?): Boolean {
        if (loc != null && (maxAccuracyM == null || !loc.hasAccuracy() || loc.accuracy <= maxAccuracyM)) {
            // savePoint can now decline: it re-checks the filter under captureLock, so a
            // fix another thread beat us to is reported as not-saved rather than counted
            // as a success it never was.
            return savePoint(loc)
        }
        if (loc != null) {
            Log.d(TAG, "Discarded fix acc=${loc.accuracy}m > ${maxAccuracyM}m target")
            CaptureStats.bump(CaptureStats.Counter.ACCURACY_DISCARD)
        }
        // Measured from the last *real* fix, not the last saved point: a dwell save used to
        // refresh the clock it is checked against, so the 10-minute ceiling never expired and
        // a long outage produced an endless fabricated stationary track instead of an honest gap.
        val last = lastAcceptedLocation
        if (last != null && System.currentTimeMillis() - lastRealFixAtMs < DWELL_MAX_AGE_MS) {
            val dwell = dwellPointFrom(last)
            if (filter.accept(dwell) && savePoint(dwell, isDwell = true)) {
                Log.d(TAG, "Logged dwell point (no usable fresh fix this cycle)")
                return true
            }
        }
        return false
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
        // Without exact alarms the OS throttles allow-while-idle wakes to roughly one per
        // 9–15 min in Doze — a large, recurring gap with no other symptom. Record it so
        // Diagnostics and the notification can name the cause instead of it being invisible.
        CaptureStats.exactAlarmsDenied = !canExact
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            CaptureStats.exactAlarmsDenied = true
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
        locationStream = locationSource.requestUpdates(buildRequest(cfg), callbackLooper) { loc ->
            if (!isPaused && filter.accept(loc)) {
                scope.launch { saveOrDwell(loc, cfg.maxAccuracyM) }
            }
        }
        if (locationStream == null) {
            Log.e(TAG, "Could not arm the ${locationSource.label} stream — relying on the alarm cadence")
        }
    }

    private fun stopLocationUpdates() {
        locationStream?.cancel()
        locationStream = null
    }

    /** Grab the last known fix immediately so the first point doesn't wait a full interval. */
    @Suppress("MissingPermission")
    private fun seedLastLocation() {
        locationSource.lastKnown { loc ->
            if (loc != null && !isPaused && filter.accept(loc)) {
                val maxAcc = currentConfig?.maxAccuracyM
                scope.launch { saveOrDwell(loc, maxAcc) }
            }
        }
    }

    private fun buildRequest(cfg: TrackingConfig): FixRequest {
        val intervalMs = cfg.intervalMs
        // One fix per interval, on time, regardless of movement:
        //  - minUpdateInterval == interval: the provider's default floor, so it won't
        //    deliver (and burn battery on) extra sub-interval fixes.
        //  - maxUpdateDelay == interval: no batching, deliver each fix as produced.
        //  - minUpdateDistance 0 (applied by the source): never gate delivery on
        //    displacement — stationary still logs every interval (those stacked dots are
        //    the dwell feature).
        //  - don't wait for an "accurate" fix; take what comes.
        return FixRequest(
            intervalMs = intervalMs,
            minIntervalMs = intervalMs,
            maxDelayMs = intervalMs,
            // Recording pins HIGH for the same reason accuracyForCurrentState does.
            accuracy = if (cfg.recording) FixAccuracy.HIGH else accuracyFor(cfg.priority),
        )
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
                // Safety valve for a recording the user forgot to stop. It has to live
                // here, not only in the config transform: that transform re-runs only
                // when one of the prefs emits, so a ride left running overnight would
                // never re-evaluate its own age — and this mode holds a wake lock and a
                // 2s GPS stream, which is the heaviest thing the app can do.
                currentConfig?.activity?.let { act ->
                    if (System.currentTimeMillis() - act.startedAtMs >= ACTIVITY_MAX_MS) {
                        Log.w(TAG, "Recording exceeded ${ACTIVITY_MAX_MS}ms — auto-stopping")
                        runCatching { ActivityCoordinator.stop(applicationContext, prefs) }
                    }
                }
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

    /**
     * The single consumer of [pointWrites]: everything that touches disk or the
     * network scheduler, moved off the capture path. One consumer keeps insertion
     * order, so PointDao's monotonic-id cursor (used by the recording screen) is
     * unaffected.
     */
    private fun startPointWriter() {
        if (writerJob != null) return
        writerJob = scope.launch {
            for (w in pointWrites) {
                runCatching { persistPoint(w) }
                    .onFailure { Log.e(TAG, "Point write failed", it) }
            }
        }
    }

    private suspend fun persistPoint(w: PendingWrite) {
        db.pointDao().insert(w.point)
        // Skipped while recording: it mkdirs + opens + closes under a global lock per
        // point, which is thousands of file operations across a ride for a Diagnostics
        // debugging aid.
        if (!w.recording) {
            runCatching { CsvPointLogger.appendPoint(applicationContext, w.point) }
                .onFailure { Log.e(TAG, "Failed to append point CSV", it) }
        }
        maybeScheduleUpload(w.recording)
    }

    /** Upload scheduling, formerly the tail of [savePoint]. */
    private suspend fun maybeScheduleUpload(recording: Boolean) {
        val now = System.currentTimeMillis()
        val reachedTimeThreshold = now - lastUploadScheduleAt >= UPLOAD_SCHEDULE_MIN_INTERVAL_MS
        // While recording, uploads go purely on the clock. The count trigger would fire
        // every ~20s at a 2s interval; on the clock it is one batch a minute of ~30
        // points, comfortably inside the 500-point cap. Checking the threshold first
        // also skips the per-point COUNT query below.
        if (recording && !reachedTimeThreshold) return
        val unsynced = db.pointDao().unsyncedCount()
        val shouldSchedule = reachedTimeThreshold || unsynced >= UPLOAD_BATCH_TRIGGER_COUNT
        if (!shouldSchedule) return
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

    /**
     * Persist one point. [isDwell] marks a synthetic re-stamp of the last known position
     * rather than a real fix — it must not refresh [lastRealFixAtMs] (or the dwell window
     * below could never expire and an outage would fabricate a parked track forever), and it
     * skips the drift anchor entirely: it already sits at the last position, and its
     * synthetic `speed = 0f` would otherwise let an outage *while moving* plant a false
     * stationary anchor.
     */
    private fun savePoint(rawLoc: android.location.Location, isDwell: Boolean = false): Boolean {
        val recording = currentConfig?.recording == true

        // Battery is a binder call, so it stays outside captureLock. Throttled to
        // BATTERY_READ_MAX_AGE_MS, so this is a no-op on almost every point.
        val nowMs = System.currentTimeMillis()
        if (lastBatteryAtMs == 0L || nowMs - lastBatteryAtMs >= BATTERY_READ_MAX_AGE_MS) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            lastBatteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it >= 0 }
            lastBatteryAtMs = nowMs
        }
        val battery = lastBatteryPct

        synchronized(captureLock) {
            // Re-check under the lock. The caller's accept() happened outside it, so a
            // concurrent save may have committed in between; without this the de-dup
            // window could be bypassed and two points land for one instant.
            if (!filter.accept(rawLoc)) {
                CaptureStats.bump(CaptureStats.Counter.RACE_DROP)
                return false
            }
            // Suppress stationary GPS drift: while parked, snap the wandering fix back onto a
            // stable anchor (speed 0). Pass-through when disabled or genuinely moving.
            val loc = if (driftAnchor.enabled && !isDwell) driftAnchor.resolve(rawLoc) else rawLoc

            // Speed implied by how far we actually moved since the last accepted fix —
            // a sanity check against GPS speed glitches. lastAcceptedLocation is still
            // the *previous* fix here (it's advanced to `loc` after this point is built).
            val movedSpeed: Float? = lastAcceptedLocation?.let { prev ->
                val dtSec = (loc.time - prev.time) / 1000.0
                if (dtSec > 0) (loc.distanceTo(prev) / dtSec).toFloat() else null
            }
            val point = CachedPoint(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                accuracy  = if (loc.hasAccuracy()) loc.accuracy else null,
                altitude  = if (loc.hasAltitude()) loc.altitude else null,
                speed     = when {
                    !loc.hasSpeed()                    -> null
                    loc.speed < MIN_LOGGED_SPEED_MPS   -> 0f   // stationary: drop GPS jitter speed
                    // Reported speed dwarfs what our displacement supports → sensor
                    // glitch (e.g. "walking but 20 mph"); use the displacement speed.
                    // Skipped while recording: this pair was tuned for 30s deltas, and at
                    // 2s the displacement-derived movedSpeed is noise-dominated — position
                    // smoothing can make it ~0 for a genuinely moving rider, at which point
                    // a correct 8 m/s Doppler reading satisfies both clauses and gets
                    // overwritten with nonsense.
                    !recording && movedSpeed != null &&
                        loc.speed > movedSpeed * SPEED_SPIKE_FACTOR &&
                        loc.speed - movedSpeed > SPEED_SPIKE_MIN_DELTA_MPS -> movedSpeed
                    else                               -> loc.speed
                },
                battery   = battery,
                timestamp = loc.time,
                provider  = loc.provider,
            )
            // Advance the filter and the cadence stamps FIRST, then hand the row to the
            // writer. Committing after the insert meant a slow database widened the
            // window in which arriving fixes were judged against stale state and thrown
            // away. Commit still happens only for points we keep: there is no early
            // return between here and the trySend below, so every commit is matched by
            // exactly one enqueued write.
            filter.commit(loc)
            CaptureStats.bump(if (isDwell) CaptureStats.Counter.DWELL else CaptureStats.Counter.SAVED)
            lastAcceptedLocation = loc
            lastAcceptedAtMs = System.currentTimeMillis()
            if (!isDwell) {
                lastRealFixAtMs = lastAcceptedAtMs
                // point.speed is already cleaned (jitter-dropped, spike-corrected)
                // above — exactly the value adaptive-interval mode should trust,
                // not the raw Doppler reading.
                lastFixSpeedMps = point.speed ?: 0f
            }
            pointWrites.trySend(PendingWrite(point, recording))
            Log.d(TAG, "Saved ${loc.latitude},${loc.longitude} acc=${loc.accuracy}m")
        }
        updateNotification()
        return true
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
        val batteryWarn = if (!TrackingCoordinator.isIgnoringBatteryOptimizations(this))
            "\n⚠ Battery optimization on — tracking may have gaps" else ""
        // The other silent gap source: without exact alarms the Doze cadence collapses to
        // one wake every 9–15 minutes, and nothing else about the app looks wrong.
        val alarmWarn = if (CaptureStats.exactAlarmsDenied)
            "\n⚠ Exact alarms denied — Doze cadence limited to ~15 min" else ""
        val warn = batteryWarn + alarmWarn
        if (isPaused) return "Tracking is paused$warn"
        currentConfig?.activity?.let { act ->
            val mins = ((System.currentTimeMillis() - act.startedAtMs) / 60_000L)
                .coerceAtLeast(0)
            return "Recording ${act.kind} · ${mins}m$warn"
        }
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

    /** [force] bypasses the throttle for a state change the user must see at once
     *  (pause, resume, a recording starting or stopping). */
    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAtMs < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyAtMs = now
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
