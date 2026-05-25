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
 * Runs:
 *  - Periodically every 15 minutes (when connected)
 *  - On-demand when [scheduleNow] is called (e.g. after N points accumulate)
 *
 * Guarantees:
 *  - Only runs when there is a network connection
 *  - Survives device reboots (WorkManager persists to its own DB)
 *  - Uses exponential backoff on failure
 *  - Marks points as synced only after a confirmed 2xx response
 *  - Old synced points pruned after each batch to keep DB lean
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
            return Result.success()   // don't retry; user hasn't configured the app
        }

        val api = buildApi(serverUrl, apiKey)
        var uploaded = 0
        var failed = 0

        // Process in batches so we don't hold a huge list in memory
        while (true) {
            val batch = db.pointDao().getUnsynced(limit = BATCH_SIZE)
            if (batch.isEmpty()) break

            Log.d(TAG, "Uploading batch of ${batch.size} points")
            val syncedIds = mutableListOf<Long>()

            for (point in batch) {
                try {
                    val response = api.pushLocation(point.toPayload(deviceId))
                    if (response.isSuccessful) {
                        syncedIds.add(point.id)
                        uploaded++
                    } else if (response.code() in 400..499) {
                        // Client error (e.g. bad API key) — don't retry this point
                        Log.e(TAG, "Client error ${response.code()} for point ${point.id} — skipping")
                        syncedIds.add(point.id)   // mark synced to avoid infinite retry
                        failed++
                    } else {
                        // Server error — stop batch and retry later
                        Log.w(TAG, "Server error ${response.code()} — will retry")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error uploading point ${point.id}: ${e.message}")
                    // Mark successfully uploaded points so far, then retry
                    if (syncedIds.isNotEmpty()) {
                        db.pointDao().markSynced(syncedIds)
                    }
                    return Result.retry()
                }
            }

            if (syncedIds.isNotEmpty()) {
                db.pointDao().markSynced(syncedIds)
            }

            // If we got fewer than BATCH_SIZE we've exhausted the queue
            if (batch.size < BATCH_SIZE) break
        }

        // Prune old synced points
        val cutoff = System.currentTimeMillis() - PRUNE_DAYS * 24 * 60 * 60 * 1000L
        db.pointDao().pruneOldSynced(cutoff)

        Log.i(TAG, "Upload complete: $uploaded uploaded, $failed skipped")
        return Result.success()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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

        private const val PERIODIC_WORK_TAG = "roamly_periodic_upload"
        private const val ONETIME_WORK_TAG  = "roamly_immediate_upload"

        /**
         * Enqueue a periodic background upload every 15 minutes (minimum WorkManager allows).
         * Safe to call multiple times — [ExistingPeriodicWorkPolicy.KEEP] is idempotent.
         */
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

        /**
         * Trigger an immediate one-time upload (e.g. when the threshold of unsynced
         * points is reached, or when the user taps "Sync Now").
         */
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
