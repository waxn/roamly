package com.roamly.data.prefs

import android.content.Context

/**
 * A synchronously-readable mirror of [UserPreferences.trackingEnabled].
 *
 * The per-fix exact alarm is delivered to `LocationTrackingService.onStartCommand`
 * on the **main thread**, which read the flag with
 * `runBlocking { prefs.trackingEnabled.first() }` — a blocking DataStore read,
 * on the main thread, once per captured point. Anything else occupying the main
 * thread (Compose recomposition, map work; the service shares a process with the
 * UI) therefore delayed the alarm, and a late alarm shifts the whole cadence.
 *
 * SharedPreferences is loaded once and served from memory afterwards, so reading
 * it costs a map lookup. DataStore remains the source of truth: this mirror is
 * only ever trusted to keep tracking *running*. Deciding to stop still confirms
 * against DataStore asynchronously, so a mirror that is stale or missing (a fresh
 * install, a value written before this existed) can never silently kill tracking.
 */
object TrackingEnabledMirror {
    private const val PREFS = "roamly_tracking_flags"
    private const val KEY = "tracking_enabled"

    fun set(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /** Defaults to `true` so an unknown value never stops a running service — the
     *  caller's asynchronous DataStore check is what actually authorises stopping. */
    fun get(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
}
