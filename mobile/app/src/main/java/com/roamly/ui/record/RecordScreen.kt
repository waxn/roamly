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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.tween
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.LocalDockPadding
import com.roamly.ui.kit.ScreenHeader
import com.roamly.ui.kit.TrackSketch
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

/** One-shot request from the dock's Record button to open the kind picker. */
object RecordLaunch {
    private val _requested = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requested: kotlinx.coroutines.flow.StateFlow<Boolean> = _requested
    fun request() { _requested.value = true }
    fun consume() { _requested.value = false }
}

internal data class Kind(val slug: String, val label: String, val icon: ImageVector)

internal val KINDS = listOf(
    Kind("ride", "Ride", Icons.Rounded.DirectionsBike),
    Kind("run", "Run", Icons.Rounded.DirectionsRun),
    Kind("walk", "Walk", Icons.Rounded.DirectionsWalk),
    Kind("hike", "Hike", Icons.Rounded.Hiking),
    Kind("row", "Row", Icons.Rounded.Rowing),
    Kind("other", "Other", Icons.Rounded.Explore),
)

/**
 * The Activity page. Idle: kind tiles on top, your recent activities as a
 * two-column grid of mini-map cards, and a slim Start bar above the nav. While
 * recording it becomes the live map with the figures and Stop.
 */
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    onOpenActivity: (Int) -> Unit,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var pickKind by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ActivityDto?>(null) }
    val haptics = rememberHaptics()
    // The dock's centre Record button asks for the kind picker.
    val launchRequested by RecordLaunch.requested.collectAsState()
    LaunchedEffect(launchRequested, state.recording) {
        if (!launchRequested) return@LaunchedEffect
        RecordLaunch.consume()
        if (!state.recording) pickKind = true
    }
    // Refresh whenever this page comes back into view (e.g. after deleting from the detail page).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.loadRecent()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    AnimatedContent(state.recording, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "rec") { recording ->
        if (recording) {
            Recording(
                state = state,
                onBack = onBack,
                onStop = { haptics.thud(); viewModel.stop() },
                onDiscard = { confirmDiscard = true },
            )
        } else {
            val dock = LocalDockPadding.current
            Box(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(title = "Activity")
                    state.error?.let { ErrorNote(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                    when {
                        state.recent.isEmpty() && state.loadingRecent -> Box(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = Atlas.colors.textMuted)
                        }
                        state.recent.isEmpty() -> EmptyState(
                            Icons.Rounded.DirectionsRun, "Nothing recorded yet",
                            body = "Press Record, pick what you're doing and go. Your rides, runs and walks will gather here.",
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = dock + 84.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(state.recent, key = { it.id }) { act ->
                                ActivityCard(act, onClick = { onOpenActivity(act.id) }, onLongClick = { pendingDelete = act })
                            }
                        }
                    }
                }
                StartBar(
                    label = "Record an activity",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = dock + 10.dp),
                ) { haptics.thud(); pickKind = true }
            }
        }
    }

    if (pickKind) {
        AtlasSheet(onDismiss = { pickKind = false }, title = "What are you doing?") {
            KindGrid { k -> pickKind = false; haptics.thud(); viewModel.start(k.slug) }
            Spacer(Modifier.height(16.dp))
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

/** Pick the activity: choosing a kind starts recording straight away. */
@Composable
private fun KindGrid(onPick: (Kind) -> Unit) {
    val c = Atlas.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KINDS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Panel(Modifier.weight(1f), tone = PanelTone.Raised, padding = PaddingValues(vertical = 14.dp), onClick = { onPick(k) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(k.icon, null, tint = c.accent, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(k.label, style = MaterialTheme.typography.labelLarge, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

/** The slim Start bar that floats above the nav. */
@Composable
private fun StartBar(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    val shape = SquircleShape(50)
    Row(
        modifier.fillMaxWidth().height(52.dp)
            .shadow(8.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape).background(c.record)
            .tappable(feel = Feel.None, pressedScale = 0.97f, onClick = onClick)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FiberManualRecord, null, tint = c.onRecord, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = c.onRecord)
    }
}

/** A recent activity, like the web's cards: a mini track on top, then what and how far. */
@Composable
private fun ActivityCard(act: ActivityDto, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Atlas.colors
    val k = KINDS.firstOrNull { it.slug == act.kind } ?: KINDS.last()
    val lines = remember(act.thumb) {
        act.thumb.orEmpty().map { decodePolyline(it) to c.accent }.filter { it.first.size >= 2 }
    }
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onClick, onLongClick = onLongClick) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(SquircleShape(topStart = SquircleRadius.panel, topEnd = SquircleRadius.panel))) {
            if (lines.isNotEmpty()) TrackSketch(lines, Modifier.fillMaxSize(), background = c.surfaceHigh)
            else Box(Modifier.fillMaxSize().background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(k.icon, null, tint = c.textFaint, modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(act.title.ifBlank { k.label }, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortDate(act.start), style = MaterialTheme.typography.labelMedium, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            // Null until the server computes stats: say so, not a confident zero.
            Text(
                if (act.computedAt == null) "Waiting for points"
                else String.format(Locale.US, "%.1f km · %s", act.distanceKm ?: 0.0, formatDuration((act.movingSeconds ?: 0) * 1000L)),
                style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1,
            )
        }
    }
}

internal fun shortDate(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}.getOrDefault(iso.take(10))

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

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatKm(metres: Double): String = String.format(Locale.US, "%.2f", metres / 1000.0)

private fun formatSpeed(mps: Float): String =
    ((mps * 3.6f) * 10).roundToInt().let { String.format(Locale.US, "%.1f", it / 10f) }
