package com.roamly.ui.tripblog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayOutlinedButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * New trip-blog post: text, photos/videos, and where it happened — the photo's
 * own GPS when it has some, the phone's latest fix (⌖), or a spot picked on the
 * map. "Post" copies the media into the outbox and returns at once; the upload
 * happens in TripPostWorker, so it survives bad wifi and the app being closed.
 */
@Composable
fun TripComposeScreen(viewModel: TripBlogViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val c by viewModel.composer.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addMedia(uris.map { u ->
                val type = context.contentResolver.getType(u).orEmpty()
                PickedMedia(u, if (type.startsWith("video")) "video" else "image")
            })
        }
    }
    // Android hides a photo's GPS from apps without this permission; asking is
    // optional — declining only means no "where the photo was taken" chip.
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    fun pick() {
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (needs) mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("New post", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ClayButton(
                onClick = { viewModel.submit(onDone) },
                enabled = !c.busy && (c.text.isNotBlank() || c.media.isNotEmpty()),
            ) { Text(if (c.busy) "Saving…" else "Post") }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
               verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = c.text, onValueChange = viewModel::setText,
                placeholder = { Text("What happened today?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            if (c.media.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(c.media, key = { it.uri.toString() }) { m ->
                        Box(Modifier.size(96.dp).background(Color(0x22000000))) {
                            AsyncImage(model = m.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                            if (m.kind == "video") Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(30.dp))
                            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape)
                                .background(Color(0x99000000)), Alignment.Center) {
                                IconButton(onClick = { viewModel.removeMedia(m) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            ClayOutlinedButton(onClick = ::pick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (c.media.isEmpty()) "Add photos or videos" else "Add more")
            }

            Text("Where", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.locLabel != null) {
                    InputChip(selected = true, onClick = viewModel::clearLocation, label = { Text(c.locLabel!!) },
                        leadingIcon = { Icon(Icons.Rounded.Place, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "Clear", Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f, fill = false))
                } else {
                    Text("No location", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                IconButton(onClick = { viewModel.useMyLocation { note = "No recent location from your phone yet." } }) {
                    Icon(Icons.Rounded.MyLocation, "Use my current location")
                }
                IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.Place, "Pick on the map") }
            }
            if (c.takenLabel != null) {
                InputChip(selected = true, onClick = viewModel::clearTime, label = { Text("🕒 ${c.takenLabel}") },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Use now instead", Modifier.size(16.dp)) })
            }
            if (c.media.any { it.kind == "video" }) {
                Text("Videos are converted on the server after uploading so they play everywhere — it can take a few minutes.",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) {
        PickLocationDialog(
            start = if (c.lat != null && c.lon != null) GeoPoint(c.lat!!, c.lon!!) else null,
            onDismiss = { picking = false },
            onPicked = { gp -> picking = false; viewModel.setPicked(gp.latitude, gp.longitude) },
        )
    }
}

@Composable
private fun PickLocationDialog(start: GeoPoint?, onDismiss: () -> Unit, onPicked: (GeoPoint) -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(start) }
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(if (start != null) 13.0 else 3.0)
            start?.let { controller.setCenter(it) }
        }
    }
    val marker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
    DisposableEffect(mapView) {
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                chosen = p
                marker.position = p
                if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
                mapView.invalidate()
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        start?.let { marker.position = it; mapView.overlays.add(marker) }
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f), color = MaterialTheme.colorScheme.surface) {
            Column {
                Text("Tap where this happened", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                AndroidView(factory = { mapView }, modifier = Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    ClayButton(onClick = { chosen?.let(onPicked) }, enabled = chosen != null) { Text("Use this spot") }
                }
            }
        }
    }
}
