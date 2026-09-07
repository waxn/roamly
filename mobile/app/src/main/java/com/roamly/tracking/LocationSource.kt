package com.roamly.tracking

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LocationSource"

/** How long a one-shot [LocationSource.currentFix] waits before giving up. */
private const val ONE_SHOT_TIMEOUT_MS = 15_000L

/**
 * Where fixes come from.
 *
 * Every location the app has ever recorded came from Play Services' fused provider, which is
 * fine on stock Android and records **nothing at all** on a de-Googled build (GrapheneOS,
 * CalyxOS, /e/OS, a LineageOS without GApps). Worse, it fails *silently*:
 * `getFusedLocationProviderClient` happily returns a client — the classes ship inside our own
 * APK, only the backing service is absent — so the foreground service starts, the notification
 * says "tracking", the alarms fire on schedule, and every `requestLocationUpdates` fails into a
 * `runCatching { }` while `lastLocation` resolves to null. Perfect-looking tracking, zero points.
 *
 * So the fused provider becomes one implementation of this interface and the platform's own
 * [LocationManager] becomes the other, chosen once per process by [LocationSource.get].
 *
 * The interface is deliberately narrow — arm a stream, read the last known fix, take one
 * fresh fix — because that is the entire surface `LocationTrackingService` ever used. Everything
 * that makes capture work ([LocationFilter], [DriftAnchor], the best-fix budget, the dwell
 * substitution, the retry ladder, the Doze alarm cadence) operates on a bare
 * [android.location.Location] and neither knows nor cares which provider produced it.
 */
interface LocationSource {

    /** Short name for logs and the Diagnostics screen. */
    val label: String

    /** One line naming the source *and* what it is actually using right now, so a phone that
     *  is recording nothing can say why rather than looking identical to a phone with no sky. */
    fun describe(): String

    /**
     * Arm a stream of fixes delivered on [looper]. Returns null when nothing could be armed
     * (no provider, permission revoked, Play Services absent) — callers treat that as "this
     * cycle produced no fix" rather than an error, since the alarm cadence retries anyway.
     */
    fun requestUpdates(req: FixRequest, looper: Looper, onFix: (Location) -> Unit): FixStream?

    /** Most recent cached fix, or null. Must not block; [onResult] may run on any thread. */
    fun lastKnown(onResult: (Location?) -> Unit)

    /**
     * Actively acquire a single fresh fix. For one-shot callers (the map's "have I been
     * here?"); the tracking cadence does **not** use this — it runs its own budgeted best-fix
     * loop over [requestUpdates] so it can stop early on a good-enough or plateaued fix.
     */
    fun currentFix(accuracy: FixAccuracy, onResult: (Location?) -> Unit)

    companion object {
        @Volatile private var cached: LocationSource? = null

        /**
         * The process-wide source. Resolved once and remembered: which one is correct can only
         * change when Play Services is installed or removed, and both of those restart us. A
         * user who installs sandboxed Play Services while Roamly is running keeps the platform
         * source until the next process start, which is harmless — it works either way.
         */
        fun get(context: Context): LocationSource =
            cached ?: synchronized(this) {
                cached ?: build(context.applicationContext).also {
                    cached = it
                    Log.i(TAG, "Location source: ${it.describe()}")
                }
            }

        private fun build(context: Context): LocationSource =
            if (playServicesUsable(context)) FusedLocationSource(context)
            else PlatformLocationSource(context)

        /**
         * Only `SUCCESS` counts. Missing, disabled, out-of-date and invalid installs all get the
         * platform source — a working coarse-grained tracker beats a fused client that answers
         * every request with a failure nobody sees.
         *
         * Wrapped in `runCatching` for the `NoClassDefFoundError` case: if the Play Services
         * artifacts are ever stripped from a build, that must degrade to the platform source
         * rather than crash the tracking service at startup.
         */
        private fun playServicesUsable(context: Context): Boolean = runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrElse {
            Log.w(TAG, "Play Services availability check failed — using the platform provider", it)
            false
        }
    }
}

/** How precisely to ask, and therefore which providers to wake. */
enum class FixAccuracy { HIGH, BALANCED, LOW }

/** An armed stream. [cancel] is idempotent and safe from any thread. */
fun interface FixStream {
    fun cancel()
}

