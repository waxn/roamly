package com.roamly.ui.record

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.ActivityDto
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.TopBar
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

private data class Kind(val slug: String, val label: String, val icon: ImageVector)

private val KINDS = listOf(
    Kind("ride", "Ride", Icons.Rounded.DirectionsBike),
    Kind("run", "Run", Icons.Rounded.DirectionsRun),
    Kind("walk", "Walk", Icons.Rounded.DirectionsWalk),
    Kind("hike", "Hike", Icons.Rounded.Hiking),
    Kind("row", "Row", Icons.Rounded.Rowing),
    Kind("other", "Other", Icons.Rounded.Explore),
)

/** Record a ride, run or walk: pick, start, and watch it draw itself live. */
@Composable
fun RecordScreen(onBack: () -> Unit, viewModel: RecordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf("ride") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ActivityDto?>(null) }
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) { viewModel.loadRecent() }

    AnimatedContent(state.recording, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "rec") { recording ->
        if (recording) {
            Recording(
                state = state,
                onBack = onBack,
                onStop = { haptics.thud(); viewModel.stop() },
                onDiscard = { confirmDiscard = true },
            )
        } else {
            Column(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
                TopBar("Record", onBack)
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { item { ErrorNote(it) } }
                    item { SectionTitle("What are you doing?") }
                    item { KindGrid(kind) { kind = it } }
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                            StartButton { haptics.thud(); viewModel.start(kind) }
                        }
                    }
                    item { SectionTitle("Recent activities") }
                    if (state.recent.isEmpty()) {
                        item {
                            Text(if (state.loadingRecent) "Loading…" else "Nothing recorded yet.",
                                style = MaterialTheme.typography.bodyMedium, color = Atlas.colors.textMuted)
                        }
                    }
                    items(state.recent, key = { it.id }) { act -> ActivityRow(act) { pendingDelete = act } }
                }
            }
        }
    }

    if (confirmDiscard) {
        AtlasDialog(
            title = "Discard this recording?",
            onDismiss = { confirmDiscard = false },
            confirmText = "Discard",
            onConfirm = { confirmDiscard = false; viewModel.discard() },
            dismissText = "Keep recording",
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            // Normal tracking pauses while recording, so this is the only copy.
            body = { Text("The activity and its track will be deleted. Normal tracking was paused while recording, so this stretch won't appear in your history either.") },
        )
    }
    pendingDelete?.let { act ->
        AtlasDialog(
            title = "Delete this activity?",
            onDismiss = { pendingDelete = null },
            confirmText = "Delete",
            onConfirm = { viewModel.deleteActivity(act.id); pendingDelete = null },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("“${act.title.ifBlank { KINDS.firstOrNull { it.slug == act.kind }?.label ?: "This activity" }}” is removed from your activity log.") },
        )
    }
}

@Composable
private fun KindGrid(selected: String, onSelect: (String) -> Unit) {
    val c = Atlas.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KINDS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val on = k.slug == selected
                    Panel(
                        Modifier.weight(1f),
                        tone = if (on) PanelTone.Accent else PanelTone.Raised,
                        padding = PaddingValues(vertical = 16.dp),
                        onClick = { onSelect(k.slug) },
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(k.icon, null, tint = if (on) c.accent else c.textMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(k.label, style = MaterialTheme.typography.labelLarge, color = if (on) c.accent else c.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartButton(onClick: () -> Unit) {
    val c = Atlas.colors
    val t = rememberInfiniteTransition(label = "start")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000)), label = "ring")
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(150.dp).graphicsLayer { scaleX = 0.8f + 0.3f * ring; scaleY = 0.8f + 0.3f * ring; alpha = 1f - ring }
            .clip(SquircleShape(50)).background(c.accent.copy(alpha = 0.3f)))
        Box(
            Modifier.size(132.dp).shadow(24.dp, SquircleShape(50), ambientColor = c.accent, spotColor = c.accent)
                .clip(SquircleShape(50)).background(Brush.linearGradient(listOf(c.accent, c.amber)))
                .tappable(feel = Feel.None, pressedScale = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FiberManualRecord, null, tint = c.onAccent, modifier = Modifier.size(30.dp))
                Text("START", style = MaterialTheme.typography.labelSmall, color = c.onAccent)
            }
        }
    }
}

