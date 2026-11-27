package com.roamly.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.ActivityDto
import com.roamly.data.api.RoamlyApi
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.Skeleton
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.addGlowLine
import com.roamly.ui.kit.fitTo
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale
import javax.inject.Inject

data class ActivityDetailState(
    val activity: ActivityDto? = null,
    val segments: List<List<GeoPoint>> = emptyList(),
    /** Chart series, flattened across segments: elapsed seconds from the start, km/h, metres. */
    val chartTimes: List<Double> = emptyList(),
    val chartSpeeds: List<Double?> = emptyList(),
    val chartElev: List<Double?> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(private val api: RoamlyApi) : ViewModel() {
    private val _state = MutableStateFlow(ActivityDetailState())
    val state: StateFlow<ActivityDetailState> = _state.asStateFlow()
    private var loadedId = -1

    fun load(id: Int) {
        if (loadedId == id) return
        loadedId = id
        _state.value = ActivityDetailState()
        viewModelScope.launch {
            val act = runCatching { api.getActivity(id).body()?.activity }.getOrNull()
            _state.update { it.copy(activity = act, loading = act == null && it.segments.isEmpty()) }
            val track = runCatching { api.getActivityTrack(id).body() }.getOrNull()
            val segs = track?.segments.orEmpty().map { seg ->
                seg.filter { it.size >= 2 }.map { GeoPoint(it[0], it[1]) }
            }.filter { it.size >= 2 }
            val ch = track?.chart
            val times = ch?.times.orEmpty().flatten().filterNotNull()
            val t0 = times.firstOrNull() ?: 0.0
            val speeds = ch?.speeds.orEmpty().flatten()
            val elev = ch?.elevations.orEmpty().flatten()
            val usable = speeds.size == times.size
            _state.update {
                it.copy(
                    chartTimes = if (usable) times.map { t -> t - t0 } else emptyList(),
                    chartSpeeds = if (usable) speeds else emptyList(),
                    chartElev = if (usable && elev.size == times.size) elev else emptyList(),
                    segments = segs, loading = false,
                    error = if (act == null && segs.isEmpty()) "Couldn't load this activity." else null,
                )
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = loadedId
        viewModelScope.launch {
            runCatching { api.deleteActivity(id) }
            RecordViewModel.clearRecentCache()
            onDone()
        }
    }
}

@Composable
fun ActivityDetailScreen(id: Int, onBack: () -> Unit, viewModel: ActivityDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(id) { viewModel.load(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    val c = Atlas.colors
    val act = state.activity
    val kind = KINDS.firstOrNull { it.slug == act?.kind }?.label ?: "Activity"

    PushedScreen(
        title = act?.title?.ifBlank { null } ?: kind,
        subtitle = act?.let { shortDate(it.start) },
        onBack = onBack,
        actions = {
            if (act != null) IconAction(Icons.Rounded.DeleteOutline, "Delete", { confirmDelete = true }, container = c.surfaceHigh)
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.error?.let { ErrorNote(it) }
            Box(Modifier.fillMaxWidth().height(300.dp).clip(SquircleShape(SquircleRadius.panel))) {
                when {
                    state.segments.isNotEmpty() -> ActivityMap(state.segments)
                    state.loading -> Skeleton(Modifier.fillMaxSize(), SquircleShape(SquircleRadius.panel))
                    else -> Box(Modifier.fillMaxSize().background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                        Text("No track to show", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    }
                }
            }
            if (act != null) {
                Panel(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        StatBlock(String.format(Locale.US, "%.2f", act.distanceKm ?: 0.0), "distance", unit = "km", color = c.accent)
                        StatBlock(formatDuration((act.movingSeconds ?: act.elapsedSeconds) * 1000L), "moving")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        StatBlock(String.format(Locale.US, "%.1f", act.avgSpeedKmh ?: 0.0), "avg km/h", small = true)
                        StatBlock(String.format(Locale.US, "%.1f", act.maxSpeedKmh ?: 0.0), "max km/h", small = true)
                        StatBlock(String.format(Locale.US, "%.0f", act.elevationGainM ?: 0.0), "climb m", small = true)
                    }
                }
                Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        StatBlock(formatDuration(act.elapsedSeconds * 1000L), "elapsed", small = true)
                        StatBlock(String.format(Locale.US, "%.0f", act.elevationLossM ?: 0.0), "descent m", small = true)
                        StatBlock("%,d".format(act.pointCount), "points", small = true)
                    }
                }
                if (state.chartSpeeds.count { it != null } > 1) {
                    ChartPanel("Speed", state.chartTimes, state.chartSpeeds, "km/h", c.accent, floorZero = true)
                }
                if (state.chartElev.count { it != null } > 1) {
                    ChartPanel("Elevation", state.chartTimes, state.chartElev, "m", c.lime, floorZero = false)
                }
                if (act.notes.isNotBlank()) {
                    Panel(Modifier.fillMaxWidth()) { Text(act.notes, style = MaterialTheme.typography.bodyMedium, color = c.text) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AtlasDialog(
            title = "Delete this activity?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete(onBack) },
            destructive = true,
            icon = Icons.Rounded.DeleteOutline,
            body = { Text("It is removed from your activity log.") },
        )
    }
}

@Composable
private fun ActivityMap(segments: List<List<GeoPoint>>) {
    val mapView = rememberAtlasMapView(interactive = true)
    val color = Atlas.colors.accent
    LaunchedEffect(segments, color) {
        mapView.overlays.clear()
        segments.forEach { mapView.addGlowLine(it, color, width = 8f) }
        mapView.fitTo(segments.flatten())
    }
    MapViewHost(mapView, Modifier.fillMaxSize())
}

/** A value-over-time chart you can drag a finger along to read each moment. */
@Composable
private fun ChartPanel(title: String, times: List<Double>, values: List<Double?>, unit: String, color: androidx.compose.ui.graphics.Color, floorZero: Boolean) {
    val c = Atlas.colors
    var sel by remember(values) { mutableStateOf(-1) }
    val present = remember(values) { values.filterNotNull() }
    val lo = if (floorZero) 0.0 else (present.minOrNull() ?: 0.0)
    val hi = (present.maxOrNull() ?: 1.0).coerceAtLeast(lo + 1e-6)
    val total = (times.lastOrNull() ?: 0.0).coerceAtLeast(1.0)
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        val v = values.getOrNull(sel)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = c.text)
            Text(
                if (v != null) String.format(Locale.US, "%.1f %s · %s", v, unit, formatDuration((times[sel] * 1000).toLong()))
                else String.format(Locale.US, "up to %.0f %s · drag to read", hi, unit),
                style = MaterialTheme.typography.labelMedium, color = if (v != null) c.accent else c.textMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .pointerInput(values) {
                    fun at(x: Float): Int {
                        val t = (x / size.width).coerceIn(0f, 1f) * total
                        val i = times.binarySearch(t)
                        return (if (i >= 0) i else -i - 1).coerceIn(0, times.lastIndex)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { o -> sel = at(o.x) },
                        onDragEnd = { sel = -1 }, onDragCancel = { sel = -1 },
                        onHorizontalDrag = { ch, _ -> sel = at(ch.position.x) },
                    )
                },
        ) {
            val n = values.size
            if (n < 2) return@Canvas
            fun x(i: Int) = (times[i] / total).toFloat() * size.width
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * (size.height - 4.dp.toPx()) - 2.dp.toPx()
            val line = androidx.compose.ui.graphics.Path()
            val area = androidx.compose.ui.graphics.Path()
            var started = false
            var lastX = 0f
            for (i in 0 until n) {
                val vi = values[i] ?: continue
                val px = x(i); val py = y(vi)
                if (!started) { line.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py); started = true }
                else { line.lineTo(px, py); area.lineTo(px, py) }
                lastX = px
            }
            area.lineTo(lastX, size.height); area.close()
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            drawPath(area, color.copy(alpha = 0.16f))
            drawPath(line, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            val sv = values.getOrNull(sel)
            if (sv != null) {
                val sx = x(sel)
                drawLine(c.textMuted, Offset(sx, 0f), Offset(sx, size.height), 1.dp.toPx())
                drawCircle(color, 5.dp.toPx(), Offset(sx, y(sv)))
            }
        }
    }
}
