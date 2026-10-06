package com.roamly.tracking

import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.roamly.data.prefs.ActivitySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ActivityRecorder"

/**
 * Records one activity: a 1 Hz GNSS stream into a private buffer, nothing else.
 *
 * This replaced recording as a *mode of* the all-day capture loop, which is what
 * made rides look bad and cost so much battery. That loop exists to survive Doze
 * on a phone left in a drawer — exact alarms, best-fix bursts, dwell points, a
 * de-dup window, a pinned wake lock — and every one of those either hurts a 1 Hz
 * track or burns power a ride doesn't need. While a session is live,
 * [LocationTrackingService] switches all of that off and hands capture to this.
 *
 * What makes it cheap is what Strava does:
 *  - **The GNSS chip runs continuously.** Keeping it on is cheap; waking the CPU
 *    for every fix is not. So screen-off the request carries a long
 *    `maxUpdateDelay` ([BATCH_SCREEN_OFF_MS]) and the chip *batches* fixes while
 *    the CPU sleeps, then delivers them together. Screen-on it delivers every
 *    second for the live screen.
 *  - **No wake lock.** A foreground service with a batched location request needs
 *    none; the old mode pinned one for the whole ride.
 *  - **Batched writes.** Fixes buffer in memory and land in Room in groups, not
 *    one insert per fix.
 *  - **No network until Stop.** The whole track uploads once, at the end
 *    ([ActivitySaveWorker]), instead of a radio wake every minute.
 *
 * Every accepted fix is stored raw. Smoothing happens on the server (forward and
 * backward), and on the live screen through [KalmanFilter2D].
 */
class ActivityRecorder(
    private val source: LocationSource,
    private val looper: Looper,
    private val dao: ActivityPointDao,
    private val scope: CoroutineScope,
    /** Called after each accepted fix, so the service can refresh its notification. */
    private val onFix: (Location) -> Unit,
) {
    @Volatile var session: ActivitySession? = null
        private set
    @Volatile var lastFixAtElapsedMs: Long = 0L
        private set
    @Volatile var lastAccuracyM: Float? = null
        private set
    @Volatile var acceptedCount: Int = 0
        private set

    private var stream: FixStream? = null
    private var armedScreenOn: Boolean? = null
    private var filter = ActivityFilter(ActivitySport.OTHER)

    private val lock = Any()
    private val buffer = ArrayList<ActivityPoint>()
    private var lastFlushAtElapsedMs = 0L
    private val writeMutex = Mutex()
    private val rejectedSinceFlush = java.util.concurrent.atomic.AtomicInteger(0)

    val active: Boolean get() = session != null

    /**
     * Start (or keep) recording [s]. Idempotent: re-arms the stream only when the
     * session or the screen state actually changed, or when [force]d (a provider
     * toggle, or the watchdog finding the stream silent).
     */
    @Synchronized
    fun start(s: ActivitySession, screenOn: Boolean, force: Boolean = false) {
        val newSession = session?.id != s.id
        if (newSession) {
            filter = ActivityFilter(ActivitySport.of(s.kind))
            acceptedCount = 0
            lastAccuracyM = null
            lastFixAtElapsedMs = 0L
        }
        session = s
        if (!newSession && !force && armedScreenOn == screenOn && stream != null) return

        stream?.cancel()
        val request = FixRequest(
            intervalMs = INTERVAL_MS,
            minIntervalMs = INTERVAL_MS,
            maxDelayMs = if (screenOn) INTERVAL_MS else BATCH_SCREEN_OFF_MS,
            accuracy = FixAccuracy.HIGH,
            gnssOnly = true,
        )
        stream = source.requestUpdates(request, looper) { handle(it) }
        armedScreenOn = screenOn
        if (stream == null) Log.e(TAG, "Could not arm ${source.label} for recording")
        else Log.i(TAG, "Recording ${s.kind} — stream armed (screenOn=$screenOn)")
    }

    /** Stop capturing and write out whatever is buffered. */
    @Synchronized
    fun stop() {
        if (session == null && stream == null) return
        stream?.cancel()
        stream = null
        armedScreenOn = null
        session = null
        scope.launch { flush() }
        Log.i(TAG, "Recording stream stopped")
    }

    private fun handle(loc: Location) {
        val s = session ?: return
        if (!filter.accept(loc)) {
            // Tallied in memory and written at flush: in a poor-signal stretch this
            // can fire every second, and each bump rewrites a SharedPreferences file.
            rejectedSinceFlush.incrementAndGet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastFixAtElapsedMs = now
        lastAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null
        acceptedCount++
        val due = synchronized(lock) {
            buffer += ActivityPoint(
                activityId = s.id,
                t = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                alt = if (loc.hasAltitude()) loc.altitude else null,
                acc = if (loc.hasAccuracy()) loc.accuracy else null,
                spd = if (loc.hasSpeed()) loc.speed else null,
            )
            // Screen-on the live screen wants fresh rows every couple of seconds;
            // screen-off a batch delivery is the natural write boundary.
            val gap = if (armedScreenOn == true) FLUSH_SCREEN_ON_MS else FLUSH_SCREEN_OFF_MS
            buffer.size >= FLUSH_MAX_POINTS || now - lastFlushAtElapsedMs >= gap
        }
        if (due) scope.launch { flush() }
        onFix(loc)
    }

    /** Write buffered fixes to Room. Serialised, so rows land in capture order. */
    suspend fun flush() {
        rejectedSinceFlush.getAndSet(0).takeIf { it > 0 }?.let {
            CaptureStats.bump(CaptureStats.Counter.ACTIVITY_REJECTED, it)
        }
        writeMutex.withLock {
            val batch = synchronized(lock) {
                lastFlushAtElapsedMs = SystemClock.elapsedRealtime()
                if (buffer.isEmpty()) return
                ArrayList(buffer).also { buffer.clear() }
            }
            runCatching { dao.insertAll(batch) }
                .onFailure { e ->
                    Log.e(TAG, "Failed to write ${batch.size} recorded fixes", e)
                    // Put them back so the next flush retries rather than losing them.
                    synchronized(lock) { buffer.addAll(0, batch) }
                }
        }
    }

    companion object {
        const val INTERVAL_MS = 1_000L
        /** Screen-off batch window: the chip keeps sampling at 1 Hz, the CPU wakes
         *  about this often to take the batch. */
        const val BATCH_SCREEN_OFF_MS = 20_000L
        const val FLUSH_SCREEN_ON_MS = 2_000L
        const val FLUSH_SCREEN_OFF_MS = 15_000L
        const val FLUSH_MAX_POINTS = 120
        /** No fix for this long while the screen is on: the stream has died; re-arm. */
        const val STALL_MS = 60_000L
    }
}
