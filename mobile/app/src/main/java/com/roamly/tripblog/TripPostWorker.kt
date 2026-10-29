package com.roamly.tripblog

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roamly.data.api.RoamlyApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Drains [TripPostOutbox]: create the post as a draft (idempotent on clientId),
 * upload each photo, send each video in 8 MB chunks, then publish.
 *
 * Every step is recorded in the manifest as it lands, so a retry — after a
 * dropped connection, a killed process, a reboot — resumes rather than starts
 * over. A 4xx on one file marks that file failed and moves on (the server will
 * never accept it); anything else is a retry with backoff.
 */
@HiltWorker
class TripPostWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RoamlyApi,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var retry = false
        for (p in TripPostOutbox.all(applicationContext)) {
            try {
                when (send(p)) {
                    Outcome.DONE -> TripPostOutbox.remove(applicationContext, p.clientId)
                    Outcome.RETRY -> retry = true
                    Outcome.DROP -> TripPostOutbox.remove(applicationContext, p.clientId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "trip post ${p.clientId} failed", e)
                p.error = e.message
                TripPostOutbox.save(applicationContext, p)
                retry = true
            }
        }
        return if (retry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private enum class Outcome { DONE, RETRY, DROP }

    private suspend fun send(p: TripPostOutbox.Pending): Outcome {
        val ctx = applicationContext
        val dir = TripPostOutbox.dir(ctx, p.clientId)
        if (p.draftId == null) {
            val r = api.createTripPost(p.blogId, mapOf("client_id" to p.clientId, "publish" to false))
            if (r.code() in 400..499 && r.code() != 408 && r.code() != 429) return Outcome.DROP
            val id = r.body()?.id ?: return Outcome.RETRY
            p.draftId = id
            TripPostOutbox.save(ctx, p)
        }
        val postId = p.draftId!!
        for (item in p.items) {
            if (item.done || item.failed) continue
            val f = File(dir, item.file)
            if (!f.exists()) { item.failed = true; TripPostOutbox.save(ctx, p); continue }
            val ok = if (item.kind == "video") sendVideo(p, postId, item, f) else sendPhoto(p.blogId, postId, item, f)
            if (ok == null) return Outcome.RETRY
            if (ok) item.done = true else item.failed = true
            TripPostOutbox.save(ctx, p)
        }
        val body = mutableMapOf<String, Any>("text" to p.text, "publish" to true)
        if (p.lat != null && p.lon != null) { body["lat"] = p.lat; body["lon"] = p.lon }
        p.takenAt?.let { body["taken_at"] = it }
        val r = api.updateTripPost(p.blogId, postId, body)
        return when {
            r.isSuccessful -> Outcome.DONE
            r.code() == 404 -> Outcome.DROP      // trip or draft gone
            else -> Outcome.RETRY
        }
    }

    /** true = stored, false = refused for good, null = try again later. */
    private suspend fun sendPhoto(blogId: Int, postId: Int, item: TripPostOutbox.Item, f: File): Boolean? {
        val part = MultipartBody.Part.createFormData("file", item.name, f.asRequestBody("image/*".toMediaTypeOrNull()))
        val r = api.uploadTripPhoto(blogId, postId, part, item.order.toString().toRequestBody())
        return when {
            r.isSuccessful -> true
            r.code() in 400..499 && r.code() != 408 && r.code() != 429 -> false
            else -> null
        }
    }

    private suspend fun sendVideo(p: TripPostOutbox.Pending, postId: Int, item: TripPostOutbox.Item, f: File): Boolean? {
        val size = f.length()
        val total = maxOf(1, ((size + CHUNK - 1) / CHUNK).toInt())
        RandomAccessFile(f, "r").use { raf ->
            while (item.nextChunk < total) {
                val i = item.nextChunk
                val start = i.toLong() * CHUNK
                val len = minOf(CHUNK.toLong(), size - start).toInt()
                val buf = ByteArray(len)
                raf.seek(start)
                raf.readFully(buf)
                val chunk = MultipartBody.Part.createFormData(
                    "chunk", "chunk", buf.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                val r = api.uploadTripVideoChunk(
                    p.blogId, postId, chunk,
                    i.toString().toRequestBody(), total.toString().toRequestBody(),
                    item.name.toRequestBody(), item.order.toString().toRequestBody(),
                    item.mediaId?.toString()?.toRequestBody(),
                )
                val b = r.body()
                if (r.code() == 409) {
                    // Out of order: the server says where it actually is.
                    val next = runCatching {
                        com.google.gson.JsonParser.parseString(r.errorBody()?.string()).asJsonObject.get("next_chunk").asInt
                    }.getOrNull() ?: return null
                    item.nextChunk = next
                } else if (r.isSuccessful && b != null) {
                    if (b.mediaId != null) item.mediaId = b.mediaId
                    if (b.status == "complete") { item.nextChunk = total; return true }
                    item.nextChunk = b.nextChunk ?: (i + 1)
                } else if (r.code() in 400..499 && r.code() != 408 && r.code() != 429) {
                    return false
                } else {
                    return null
                }
                TripPostOutbox.save(applicationContext, p)
            }
        }
        return true
    }

    companion object {
        private const val TAG = "TripPostWorker"
        private const val UNIQUE = "roamly_trip_post_outbox"
        private const val CHUNK = 8 * 1024 * 1024
        private const val MAX_ATTEMPTS = 40

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                // APPEND_OR_REPLACE: a post queued while a drain runs gets its
                // own pass afterwards instead of being missed by the running one.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TripPostWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
