package com.roamly.ui.record

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
import androidx.compose.ui.Modifier
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
            _state.update {
                it.copy(
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
                if (state.segments.isEmpty()) Skeleton(Modifier.fillMaxSize(), SquircleShape(SquircleRadius.panel))
                else ActivityMap(state.segments)
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
