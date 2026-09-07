package com.roamly.tracking

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.roamly.data.api.ActivityCreateRequest
import com.roamly.data.api.RoamlyApi
import com.roamly.data.prefs.ActivitySession
import com.roamly.data.prefs.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "ActivitySaveWorker"
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/**
 * Posts the envelope of a finished recording to the server.
 *
 * Only the envelope: the server derives distance, moving time and speeds from the
 * Location rows in the window, so this worker does not have to wait for — or know
 * anything about — the point upload. The two are independent, which is what makes
 * a save correct when the ride was recorded in a tunnel and uploads an hour later.
 *
 * There is no local table behind this. WorkManager persists the input [Data]
 * itself, so the envelope survives a process kill for free, and `client_id` makes
 * the POST idempotent server-side, so a retry of a call that actually landed is
 * harmless rather than a duplicate ride.
 */
@HiltWorker
class ActivitySaveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RoamlyApi,
    private val prefs: UserPreferences,
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

        val body = ActivityCreateRequest(
            clientId = clientId,
            deviceId = deviceId,
            kind = inputData.getString(KEY_KIND) ?: "other",
            title = inputData.getString(KEY_TITLE).orEmpty(),
            notes = inputData.getString(KEY_NOTES).orEmpty(),
            start = ISO.format(Instant.ofEpochMilli(startedAt)),
            end = ISO.format(Instant.ofEpochMilli(endedAt)),
        )

        return try {
            val resp = api.createActivity(body)
            when {
                resp.isSuccessful -> {
                    Log.i(TAG, "Saved activity $clientId (created=${resp.body()?.created})")
                    Result.success()
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

    companion object {
        private const val KEY_CLIENT_ID  = "client_id"
        private const val KEY_KIND       = "kind"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_ENDED_AT   = "ended_at"
        private const val KEY_TITLE      = "title"
        private const val KEY_NOTES      = "notes"

        const val TAG_SAVE = "roamly_activity_save"

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
