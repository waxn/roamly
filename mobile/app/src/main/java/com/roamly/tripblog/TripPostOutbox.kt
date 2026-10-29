package com.roamly.tripblog

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * Trip-blog posts waiting to upload, one folder per post under
 * `filesDir/tripblog_outbox/<clientId>/`: the picked media copied in, plus a
 * `post.json` manifest recording how far the upload got.
 *
 * Media is copied at "Post" time because a photo-picker URI's read grant dies
 * with the process, and the whole point is that a post outlives the app being
 * swiped away on bad hotel wifi. TripPostWorker drains it; the manifest makes
 * every step resumable, and the server dedupes the post itself on clientId.
 */
object TripPostOutbox {
    data class Item(
        val file: String,
        val kind: String,           // "image" | "video"
        val name: String,
        val order: Int,
        var done: Boolean = false,
        var failed: Boolean = false,
        var mediaId: Int? = null,
        var nextChunk: Int = 0,
    )

    data class Pending(
        val blogId: Int,
        val clientId: String,
        val text: String,
        val lat: Double?,
        val lon: Double?,
        val takenAt: String?,
        val items: MutableList<Item>,
        var draftId: Int? = null,
        var error: String? = null,
    )

    private val gson = Gson()

    private fun root(context: Context) = File(context.filesDir, "tripblog_outbox").apply { mkdirs() }

    fun dir(context: Context, clientId: String) = File(root(context), clientId)

    fun all(context: Context): List<Pending> =
        root(context).listFiles()?.mapNotNull { d -> load(File(d, "post.json")) }
            ?.sortedBy { it.clientId } ?: emptyList()

    fun count(context: Context): Int = root(context).listFiles()?.count { File(it, "post.json").exists() } ?: 0

    fun save(context: Context, p: Pending) {
        val d = dir(context, p.clientId).apply { mkdirs() }
        val tmp = File(d, "post.json.tmp")
        tmp.writeText(gson.toJson(p))
        tmp.renameTo(File(d, "post.json"))
    }

    fun remove(context: Context, clientId: String) {
        dir(context, clientId).deleteRecursively()
    }

    private fun load(f: File): Pending? = runCatching {
        if (!f.exists()) null else gson.fromJson(f.readText(), Pending::class.java)
    }.getOrNull()

    /** Copy the picked media in and write the manifest. Runs off the main thread. */
    fun enqueue(
        context: Context, blogId: Int, text: String, lat: Double?, lon: Double?,
        takenAt: String?, media: List<Pair<Uri, String>>,
    ): Pending {
        val clientId = UUID.randomUUID().toString()
        val d = dir(context, clientId).apply { mkdirs() }
        val items = mutableListOf<Item>()
        media.forEachIndexed { i, (uri, kind) ->
            val name = displayName(context, uri) ?: if (kind == "video") "video.mp4" else "photo.jpg"
            val ext = name.substringAfterLast('.', if (kind == "video") "mp4" else "jpg").lowercase()
            val out = File(d, "m$i.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it, 64 * 1024) }
            } ?: return@forEachIndexed
            items += Item(file = out.name, kind = kind, name = name, order = i)
        }
        val p = Pending(blogId, clientId, text, lat, lon, takenAt, items)
        save(context, p)
        return p
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
