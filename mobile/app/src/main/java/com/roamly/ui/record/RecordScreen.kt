package com.roamly.ui.record

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.ActivityDto
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

private val KINDS = listOf(
    "ride" to "Ride",
    "run" to "Run",
    "walk" to "Walk",
    "hike" to "Hike",
    "other" to "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf("ride") }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadRecent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (state.recording) "Recording" else "Record",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        state.error?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))

        if (state.recording) {
            LiveStats(state)
            Spacer(Modifier.height(12.dp))
            LiveTrackMap(state.track, Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClayButton(
                    onClick = { viewModel.stop() },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.saving) "Saving…" else "Stop & save") }
                ClayOutlinedButton(
                    onClick = { confirmDiscard = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Discard") }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text("What are you doing?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            KindPicker(kind) { kind = it }
            Spacer(Modifier.height(12.dp))
            ClayButton(
                onClick = { viewModel.start(kind) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start recording") }
            Spacer(Modifier.height(20.dp))
            Text("Recent activities", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            RecentList(state, Modifier.weight(1f)) { viewModel.deleteActivity(it) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this recording?") },
            // Worth saying plainly: people expect Discard to undo the tracking too.
            text = {
                Text("The activity won't be saved. The GPS points stay in your " +
                     "normal location history, as they would have anyway.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep recording") }
            },
        )
    }
}

@Composable
private fun KindPicker(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KINDS.forEach { (slug, label) ->
            FilterChip(
                selected = slug == selected,
                onClick = { onSelect(slug) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun LiveStats(state: RecordUiState) {
    ClayCard {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(formatDuration(state.elapsedMs), "elapsed")
            Stat(formatKm(state.distanceM), "km")
            Stat(formatSpeed(state.currentSpeedMps ?: 0f), "km/h")
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(formatSpeed(state.avgSpeedMps), "avg", small = true)
            Stat(formatSpeed(state.maxSpeedMps), "max", small = true)
            Stat("${state.pointCount}", "points", small = true)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, small: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = if (small) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The live track.
 *
 * Its own [MapView], following the JournalsScreen / TripFullMapScreen pattern —
 * deliberately not MapViewModel's retained instance, which is kept alive across
 * tab switches precisely because recreating *that* one crashes osmdroid's shared
 * tile cache. A second, self-contained instance is fine and already done twice.
 */
@Composable
private fun LiveTrackMap(track: List<TrackPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lineColor = Clay.colors.mapBlue.toArgb()
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache =
            java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            controller.setZoom(15.0)
        }
    }

    // Keyed on the point count, not the list: the list identity changes on every
    // fold, and redrawing a polyline per fix would fight the user's own panning.
    LaunchedEffect(track.size) {
        if (track.size < 2) return@LaunchedEffect
        val geo = track.map { GeoPoint(it.lat, it.lng) }
        mapView.overlays.clear()
        mapView.overlays.add(Polyline(mapView).apply {
            setPoints(geo)
            outlinePaint.color = lineColor
            outlinePaint.strokeWidth = 7f
            outlinePaint.isAntiAlias = true
        })
        mapView.post {
            runCatching {
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 64)
            }
            mapView.invalidate()
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    ClayCard(contentPadding = 0.dp, modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RecentList(
    state: RecordUiState,
    modifier: Modifier = Modifier,
    onDelete: (Int) -> Unit,
) {
    if (state.recent.isEmpty()) {
        Text(
            if (state.loadingRecent) "Loading…" else "Nothing recorded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.recent, key = { it.id }) { act -> ActivityRow(act, onDelete) }
    }
}

@Composable
private fun ActivityRow(act: ActivityDto, onDelete: (Int) -> Unit) {
    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    act.title.ifBlank { KINDS.firstOrNull { it.first == act.kind }?.second ?: act.kind },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append(act.start.take(10))
                        // Null until the server has computed stats — an activity can be
                        // saved before its points finish uploading, so say so rather
                        // than showing a confident zero.
                        if (act.computedAt == null) {
                            append(" · waiting for points")
                        } else {
                            append(" · ")
                            append(String.format(Locale.US, "%.2f km", act.distanceKm ?: 0.0))
                            append(" · ")
                            append(formatDuration((act.movingSeconds ?: 0) * 1000L))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onDelete(act.id) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatKm(metres: Double): String =
    String.format(Locale.US, "%.2f", metres / 1000.0)

private fun formatSpeed(mps: Float): String =
    ((mps * 3.6f) * 10).roundToInt().let { String.format(Locale.US, "%.1f", it / 10f) }
