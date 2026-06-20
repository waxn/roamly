package com.roamly.tracking

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.roamly.data.api.LocationPushPayload
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "UploadWorker"
private const val BATCH = 100
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/**
 * Uploads cached points to the server. Tries the batch endpoint first (100 points
 * per HTTP call), falling back to single-point push if the server returns 404/405
 * (older instance without /api/push/batch/). Auth is injected by AppModule interceptors.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: TrackingDatabase,
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deviceId = prefs.deviceId.first()?.trim().orEmpty()
        if (deviceId.isBlank()) {
            writeSyncResult(false, 0, "Device ID not set")
            return Result.success()
        }

        var uploaded = 0

        while (true) {
            val batch = db.pointDao().getUnsynced(BATCH)
            if (batch.isEmpty()) break

            when (val result = uploadBatch(batch, deviceId)) {
                is UploadResult.Success    -> uploaded += result.count
                is UploadResult.AuthFailed -> {
                    writeSyncResult(false, uploaded, result.message)
                    return Result.success()
                }
                is UploadResult.RetriableError -> {
                    writeSyncResult(false, uploaded, result.message)
                    return Result.retry()
                }
            }

            if (batch.size < BATCH) break
        }

        db.pointDao().pruneOldSynced(System.currentTimeMillis() - 7 * 86_400_000L)
        Log.i(TAG, "Uploaded $uploaded points")
        writeSyncResult(true, uploaded, "")
        return Result.success()
    }

    /**
     * Upload one batch (≤100 points). Tries the batch endpoint first; if the server
     * returns 404/405 (endpoint absent) or a network exception falls through to
     * single-point. Each path marks uploaded IDs in Room before returning Success.
     */
    private suspend fun uploadBatch(batch: List<CachedPoint>, deviceId: String): UploadResult {
        // ── Batch path ────────────────────────────────────────────────────────
        try {
            val resp = api.pushLocationBatch(batch.map { it.toPayload(deviceId) })
            when {
                resp.isSuccessful -> {
                    db.pointDao().markSynced(batch.map { it.id })
                    return UploadResult.Success(batch.size)
                }
                resp.code() == 401 || resp.code() == 403 ->
                    return UploadResult.AuthFailed("Auth failed (${resp.code()}) — check API key")
                resp.code() == 404 || resp.code() == 405 ->
                    Unit  // endpoint not on this server, fall through to single-point
                resp.code() in 400..499 -> {
                    // Bad batch data — skip the whole batch rather than loop forever
                    db.pointDao().markSynced(batch.map { it.id })
                    Log.w(TAG, "Batch rejected by server (${resp.code()}), skipping")
                    return UploadResult.Success(0)
                }
                else -> return UploadResult.RetriableError("Server error ${resp.code()}")
            }
        } catch (_: Exception) {
            // Network error or endpoint absent — fall through to single-point
        }

        // ── Single-point fallback ─────────────────────────────────────────────
        val syncedIds = mutableListOf<Long>()
        for (point in batch) {
            try {
                val resp = api.pushLocation(point.toPayload(deviceId))
                when {
                    resp.isSuccessful -> syncedIds.add(point.id)
                    resp.code() == 401 || resp.code() == 403 -> {
                        db.pointDao().markSynced(syncedIds)
                        return UploadResult.AuthFailed("Auth failed (${resp.code()}) — check API key")
                    }
                    resp.code() in 400..499 -> syncedIds.add(point.id)  // bad point, skip it
                    else -> {
                        db.pointDao().markSynced(syncedIds)
                        return UploadResult.RetriableError("Server error ${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                db.pointDao().markSynced(syncedIds)
                return UploadResult.RetriableError(e.message ?: "Network error")
            }
        }
        db.pointDao().markSynced(syncedIds)
        return UploadResult.Success(syncedIds.size)
    }

    private suspend fun writeSyncResult(success: Boolean, count: Int, error: String) {
        prefs.setSyncResult(System.currentTimeMillis(), success, count, error)
    }

    private sealed class UploadResult {
        data class Success(val count: Int) : UploadResult()
        data class AuthFailed(val message: String) : UploadResult()
        data class RetriableError(val message: String) : UploadResult()
    }

    companion object {
        const val ONETIME_TAG  = "roamly_upload_now"
        private const val PERIODIC_TAG = "roamly_upload_periodic"

        fun scheduleNow(context: Context, syncOnMobileData: Boolean = true, replace: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_TAG, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(
                                if (syncOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
                            )
                            .build()
                    )
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(ONETIME_TAG)
                    .build()
            )
        }

        fun schedulePeriodic(context: Context, syncOnMobileData: Boolean = true) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(
                                if (syncOnMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
                            )
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .addTag(PERIODIC_TAG)
                    .build()
            )
        }

        fun reschedulePeriodic(context: Context, syncOnMobileData: Boolean) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_TAG)
            schedulePeriodic(context, syncOnMobileData)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_TAG)
        }
    }
}

private fun CachedPoint.toPayload(deviceId: String) = LocationPushPayload(
    deviceId  = deviceId,
    latitude  = latitude,
    longitude = longitude,
    timestamp = ISO.format(Instant.ofEpochMilli(timestamp)),
    altitude  = altitude,
    accuracy  = accuracy,
    speed     = speed,
    battery   = battery,
)