/**
 * A source-neutral request. The defaults reproduce the fused stream's semantics: a floor equal
 * to the interval (so the provider doesn't deliver — and burn battery on — sub-interval fixes)
 * and a batch window equal to the interval (so fixes arrive as produced, never batched).
 * The best-fix burst overrides both to 0, wanting every fix the chip can emit.
 */
data class FixRequest(
    val intervalMs: Long,
    val minIntervalMs: Long = intervalMs,
    val maxDelayMs: Long = intervalMs,
    val accuracy: FixAccuracy = FixAccuracy.HIGH,
)

// ── Google Play Services ─────────────────────────────────────────────────────

/** The original path, unchanged in behaviour — just moved behind the interface. */
private class FusedLocationSource(context: Context) : LocationSource {

    override val label = "Google Play Services"

    private val client = LocationServices.getFusedLocationProviderClient(context)

    override fun describe() = "$label · fused provider"

    private fun priorityOf(accuracy: FixAccuracy) = when (accuracy) {
        FixAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
        FixAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        FixAccuracy.LOW -> Priority.PRIORITY_LOW_POWER
    }

    @Suppress("MissingPermission")
    override fun requestUpdates(
        req: FixRequest,
        looper: Looper,
        onFix: (Location) -> Unit,
    ): FixStream? {
        val request = LocationRequest.Builder(priorityOf(req.accuracy), req.intervalMs)
            .setMinUpdateIntervalMillis(req.minIntervalMs)
            .setMaxUpdateDelayMillis(req.maxDelayMs)
            .setMinUpdateDistanceMeters(0f)   // never gate on movement — stationary still updates
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) onFix(loc)
            }
        }
        return runCatching {
            client.requestLocationUpdates(request, callback, looper)
            FixStream { runCatching { client.removeLocationUpdates(callback) } }
        }.onFailure { Log.e(TAG, "fused requestLocationUpdates failed", it) }.getOrNull()
    }

    @Suppress("MissingPermission")
    override fun lastKnown(onResult: (Location?) -> Unit) {
        val fired = AtomicBoolean(false)
        runCatching {
            client.lastLocation
                .addOnSuccessListener { if (fired.compareAndSet(false, true)) onResult(it) }
                .addOnFailureListener { if (fired.compareAndSet(false, true)) onResult(null) }
        }.onFailure { if (fired.compareAndSet(false, true)) onResult(null) }
    }

    @Suppress("MissingPermission")
    override fun currentFix(accuracy: FixAccuracy, onResult: (Location?) -> Unit) {
        val fired = AtomicBoolean(false)
        runCatching {
            client.getCurrentLocation(priorityOf(accuracy), null)
                .addOnSuccessListener { if (fired.compareAndSet(false, true)) onResult(it) }
                .addOnFailureListener { if (fired.compareAndSet(false, true)) onResult(null) }
        }.onFailure { if (fired.compareAndSet(false, true)) onResult(null) }
    }
}

// ── Platform LocationManager (no Google dependency) ──────────────────────────

/**
 * The de-Googled path, built on [LocationManager] via androidx's compat layer so one code path
 * covers `minSdk 26` through current.
 *
 * The platform has **no fusion layer**, which is the one real difference. Rather than reinvent
 * one, this arms every usable provider at once and lets the machinery downstream do the merging
 * it already does: `acquireBestFix` keeps the most accurate fix of a cycle, and [LocationFilter]
 * drops stale, duplicate and teleporting fixes. Fused delivers interleaved GPS and network fixes
 * into exactly the same funnel, so this is not a new code path for them to handle.
 */
private class PlatformLocationSource(context: Context) : LocationSource {

    override val label = "Android system"

