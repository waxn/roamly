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
 * Uploads cached points to /api/push/ using the app's existing authenticated RoamlyApi.
 * Auth (Bearer token + session cookie) is injected automatically by AppModule's interceptors.
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

            val syncedIds = mutableListOf<Long>()
            for (point in batch) {
                try {
                    val resp = api.pushLocation(point.toPayload(deviceId))
                    when {
                        resp.isSuccessful -> { syncedIds.add(point.id); uploaded++ }
                        resp.code() == 401 || resp.code() == 403 -> {
                            db.pointDao().markSynced(syncedIds)
                            writeSyncResult(false, uploaded, "Auth failed (${resp.code()}) — check API key")
                            return Result.success()
                        }
                        resp.code() in 400..499 -> {
                            // Bad point — skip it to avoid infinite retry
                            syncedIds.add(point.id)
                            Log.w(TAG, "Skipping point ${point.id}: HTTP ${resp.code()}")
                        }
                        else -> {
                            db.pointDao().markSynced(syncedIds)
                            writeSyncResult(false, uploaded, "Server error ${resp.code()}")
                            return Result.retry()
                        }
                    }
                } catch (e: Exception) {
                    db.pointDao().markSynced(syncedIds)
                    writeSyncResult(false, uploaded, e.message ?: "Network error")
                    return Result.retry()
                }
            }
            db.pointDao().markSynced(syncedIds)
            if (batch.size < BATCH) break
        }

        // Prune synced points older than 7 days
        db.pointDao().pruneOldSynced(System.currentTimeMillis() - 7 * 86_400_000L)

        Log.i(TAG, "Uploaded $uploaded points")
        writeSyncResult(true, uploaded, "")
        return Result.success()
    }

    private suspend fun writeSyncResult(success: Boolean, count: Int, error: String) {
        prefs.setSyncResult(System.currentTimeMillis(), success, count, error)
    }

    companion object {
        const val ONETIME_TAG  = "roamly_upload_now"
        private const val PERIODIC_TAG = "roamly_upload_periodic"

        fun scheduleNow(context: Context, syncOnMobileData: Boolean = true, replace: Boolean = false) {
            // Automatic (point-driven) syncs use KEEP so they don't pile up. A
            // user-initiated sync passes replace=true so it cancels any job stuck
            // in retry-backoff and runs a fresh attempt immediately instead of being
            // silently dropped by KEEP.
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

        /** Stop the recurring background upload entirely (used by a full stop). */
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
