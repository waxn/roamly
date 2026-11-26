package com.roamly.ui.tripblog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.configureOsmdroid
import com.roamly.ui.kit.tappable
import com.roamly.ui.map.applyBasemap
import androidx.compose.ui.graphics.toArgb
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * New trip-blog post: text, photos/videos, and where it happened — the photo's
 * own GPS when it has some, the phone's latest fix, or a spot picked on the
 * map. "Post" copies the media into the outbox and returns at once; the upload
 * happens in TripPostWorker, so it survives bad wifi and the app being closed.
 */
@Composable
fun TripComposeScreen(viewModel: TripBlogViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val a = Atlas.colors
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

    PushedScreen(
        title = "New post",
        onBack = onDone,
        actions = {
            AtlasButton("Post", { viewModel.submit(onDone) }, compact = true, loading = c.busy,
                enabled = c.text.isNotBlank() || c.media.isNotEmpty(), feel = Feel.Confirm)
        },
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = c.text, onValueChange = viewModel::setText,
                placeholder = { Text("What happened today?", color = a.textFaint) },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = SquircleShape(SquircleRadius.panel),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = a.surfaceHigh, unfocusedContainerColor = a.surfaceHigh,
                    focusedBorderColor = a.accent, unfocusedBorderColor = Color.Transparent, cursorColor = a.accent,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            if (c.media.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(c.media, key = { it.uri.toString() }) { m ->
                        Box(Modifier.size(100.dp).clip(SquircleShape(SquircleRadius.control)).background(a.surfaceHigh)) {
                            AsyncImage(model = m.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (m.kind == "video") Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
                            IconAction(Icons.Rounded.Close, "Remove", { viewModel.removeMedia(m) }, Modifier.align(Alignment.TopEnd).padding(4.dp),
                                tint = Color.White, container = Color.Black.copy(alpha = 0.55f), size = 24.dp, iconSize = 15.dp)
                        }
                    }
                }
            }
            AtlasButton(if (c.media.isEmpty()) "Add photos or videos" else "Add more", ::pick, Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary, icon = Icons.Rounded.AddPhotoAlternate)

            SectionTitle("Where", Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = c.locLabel
                if (label != null) {
                    InfoChip(Icons.Rounded.Place, label, Modifier.weight(1f, fill = false), onClear = viewModel::clearLocation)
                } else {
                    Text("No location", style = MaterialTheme.typography.bodyMedium, color = a.textMuted, modifier = Modifier.weight(1f))
                }
                if (label != null) Spacer(Modifier.weight(1f))
                IconAction(Icons.Rounded.MyLocation, "Use my current location", { viewModel.useMyLocation { note = "No recent location from your phone yet." } }, tint = a.accent)
                IconAction(Icons.Rounded.Place, "Pick on the map", { picking = true }, tint = a.accent)
            }
            c.takenLabel?.let { InfoChip(Icons.Rounded.Schedule, it, onClear = viewModel::clearTime) }
            if (c.media.any { it.kind == "video" }) {
                Text("Videos are converted on the server after uploading so they play everywhere — it can take a few minutes.",
                    style = MaterialTheme.typography.bodySmall, color = a.textMuted)
            }
            note?.let { ErrorNote(it) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) {
        PickLocationDialog(
            start = if (c.lat != null && c.lon != null) GeoPoint(c.lat!!, c.lon!!) else null,
            area = viewModel::pickerArea,
            onDismiss = { picking = false },
            onPicked = { gp -> picking = false; viewModel.setPicked(gp.latitude, gp.longitude) },
        )
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClear: () -> Unit) {
    val a = Atlas.colors
    Row(
        modifier.clip(SquircleShape(50)).background(a.accentSoft).border(1.dp, a.accent.copy(alpha = 0.5f), SquircleShape(50))
            .tappable(feel = Feel.Tick, pressedScale = 0.95f, onClick = onClear).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = a.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = a.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Close, "Clear", tint = a.textMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PickLocationDialog(
    start: GeoPoint?,
    area: suspend () -> Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onPicked: (GeoPoint) -> Unit,
) {
    val context = LocalContext.current
    val a = Atlas.colors
    var chosen by remember { mutableStateOf(start) }
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            applyBasemap("Auto", "", a.isDark, a.surface.toArgb())
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
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
    // Open on where the trip is now rather than on the whole world. A tap made while this loads wins.
    LaunchedEffect(Unit) {
        if (start != null) return@LaunchedEffect
        val (lat, lon) = area() ?: return@LaunchedEffect
        if (chosen == null) {
            mapView.controller.setZoom(11.0)
            mapView.controller.setCenter(GeoPoint(lat, lon))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f).clip(SquircleShape(SquircleRadius.dialog)).background(a.surface)) {
            Text("Tap where this happened", style = MaterialTheme.typography.titleLarge, color = a.text, modifier = Modifier.padding(20.dp))
            AndroidView(factory = { mapView }, modifier = Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtlasButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AtlasButton("Use this spot", { chosen?.let(onPicked) }, Modifier.weight(1.4f), enabled = chosen != null, feel = Feel.Confirm)
            }
        }
    }
}
