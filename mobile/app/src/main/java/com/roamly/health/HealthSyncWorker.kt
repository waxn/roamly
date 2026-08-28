package com.roamly.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.Record
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.roamly.data.api.HealthSampleDto
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.HealthRepository
import com.roamly.data.repository.Result as ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val TAG = "HealthSyncWorker"
private const val PUSH_BATCH = 1000          // matches the server's per-request cap
private const val BACKFILL_WINDOW_DAYS = 30L // read history a month at a time

/**
 * Pulls steps / distance / calories out of Health Connect and pushes them to the
 * server.
 *
 * Runs every 6 hours rather than the uploader's 15 minutes: health data is
 * daily-granularity, so a tighter cadence would burn battery reading Health
 * Connect and add server requests for no visible benefit.
 *
 * Two modes, chosen by whether a changes token has been stored:
 *  - **Backfill** (no token) — walks the history in month-sized windows, saving a
 *    cursor after each one so a killed worker resumes rather than starting over.
 *    The token is minted *before* the backfill starts, so anything written during
 *    it is picked up by the first incremental run instead of being missed.
 *  - **Incremental** (token present) — asks Health Connect what changed, which
 *    yields both upserts and deleted record ids.
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val health: HealthConnectManager,
    private val repo: HealthRepository,
    private val prefs: UserPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!prefs.healthEnabled.first()) return Result.success()

        if (health.availability() != HealthConnectManager.Availability.AVAILABLE) {
            prefs.setHealthSyncResult(false, 0, "Health Connect is not available")
            return Result.success()
        }
        // A revoked permission must stop the worker, not spin it forever.
        if (!health.hasRequiredPermissions()) {
            prefs.setHealthSyncResult(false, 0, "Health Connect permission was revoked")
            return Result.success()
        }

        val deviceId = prefs.deviceId.first()?.trim().orEmpty()

        return try {
            val token = prefs.healthChangesToken.first()
            val synced = if (token.isBlank()) backfill(deviceId) else incremental(token, deviceId)
            prefs.setHealthSyncResult(true, synced, "")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "health sync failed", e)
            prefs.setHealthSyncResult(false, 0, e.message ?: "Sync failed")
            Result.retry()
        }
    }

    /** First run: mint the cursor, then walk history a month at a time. */
    private suspend fun backfill(deviceId: String): Int {
        // Minted before reading so records written *during* the backfill are
        // caught by the next incremental run rather than falling in the gap.
        val token = health.changesToken()

        val days = prefs.healthBackfillDays.first().toLong()
        val now = Instant.now()
        val floor = now.minus(days, ChronoUnit.DAYS)
        val resumeAt = prefs.healthBackfillCursor.first()
        var windowEnd = if (resumeAt > 0) Instant.ofEpochMilli(resumeAt) else now

        var total = 0
        while (windowEnd.isAfter(floor)) {
            val windowStart = maxOf(floor, windowEnd.minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS))
            val samples = health.readAllMetrics(windowStart, windowEnd)
                .mapNotNull { it.toHealthSample(deviceId) }
            total += pushInBatches(samples)
            // Saved only after the window's rows are actually on the server, so a
            // crash re-reads that window rather than skipping it.
            prefs.setHealthBackfillCursor(windowStart.toEpochMilli())
            windowEnd = windowStart
        }

        prefs.setHealthChangesToken(token)
        prefs.setHealthBackfillDone(true)
        return total
    }

    /** Subsequent runs: ask Health Connect what changed. */
    private suspend fun incremental(startToken: String, deviceId: String): Int {
        var token = startToken
        var total = 0

        while (true) {
            val response = health.changes(token)

            if (response.changesTokenExpired) {
                // Tokens expire after roughly a month unused. Re-backfill the
                // window Health Connect can still answer for, rather than leaving
                // the sync permanently stalled.
                prefs.setHealthChangesToken("")
                prefs.setHealthBackfillCursor(0L)
                prefs.setHealthBackfillDone(false)
                return total + backfill(deviceId)
            }

            val samples = mutableListOf<HealthSampleDto>()
            val deleted = mutableListOf<String>()
            response.changes.forEach { change ->
                change.deletedIdOrNull()?.let { deleted += it }
                change.upsertedRecordOrNull()?.let { record: Record ->
                    record.toHealthSample(deviceId)?.let { samples += it }
                }
            }

            total += pushInBatches(samples, deleted)
            token = response.nextChangesToken
            prefs.setHealthChangesToken(token)
            if (!response.hasMore) break
        }
        return total
    }

    /** Push in server-sized chunks; deletions ride along with the first chunk. */
    private suspend fun pushInBatches(
        samples: List<HealthSampleDto>,
        deleted: List<String> = emptyList(),
    ): Int {
        if (samples.isEmpty() && deleted.isEmpty()) return 0
        var accepted = 0
        var pendingDeletes = deleted
        val chunks = if (samples.isEmpty()) listOf(emptyList()) else samples.chunked(PUSH_BATCH)
        for (chunk in chunks) {
            when (val result = repo.pushSamples(chunk, pendingDeletes)) {
                is ApiResult.Success -> accepted += result.data.accepted
                is ApiResult.Error -> throw IllegalStateException(result.message)
            }
            pendingDeletes = emptyList()
        }
        return accepted
    }

    companion object {
        const val ONETIME_TAG = "roamly_health_sync_now"
        private const val PERIODIC_TAG = "roamly_health_sync_periodic"

        fun scheduleNow(context: Context, replace: Boolean = true) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_TAG,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HealthSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(ONETIME_TAG)
                    .build()
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                    .addTag(PERIODIC_TAG)
                    .build()
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_TAG)
        }
    }
}
