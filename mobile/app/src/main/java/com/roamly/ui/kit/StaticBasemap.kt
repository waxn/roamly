package com.roamly.ui.kit

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web-Mercator maths and a small tile loader for [TrackSketch]: thumbnails in
 * scrolling lists cannot host a live MapView, so they paint a handful of OSM
 * tiles (memory-cached, plus an OkHttp disk cache) under the track instead.
 */
internal object StaticBasemap {
    /** World x in 0..1 for a longitude. */
    fun worldX(lng: Double) = (lng + 180.0) / 360.0

    /** World y in 0..1 (north at 0) for a latitude, clamped to Mercator's limit. */
    fun worldY(lat: Double): Double {
        val l = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return 0.5 - ln(tan(PI / 4 + l / 2)) / (2 * PI)
    }

    data class Tile(val z: Int, val x: Int, val y: Int) { val key get() = "$z/$x/$y" }

    // ~24 MB of decoded tiles shared by every thumbnail.
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun cached(t: Tile): ImageBitmap? = memory.get(t.key)

    @Volatile private var client: OkHttpClient? = null
    private fun client(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .cache(Cache(File(context.applicationContext.cacheDir, "sketch_tiles"), 40L * 1024 * 1024))
                .build().also { client = it }
        }

    /** Fetch (or return the cached) tile; null on any failure so the sketch just stays plain. */
    suspend fun load(context: Context, t: Tile): ImageBitmap? {
        memory.get(t.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png")
                    .header("User-Agent", "Roamly/1.0")
                    .build()
                client(context).newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        ?.also { memory.put(t.key, it) }
                }
            }.getOrNull()
        }
    }
}
