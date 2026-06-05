package com.roamly.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiskCache"

/**
 * Generic disk-backed JSON cache for API responses. Lets screens paint the
 * last-known data instantly on cold start (and offline) while a fresh request
 * runs in the background, instead of showing an empty skeleton every launch.
 *
 * Each key maps to one small JSON file under cacheDir/api_cache. Reads are cheap
 * (a few KB) and intended to be done synchronously during ViewModel init for an
 * instant first frame; writes happen after a successful network refresh.
 *
 * The cache is per-account — it is wiped on sign-out (see [clearAll]) so a new
 * user never sees the previous user's data.
 */
@Singleton
class DiskCache @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
) {
    private val dir = File(context.cacheDir, "api_cache").apply { mkdirs() }

    private fun fileFor(key: String) = File(dir, "$key.json")

    fun <T> get(key: String, type: Type): T? = try {
        val f = fileFor(key)
        if (!f.exists()) null else gson.fromJson<T>(f.readText(), type)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read cache '$key'", e)
        null
    }

    fun put(key: String, value: Any) {
        try {
            fileFor(key).writeText(gson.toJson(value))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache '$key'", e)
        }
    }

    /** Wipe every cached response. Called on sign-out. */
    fun clearAll() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache", e)
        }
    }

    companion object Keys {
        const val STATS = "stats_default"
        const val STATS_YEARLY = "stats_yearly"
        const val VISITS = "visits"
        const val TRIPS = "trips"
        const val PALS = "pals"
        /** Map locations are cached per time-period, e.g. map_locations_H24. */
        fun mapLocations(period: String) = "map_locations_$period"
        fun mapStats(period: String) = "map_stats_$period"
    }
}