    private val manager = runCatching {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull()

    override fun describe(): String {
        val providers = providersFor(FixAccuracy.HIGH)
        return if (providers.isEmpty()) "$label · no provider available"
        else "$label · ${providers.joinToString(", ")}"
    }

    private fun qualityOf(accuracy: FixAccuracy) = when (accuracy) {
        FixAccuracy.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
        FixAccuracy.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        FixAccuracy.LOW -> LocationRequestCompat.QUALITY_LOW_POWER
    }

    /**
     * Which providers to arm, best-first.
     *
     * The last-resort clause is the important one. A de-Googled phone frequently has **no
     * network provider at all** (there is no Google network-location service to back it), and
     * `PASSIVE_PROVIDER` yields nothing unless some other app happens to be requesting location.
     * Returning an empty list there would reproduce the exact bug this class exists to fix, so
     * GPS is armed even when it is currently switched off — the service's `PROVIDERS_CHANGED`
     * receiver re-arms capture the moment the user turns location back on.
     */
    private fun providersFor(accuracy: FixAccuracy): List<String> {
        val lm = manager ?: return emptyList()
        val present = runCatching { lm.allProviders }.getOrNull().orEmpty()
        val wanted = when (accuracy) {
            FixAccuracy.HIGH ->
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            FixAccuracy.BALANCED ->
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            FixAccuracy.LOW ->
                listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
        }
        val enabled = wanted.filter { p ->
            p in present && runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
        }
        if (enabled.isNotEmpty()) return enabled
        return listOf(LocationManager.GPS_PROVIDER).filter { it in present }
    }

    @Suppress("MissingPermission")
    override fun requestUpdates(
        req: FixRequest,
        looper: Looper,
        onFix: (Location) -> Unit,
    ): FixStream? {
        val lm = manager ?: return null
        val providers = providersFor(req.accuracy)
        if (providers.isEmpty()) {
            Log.e(TAG, "No location provider available on this device")
            return null
        }
        val request = LocationRequestCompat.Builder(req.intervalMs)
            .setQuality(qualityOf(req.accuracy))
            .setMinUpdateIntervalMillis(req.minIntervalMs)
            .setMaxUpdateDelayMillis(req.maxDelayMs)
            .setMinUpdateDistanceMeters(0f)   // never gate on movement — stationary still updates
            .build()
        val armed = mutableListOf<LocationListenerCompat>()
        for (provider in providers) {
            val listener = object : LocationListenerCompat {
                override fun onLocationChanged(location: Location) = onFix(location)
            }
            runCatching {
                LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, looper)
                armed += listener
            }.onFailure { Log.w(TAG, "requestLocationUpdates($provider) failed", it) }
        }
        if (armed.isEmpty()) return null
        return FixStream {
            for (listener in armed) {
                runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
            }
        }
    }

    /**
     * Newest wins, across every provider. Not "most accurate": a stale-but-precise GPS fix is
     * the wrong answer for the two callers (seeding the first point, and anchoring a cycle that
     * acquired nothing), and the accuracy gate in `saveOrDwell` plus `LocationFilter`'s staleness
     * check already reject whatever this hands back if it is not good enough to keep.
     */
    @Suppress("MissingPermission")
    override fun lastKnown(onResult: (Location?) -> Unit) {
        val lm = manager ?: return onResult(null)
        var best: Location? = null
        for (provider in runCatching { lm.allProviders }.getOrNull().orEmpty()) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            val incumbent = best
            if (incumbent == null || loc.elapsedRealtimeNanos > incumbent.elapsedRealtimeNanos) {
                best = loc
            }
        }
        onResult(best)
    }

    /**
     * Built on [requestUpdates] rather than `LocationManagerCompat.getCurrentLocation`: that
     * helper carries two overloads differing only in which `CancellationSignal` they take, so a
     * bare `null` there is an overload-resolution ambiguity. Taking the first fix off a
     * short-lived stream needs no new API surface and behaves identically.
     */
    @Suppress("MissingPermission")
    override fun currentFix(accuracy: FixAccuracy, onResult: (Location?) -> Unit) {
        val looper = Looper.getMainLooper()
        val handler = Handler(looper)
        val fired = AtomicBoolean(false)
        val streamRef = AtomicReference<FixStream?>(null)

        fun finish(loc: Location?) {
            if (!fired.compareAndSet(false, true)) return
            streamRef.getAndSet(null)?.cancel()
            onResult(loc)
        }

        val timeout = Runnable { finish(null) }
        val armed = requestUpdates(FixRequest(0L, 0L, 0L, accuracy), looper) { loc ->
            handler.removeCallbacks(timeout)
            finish(loc)
        }
        if (armed == null) {
            finish(null)
            return
        }
        streamRef.set(armed)
        // A cached fix can be delivered before the reference is stored, in which case finish()
        // had nothing to cancel — so cancel here instead of leaking the stream.
        if (fired.get()) armed.cancel() else handler.postDelayed(timeout, ONE_SHOT_TIMEOUT_MS)
    }
}
