package com.roamly.tracker.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.roamly.tracker.api.AuthInterceptor
import com.roamly.tracker.api.LocationPayload
import com.roamly.tracker.api.RoamlyApi
import com.roamly.tracker.db.AppDatabase
import com.roamly.tracker.db.CachedPoint
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "UploadWorker"
private const val BATCH_SIZE = 100
private const val PRUNE_DAYS = 7L

/**
 * WorkManager worker that bulk-uploads unsynced cached points to the Roamly server.
 *
 * After each run it writes the result into SharedPreferences so the UI can display
 * "Last synced: X ago (N points)" or "Last sync failed: reason" without polling.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: AppDatabase,
    private val prefs: SharedPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val serverUrl = prefs.getString(PREF_SERVER_URL, "") ?: ""
        val apiKey    = prefs.getString(PREF_API_KEY, "") ?: ""
        val deviceId  = prefs.getString(PREF_DEVICE_ID, "") ?: ""

        if (serverUrl.isBlank() || apiKey.isBlank() || deviceId.isBlank()) {
            Log.w(TAG, "Missing configuration — skipping upload")
            writeSyncResult(success = false, uploaded = 0, error = "Not configured")
            return Result.success()
        }

        val api = buildApi(serverUrl, apiKey)
        var uploaded = 0
        var failed = 0

        while (true) {
            val batch = db.pointDao().getUnsynced(limit = BATCH_SIZE)
            if (batch.isEmpty()) break

            Log.d(TAG, "Uploading batch of ${batch.size} points")
            val syncedIds = mutableListOf<Long>()

            for (point in batch) {
                try {
                    val response = api.pushLocation(point.toPayload(deviceId))
                    when {
                        response.isSuccessful -> {
                            syncedIds.add(point.id)
                            uploaded++
                        }
                        response.code() in 400..499 -> {
                            // Client error (bad API key, bad payload) — skip to avoid loop
                            val msg = "HTTP ${response.code()}"
                            Log.e(TAG, "Client error $msg for point ${point.id}")
                            syncedIds.add(point.id)
                            failed++
                            if (response.code() == 401 || response.code() == 403) {
                                db.pointDao().markSynced(syncedIds)
                                writeSyncResult(success = false, uploaded = uploaded,
                                    error = "Auth failed (${response.code()}) — check API key")
                                return Result.success()
                            }
                        }
                        else -> {
                            Log.w(TAG, "Server error ${response.code()} — will retry")
                            if (syncedIds.isNotEmpty()) db.pointDao().markSynced(syncedIds)
                            writeSyncResult(success = false, uploaded = uploaded,
                                error = "Server error ${response.code()}")
                            return Result.retry()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error: ${e.message}")
                    if (syncedIds.isNotEmpty()) db.pointDao().markSynced(syncedIds)
                    writeSyncResult(success = false, uploaded = uploaded,
                        error = e.message ?: "Network error")
                    return Result.retry()
                }
            }

            if (syncedIds.isNotEmpty()) db.pointDao().markSynced(syncedIds)
            if (batch.size < BATCH_SIZE) break
        }

        // Prune old synced points
        val cutoff = System.currentTimeMillis() - PRUNE_DAYS * 24 * 60 * 60 * 1000L
        db.pointDao().pruneOldSynced(cutoff)

        Log.i(TAG, "Upload complete: $uploaded uploaded, $failed skipped")
        writeSyncResult(success = true, uploaded = uploaded, error = null)
        return Result.success()
    }

    // ── Write result to prefs so UI can read it ────────────────────────────

    private fun writeSyncResult(success: Boolean, uploaded: Int, error: String?) {
        prefs.edit()
            .putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
            .putBoolean(PREF_LAST_SYNC_SUCCESS, success)
            .putInt(PREF_LAST_SYNC_COUNT, uploaded)
            .putString(PREF_LAST_SYNC_ERROR, error ?: "")
            .apply()
    }

    // ── HTTP client ────────────────────────────────────────────────────────

    private fun buildApi(baseUrl: String, apiKey: String): RoamlyApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { apiKey })
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RoamlyApi::class.java)
    }

    companion object {
        const val PREF_SERVER_URL = "server_url"
        const val PREF_API_KEY    = "api_key"
        const val PREF_DEVICE_ID  = "device_id"

        // Written after every run — read by MainActivity to show sync status
        const val PREF_LAST_SYNC_TIME    = "last_sync_time"
        const val PREF_LAST_SYNC_SUCCESS = "last_sync_success"
        const val PREF_LAST_SYNC_COUNT   = "last_sync_count"
        const val PREF_LAST_SYNC_ERROR   = "last_sync_error"

        private const val PERIODIC_WORK_TAG = "roamly_periodic_upload"
        const val ONETIME_WORK_TAG          = "roamly_immediate_upload"

        fun schedulePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(PERIODIC_WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ONETIME_WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_WORK_TAG,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

// ── Extension ──────────────────────────────────────────────────────────────

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private fun CachedPoint.toPayload(deviceId: String): LocationPayload {
    val ts = ISO_FORMATTER.format(Instant.ofEpochMilli(timestamp))
    return LocationPayload(
        deviceId  = deviceId,
        latitude  = latitude,
        longitude = longitude,
        timestamp = ts,
        altitude  = altitude,
        accuracy  = accuracy,
        speed     = speed,
        battery   = battery
    )
}