@Composable
private fun Recording(state: RecordUiState, onBack: () -> Unit, onStop: () -> Unit, onDiscard: () -> Unit) {
    val c = Atlas.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LiveTrackMap(state.track, Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.8f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Back — recording continues", onBack, container = c.glass)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.clip(SquircleShape(50)).background(c.glass).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(c.danger, 8.dp, pulse = true)
                Spacer(Modifier.width(8.dp))
                Text("REC · " + (KINDS.firstOrNull { it.slug == state.session?.kind }?.label ?: "Activity").uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = c.text)
            }
            Spacer(Modifier.weight(1f))
            GpsBadge(state)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(14.dp)) {
            state.error?.let { ErrorNote(it, Modifier.padding(bottom = 10.dp)) }
            Panel(Modifier.fillMaxWidth(), tone = PanelTone.Glass, padding = PaddingValues(20.dp)) {
                Text(formatDuration(state.elapsedMs), style = MaterialTheme.typography.displayLarge, color = c.text,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatBlock(formatKm(state.distanceM), "distance", unit = "km", color = c.accent)
                    StatBlock(formatSpeed(state.currentSpeedMps ?: 0f), "speed", unit = "km/h")
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatBlock(formatSpeed(state.avgSpeedMps), "avg km/h", small = true)
                    StatBlock(formatSpeed(state.maxSpeedMps), "max km/h", small = true)
                    StatBlock(formatDuration(state.movingMs), "moving", small = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AtlasButton("Discard", onDiscard, Modifier.weight(1f), kind = ButtonKind.Secondary)
                    AtlasButton("Stop & save", onStop, Modifier.weight(1.6f), icon = Icons.Rounded.Stop, loading = state.saving, feel = Feel.None)
                }
            }
        }
    }
}

/** GPS quality up front: a weak fix explains a wobbly line before anyone wonders. */
@Composable
private fun GpsBadge(state: RecordUiState) {
    val c = Atlas.colors
    val acc = state.gpsAccuracyM
    val (label, tone) = when {
        acc == null -> "Finding GPS" to c.warning
        acc <= 10f -> "GPS ±${acc.roundToInt()} m" to c.success
        acc <= 25f -> "GPS ±${acc.roundToInt()} m" to c.amber
        else -> "Weak ±${acc.roundToInt()} m" to c.danger
    }
    Row(Modifier.clip(SquircleShape(50)).background(c.glass).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(tone, 7.dp, pulse = acc == null)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.text)
    }
}

/**
 * The live track on its own MapView (not MapViewModel's retained one). One
 * persistent Polyline appended to as fixes arrive — rebuilding it per fix was
 * O(n²) over a ride. The camera follows the newest point until the user pans;
 * the recentre button resumes following.
 */
@Composable
private fun LiveTrackMap(track: List<TrackPoint>, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    var follow by remember { mutableStateOf(true) }
    val mapView = rememberAtlasMapView(interactive = true)
    val lineColor = c.accent.toArgb()
    val haloColor = c.accent.copy(alpha = 0.3f).toArgb()
    val halo = remember {
        Polyline(mapView).apply {
            outlinePaint.color = haloColor; outlinePaint.strokeWidth = 26f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            mapView.overlays.add(this)
        }
    }
    val line = remember {
        Polyline(mapView).apply {
            outlinePaint.color = lineColor; outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            mapView.overlays.add(this)
        }
    }
    var drawn by remember { mutableIntStateOf(0) }
    DisposableEffect(mapView) {
        mapView.controller.setZoom(16.0)
        // A drag means the user wants to look around; stop yanking the camera back.
        @Suppress("ClickableViewAccessibility")
        mapView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) follow = false
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }
    LaunchedEffect(track.size, follow) {
        if (track.size < drawn) { line.setPoints(emptyList()); halo.setPoints(emptyList()); drawn = 0 }
        if (track.isEmpty()) return@LaunchedEffect
        val first = drawn == 0
        for (i in drawn until track.size) {
            val p = GeoPoint(track[i].lat, track[i].lng)
            line.addPoint(p); halo.addPoint(p)
        }
        drawn = track.size
        val last = GeoPoint(track.last().lat, track.last().lng)
        if (first) mapView.controller.setCenter(last) else if (follow) mapView.controller.animateTo(last)
        mapView.invalidate()
    }
    Box(modifier) {
        MapViewHost(mapView, Modifier.fillMaxSize())
        if (!follow) {
            IconAction(
                Icons.Rounded.MyLocation, "Follow my position", { follow = true },
                Modifier.align(Alignment.CenterEnd).padding(14.dp), container = c.glass, tint = c.accent,
            )
        }
    }
}

@Composable
private fun ActivityRow(act: ActivityDto, onDelete: () -> Unit) {
    val c = Atlas.colors
    val k = KINDS.firstOrNull { it.slug == act.kind } ?: KINDS.last()
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(k.icon, tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(act.title.ifBlank { k.label }, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(
                    buildString {
                        append(act.start.take(10))
                        // Null until the server computes stats: an activity can be saved
                        // before its points finish uploading — say so, not a confident zero.
                        if (act.computedAt == null) append(" · waiting for points")
                        else append(" · ${String.format(Locale.US, "%.2f km", act.distanceKm ?: 0.0)} · ${formatDuration((act.movingSeconds ?: 0) * 1000L)}")
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
            IconAction(Icons.Rounded.DeleteOutline, "Delete", onDelete, container = null, tint = c.textMuted, size = 38.dp, iconSize = 18.dp)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatKm(metres: Double): String = String.format(Locale.US, "%.2f", metres / 1000.0)

private fun formatSpeed(mps: Float): String =
    ((mps * 3.6f) * 10).roundToInt().let { String.format(Locale.US, "%.1f", it / 10f) }
