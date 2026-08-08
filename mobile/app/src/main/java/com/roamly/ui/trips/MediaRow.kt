package com.roamly.ui.trips

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.roamly.data.api.MediaItem

/** Build an absolute URL for a (possibly relative) media path. */
fun mediaUrl(serverUrl: String, path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http")) return path
    return serverUrl.trimEnd('/') + path
}

/** A horizontally-scrolling row of photo/video thumbnails. Tapping an image opens
 *  a full-screen lightbox; tapping a video opens it in an external player. Shared
 *  by story photo_grid blocks, blurb photos, and day-log photos. */
@Composable
fun MediaThumbnailRow(
    media: List<MediaItem>,
    serverUrl: String,
    thumbSize: Int = 92,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty()) return
    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        media.forEach { m ->
            val thumb = mediaUrl(serverUrl, m.thumb ?: m.url)
            Box(
                modifier = Modifier
                    .size(thumbSize.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22000000))
                    .clickable {
                        if (m.type == "video") {
                            val v = mediaUrl(serverUrl, m.video ?: m.url)
                            if (v != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)))
                        } else {
                            mediaUrl(serverUrl, m.url ?: m.thumb)?.let { lightbox = it }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (m.type == "video") {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    lightbox?.let { url ->
        Dialog(onDismissRequest = { lightbox = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xF2000000)).clickable { lightbox = null }) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
                IconButton(onClick = { lightbox = null }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White)
                }
            }
        }
    }
}
