package com.roamly.tracking

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.roamly.data.api.ActivityCreateRequest
import com.roamly.data.api.ActivityTrackChunkRequest
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "ActivitySaveWorker"
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/**
 * Saves a finished recording: the envelope, then the raw track in chunks.
 *
 * The track comes from [ActivityPoint] rows the recorder wrote privately during the
 * ride — it never went through the all-day upload queue. It is sent once, at the
 * end, in order, in chunks of [CHUNK_SIZE]; the server stores it, smooths it, scores
 * it and mirrors a thinned copy into normal history when the final chunk lands.
 *
 * Everything is idempotent, so a retry after any partial success is harmless:
 * `client_id` dedupes the envelope, chunk indices dedupe the track (a chunk the
 * server already holds is acknowledged; one from the future is refused with the
 * index it expects, which is where this resumes). Local rows are deleted only once
 * a response says the track is complete.
 *
 * WorkManager persists the input [Data], so the envelope survives a process kill;
 * the track survives in Room.
 */
@HiltWorker
class ActivitySaveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
    private val db: TrackingDatabase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val clientId = inputData.getString(KEY_CLIENT_ID).orEmpty()
        val startedAt = inputData.getLong(KEY_STARTED_AT, 0L)
        val endedAt = inputData.getLong(KEY_ENDED_AT, 0L)
        if (clientId.isBlank() || startedAt <= 0L || endedAt <= 0L) {
            Log.e(TAG, "Incomplete activity envelope — nothing to save")
            return Result.failure()
        }

        val deviceId = prefs.deviceId.first()?.trim().orEmpty()
        if (deviceId.isBlank()) {
            // Unreachable in practice: a recording can only start while tracking is
            // enabled, which requires a registered device. Retrying would not help.
            Log.e(TAG, "Device ID not set — cannot save activity $clientId")
            return Result.failure()
        }

        val dao = db.activityPointDao()
        val pointCount = awaitSettledCount(dao, clientId)

        val body = ActivityCreateRequest(
            clientId = clientId,
            deviceId = deviceId,
            kind = inputData.getString(KEY_KIND) ?: "other",
            title = inputData.getString(KEY_TITLE).orEmpty(),
            notes = inputData.getString(KEY_NOTES).orEmpty(),
            start = ISO.format(Instant.ofEpochMilli(startedAt)),
            end = ISO.format(Instant.ofEpochMilli(endedAt)),
            hasTrack = pointCount > 0,
        )

        return try {
            val resp = api.createActivity(body)
            when {
                resp.isSuccessful -> {
                    Log.i(TAG, "Saved activity $clientId (created=${resp.body()?.created})")
                    if (pointCount > 0) uploadTrack(dao, clientId) else Result.success()
                }
                // Auth and malformed-envelope failures do not fix themselves by being
                // asked again; anything else might, so let WorkManager back off.
                resp.code() in 400..499 -> {
                    Log.e(TAG, "Activity save rejected (${resp.code()}) — giving up on $clientId")
                    Result.failure()
                }
                else -> {
                    Log.w(TAG, "Activity save failed (${resp.code()}) — will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Activity save error — will retry", e)
            Result.retry()
        }
    }

    /**
     * Wait for the recorder's last buffered write to land. Stop clears the session
     * and enqueues this worker in the same breath, and the service's final flush
     * runs moments later; reading the track before it would cut off the end of the
     * ride and shift every chunk boundary on a retry.
     */
    private suspend fun awaitSettledCount(dao: ActivityPointDao, clientId: String): Int {
        var last = dao.count(clientId)
        repeat(SETTLE_MAX_CHECKS) {
            delay(SETTLE_CHECK_MS)
            val now = dao.count(clientId)
            if (now == last) return now
            last = now
        }
        return last
    }

    private suspend fun uploadTrack(dao: ActivityPointDao, clientId: String): Result {
        val total = dao.count(clientId)
        val chunks = (total + CHUNK_SIZE - 1) / CHUNK_SIZE
        var chunk = 0
        var cursor = 0L
        var skipTo = -1
        while (chunk < chunks) {
            val rows = dao.after(clientId, cursor, CHUNK_SIZE)
            if (rows.isEmpty()) break
            cursor = rows.last().id
            if (chunk < skipTo) { chunk++; continue }
            val req = ActivityTrackChunkRequest(
                clientId = clientId,
                chunk = chunk,
                final = chunk == chunks - 1,
                points = rows.map { listOf(it.t, it.lat, it.lon, it.alt, it.acc, it.spd) },
            )
            val resp = api.uploadActivityTrack(req)
            val out = resp.body()
            when {
                resp.isSuccessful && out != null -> {
                    if (out.complete) {
                        dao.deleteActivity(clientId)
                        Log.i(TAG, "Track for $clientId uploaded (${out.total} fixes)")
                        return Result.success()
                    }
                    // A duplicate means an earlier attempt got further — jump ahead.
                    if (out.duplicate && out.nextChunk > chunk + 1) skipTo = out.nextChunk
                    chunk++
                }
                resp.code() == 409 -> {
                    // The server is missing an earlier chunk. A retry starts again at
                    // chunk 0, which comes back as a duplicate carrying the index the
                    // server wants — so it resumes exactly there.
                    Log.w(TAG, "Track chunk $chunk out of order — retrying from the server's position")
                    return Result.retry()
                }
                resp.code() == 404 -> return Result.retry()  // envelope not there yet
                resp.code() in 400..499 -> {
                    // Keep the local rows: losing a recorded ride over a server
                    // complaint is worse than a buffer that lingers.
                    Log.e(TAG, "Track upload rejected (${resp.code()}) — keeping local copy of $clientId")
                    CaptureStats.bump(CaptureStats.Counter.ACTIVITY_UPLOAD_FAILED)
                    return Result.failure()
                }
                else -> {
                    CaptureStats.bump(CaptureStats.Counter.ACTIVITY_UPLOAD_FAILED)
                    return Result.retry()
                }
            }
        }
        // Every chunk sent without a "complete" — the final one must have been lost.
        return Result.retry()
    }

    companion object {
        private const val KEY_CLIENT_ID  = "client_id"
        private const val KEY_KIND       = "kind"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_ENDED_AT   = "ended_at"
        private const val KEY_TITLE      = "title"
        private const val KEY_NOTES      = "notes"

        const val TAG_SAVE = "roamly_activity_save"

        /** Rows per upload request: ~100 KB of JSON, well under the server's cap. */
        private const val CHUNK_SIZE = 2000
        private const val SETTLE_CHECK_MS = 1_500L
        private const val SETTLE_MAX_CHECKS = 8

        /**
         * Queue one activity for saving.
         *
         * The unique work name carries the client id on purpose: a single fixed name
         * would make two rides finished back to back collide, and KEEP would silently
         * drop the second envelope.
         */
        fun enqueue(context: Context, session: ActivitySession, endedAtMs: Long,
                    title: String = "", notes: String = "") {
            val data = Data.Builder()
                .putString(KEY_CLIENT_ID, session.id)
                .putString(KEY_KIND, session.kind)
                .putLong(KEY_STARTED_AT, session.startedAtMs)
                .putLong(KEY_ENDED_AT, endedAtMs)
                .putString(KEY_TITLE, title)
                .putString(KEY_NOTES, notes)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$TAG_SAVE:${session.id}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ActivitySaveWorker>()
                    .setInputData(data)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(TAG_SAVE)
                    .build()
            )
        }
    }
}
