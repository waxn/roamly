package com.roamly.ui.tripblog

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a photo's EXIF says about where and when it was taken. */
data class ExifInfo(val lat: Double?, val lon: Double?, val takenAt: String?, val takenLabel: String?)

/**
 * Reads location + time from a picked photo, for the composer's suggestion chips.
 *
 * Android redacts GPS from shared media unless the app holds
 * ACCESS_MEDIA_LOCATION *and* asks for the original via setRequireOriginal —
 * which only works on MediaStore URIs, so anything else (the photo picker on
 * some versions, a file manager) falls back to the redacted stream and simply
 * yields no location. The time is usually still there.
 */
object MediaExif {
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val LABEL = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    fun read(context: Context, uri: Uri): ExifInfo? {
        val exif = open(context, uri) ?: return null
        val ll = FloatArray(2)
        val hasLL = runCatching { exif.getLatLong(ll) }.getOrDefault(false) &&
            !(ll[0] == 0f && ll[1] == 0f)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        // "OffsetTimeOriginal" (ExifInterface.TAG_OFFSET_TIME_ORIGINAL), spelled out
        // so lint does not tie the read to the API level that added the constant.
        val offset = exif.getAttribute("OffsetTimeOriginal")
        var iso: String? = null
        var label: String? = null
        runCatching {
            val local = LocalDateTime.parse(raw!!.trim().take(19), EXIF_TIME)
            label = local.format(LABEL) + " · from photo"
            // With an offset it is an instant. Without one it goes up as a
            // wall-clock time, which the server reads in the zone of the post's
            // location (the zone the camera's clock was in).
            iso = if (!offset.isNullOrBlank()) OffsetDateTime.of(local, ZoneOffset.of(offset.trim())).toString()
                  else local.toString()
        }
        return ExifInfo(
            lat = if (hasLL) ll[0].toDouble() else null,
            lon = if (hasLL) ll[1].toDouble() else null,
            takenAt = iso, takenLabel = label,
        )
    }

    private fun open(context: Context, uri: Uri): ExifInterface? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { add(MediaStore.setRequireOriginal(uri)) }
            }
            add(uri)
        }
        for (u in candidates) {
            val e = runCatching {
                context.contentResolver.openInputStream(u)?.use { ExifInterface(it) }
            }.getOrNull()
            if (e != null) return e
        }
        return null
    }
}
