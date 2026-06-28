package com.roamly.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import com.roamly.ui.search.SearchScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.roamly.data.api.LocationPoint
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onNavigateToDate: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showTimeMenu by remember { mutableStateOf(false) }

    val holder = remember(viewModel) {
        viewModel.mapHolder ?: run {
            val appContext = context.applicationContext
            Configuration.getInstance().load(appContext, appContext.getSharedPreferences("osmdroid", 0))
            Configuration.getInstance().userAgentValue = "Roamly/1.0"
            Configuration.getInstance().osmdroidBasePath = appContext.cacheDir
            Configuration.getInstance().osmdroidTileCache = java.io.File(appContext.cacheDir, "osmdroid_tiles").apply { mkdirs() }
            val heatmap = HeatmapOverlay()
            val points = PointsOverlay()
            val mv = MapView(appContext).apply {
                setDestroyMode(false)
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(10.0)
                controller.setCenter(GeoPoint(20.0, 0.0))
                overlays.add(heatmap)
                overlays.add(points)
            }
            MapHolder(mv, heatmap, points).also { viewModel.mapHolder = it }
        }
    }
    val mapView = holder.mapView
    val heatmapOverlay = holder.heatmap
    val pointsOverlay = holder.points

    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                emit(); return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                emit(); return false
            }
            private fun emit() {
                val bbox = mapView.boundingBox ?: return
                viewModel.onViewportChanged(
                    zoom = mapView.zoomLevelDouble,
                    minLat = bbox.latSouth, maxLat = bbox.latNorth,
                    minLng = bbox.lonWest, maxLng = bbox.lonEast,
                )
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    var nearHereResult by remember { mutableStateOf<NearHereResult?>(null) }
    var showAllDays by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var checkingHere by remember { mutableStateOf(false) }
    var nearHereError by remember { mutableStateOf<String?>(null) }
    var tappedPoint by remember { mutableStateOf<LocationPoint?>(null) }

    // Wire the overlay tap callback back to Compose state
    LaunchedEffect(pointsOverlay) {
        pointsOverlay.onPointTapped = { pt -> tappedPoint = pt }
    }

    val startNearHere = {
        checkingHere = true
        nearHereError = null
        checkNearHere(context, viewModel) { result ->
            checkingHere = false
            if (result != null) nearHereResult = result
            else nearHereError = "couldn't get your current location — try again outdoors"
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) startNearHere()
    }

    var didAutoFit by remember { mutableStateOf(false) }
    LaunchedEffect(state.locations) {
        heatmapOverlay.setPoints(state.locations)
        pointsOverlay.setPoints(state.locations)
        if (!didAutoFit && state.focus == null && state.locations.isNotEmpty()) {
            val geoPoints = state.locations.map { GeoPoint(it.lat, it.lng) }
            val fit = {
                if (geoPoints.size == 1) {
                    mapView.controller.animateTo(geoPoints.first())
                    mapView.controller.setZoom(13.0)
                } else {
                    val bbox = BoundingBox.fromGeoPoints(geoPoints)
                    mapView.zoomToBoundingBox(bbox, false, 96)
                }
            }
            if (mapView.width > 0 && mapView.height > 0) fit() else mapView.post { fit() }
            didAutoFit = true
        }
        mapView.invalidate()
    }
    LaunchedEffect(state.timePeriod, state.customDateRange) { didAutoFit = false }

    LaunchedEffect(state.focus?.key) {
        state.focus?.let {
            mapView.controller.setZoom(it.zoom)
            mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        mapView.post {
            mapView.invalidate()
            mapView.controller.setZoom(mapView.zoomLevelDouble)
        }
        onDispose { mapView.onPause() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Top-left time period chip
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
        ) {
            TextButton(onClick = { showTimeMenu = true }) {
                Icon(
                    if (state.timePeriod == TimePeriod.CUSTOM) Icons.Rounded.DateRange else Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(state.periodLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showTimeMenu,
                onDismissRequest = { showTimeMenu = false },
                shape = RoundedCornerShape(16.dp),
                offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp),
            ) {
                TimePeriod.entries.forEach { period ->
                    DropdownMenuItem(
                        text = { Text(if (period == TimePeriod.CUSTOM) "Custom range…" else period.label) },
                        leadingIcon = if (period == TimePeriod.CUSTOM) {
                            { Icon(Icons.Rounded.DateRange, null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        onClick = {
                            showTimeMenu = false
                            viewModel.setTimePeriod(period)
                        }
                    )
                }
            }
        }

        // Top-right search button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
        ) {
            IconButton(onClick = { showSearch = true }) {
                Icon(Icons.Rounded.Search, contentDescription = "search", tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (state.isLoadingMore) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Loading detail…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Right-side zoom controls
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ZoomFab(icon = Icons.Rounded.Add, desc = "Zoom in") { mapView.controller.zoomIn() }
            ZoomFab(icon = Icons.Rounded.Remove, desc = "Zoom out") { mapView.controller.zoomOut() }
        }

        // "Have I been here?" chip
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (state.stats != null) 108.dp else 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) startNearHere()
                    else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Have I been here?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth())
        }

        // Bottom stats strip
        state.stats?.let { stats ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatChip("${stats.totalPoints}", "points")
                StatChip("${stats.cities}", "cities")
                StatChip("${stats.countries}", "countries")
            }
        }

        state.error?.let {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
    }

    // Date range picker dialog
    if (state.showDateRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = viewModel::dismissDateRangePicker,
            confirmButton = {
                TextButton(
                    onClick = {
                        val startMs = pickerState.selectedStartDateMillis
                        val endMs = pickerState.selectedEndDateMillis
                        if (startMs != null) {
                            val zone = ZoneId.systemDefault()
                            val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
                            val end = if (endMs != null) Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate() else start
                            viewModel.setCustomDateRange(DateRange(start, end))
                        } else {
                            viewModel.dismissDateRangePicker()
                        }
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDateRangePicker) { Text("Cancel") } },
        ) {
            DateRangePicker(
                state = pickerState,
                modifier = Modifier
                    .wrapContentHeight()
                    .padding(top = 16.dp),
            )
        }
    }

    if (showSearch) {
        SearchScreen(
            onClose = { showSearch = false },
            onPlaceClick = { lat, lng ->
                viewModel.focusOn(lat, lng, zoom = 16.0)
                showSearch = false
            },
            onDayClick = { day ->
                viewModel.navigateToDate(day.date)
                showSearch = false
            },
        )
    }

    if (checkingHere) {
        AlertDialog(
            onDismissRequest = { checkingHere = false },
            title = { Text("Have you been here?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Checking your history at this spot…")
                }
            },
            confirmButton = {},
        )
    }

    nearHereError?.let { msg ->
        AlertDialog(
            onDismissRequest = { nearHereError = null },
            title = { Text("Have you been here?") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { nearHereError = null }) { Text("OK") } },
        )
    }

    nearHereResult?.let { result ->
        if (showAllDays) {
            AllDaysScreen(
                result = result,
                onClose = { showAllDays = false; nearHereResult = null },
                onBack = { showAllDays = false },
                onDayClick = { day ->
                    // Navigate to that date on the map
                    viewModel.navigateToDate(day.date.toString(), day.lat, day.lng)
                    showAllDays = false
                    nearHereResult = null
                }
            )
        } else {
            NearHereDialog(
                result = result,
                onDismiss = { nearHereResult = null },
                onShowAll = { showAllDays = true },
                onDayClick = { day ->
                    viewModel.navigateToDate(day.date.toString(), day.lat, day.lng)
                    nearHereResult = null
                }
            )
        }
    }

    tappedPoint?.let { pt ->
        PointDetailDialog(
            pt = pt,
            onDismiss = { tappedPoint = null },
            onDelete = {
                val id = pt.id
                tappedPoint = null
                viewModel.deletePoint(id) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Point deleted" else "Couldn't delete point",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun PointDetailDialog(pt: LocationPoint, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · HH:mm:ss") }
    val timeStr = remember(pt.timestamp) {
        runCatching {
            java.time.OffsetDateTime.parse(pt.timestamp)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(fmt)
        }.getOrDefault(pt.timestamp)
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmingDelete) "Delete this point?" else "Location point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                val location = listOfNotNull(pt.city, pt.state, pt.country).joinToString(", ")
                if (location.isNotBlank()) InfoLine("Location", location)
                InfoLine("Coordinates", "${"%.5f".format(pt.lat)}, ${"%.5f".format(pt.lng)}")
                pt.speed?.let { InfoLine("Speed", speedLabel(it)) }
                pt.accuracy?.let { InfoLine("Accuracy", "${it.toInt()} m") }
                pt.altitude?.let { InfoLine("Altitude", "${it.toInt()} m") }
                pt.battery?.let { InfoLine("Battery", "${it.toInt()}%") }
                if (confirmingDelete) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "This permanently removes this GPS point. It can't be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (confirmingDelete) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (confirmingDelete) {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            } else {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun speedLabel(mps: Double): String {
    val kmh = mps * 3.6
    return "%.1f km/h".format(kmh)
}

@Composable
private fun ZoomFab(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Near-here dialog ----

data class NearHereDay(
    val date: LocalDate,
    val pointCount: Int,
    val closestMeters: Double,
    val lat: Double,
    val lng: Double,
    val timeSpentSeconds: Int = 0,
)

data class NearHereResult(
    val region: String?,
    val veryCloseDays: Int,
    val totalRegionPoints: Int,
    val closestMeters: Double?,
    val days: List<NearHereDay>,
)

private const val PREVIEW_DAYS = 3

@Composable
private fun NearHereDialog(
    result: NearHereResult,
    onDismiss: () -> Unit,
    onShowAll: () -> Unit,
    onDayClick: (NearHereDay) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Have you been here?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    result.days.isNotEmpty() -> {
                        val closest = result.closestMeters?.let { "${it.toInt()} m away" } ?: ""
                        ResultHeader(
                            "Yes — you've been right here",
                            "${result.veryCloseDays} day${if (result.veryCloseDays != 1) "s" else ""}${if (closest.isNotEmpty()) " · closest $closest" else ""}",
                            MaterialTheme.colorScheme.secondary,
                        )
                    }
                    result.totalRegionPoints > 0 ->
                        ResultHeader(
                            "In this area, but not this exact spot",
                            "${result.totalRegionPoints} past points in ${result.region ?: "this region"}",
                            MaterialTheme.colorScheme.primary,
                        )
                    else ->
                        ResultHeader("First time here", "No past points nearby", MaterialTheme.colorScheme.onSurface)
                }

                result.region?.let {
                    Text("Region: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (result.days.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Past visits — tap to go there", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        result.days.take(PREVIEW_DAYS).forEach { day ->
                            DayRow(day, onClick = { onDayClick(day) })
                        }
                    }
                    if (result.days.size > PREVIEW_DAYS) {
                        TextButton(onClick = onShowAll, modifier = Modifier.fillMaxWidth()) {
                            Text("Show all ${result.days.size} visits")
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AllDaysScreen(
    result: NearHereResult,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onDayClick: (NearHereDay) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("All visits to this spot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${result.days.size} days${result.region?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(result.days) { day ->
                    DayRow(day, onClick = { onDayClick(day) })
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(title: String, sub: String, tint: ComposeColor) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayRow(day: NearHereDay, onClick: () -> Unit) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d, yyyy") }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(day.date.format(fmt), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val sub = buildString {
                    append("${day.closestMeters.toInt()} m · ${day.pointCount} pts")
                    if (day.timeSpentSeconds > 0) append(" · ${formatDuration(day.timeSpentSeconds)}")
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}

// ---- Heatmap overlay ----

/** Holds the single reusable MapView and its overlays. */
internal class MapHolder(
    val mapView: MapView,
    val heatmap: HeatmapOverlay,
    val points: PointsOverlay,
) {
    fun detach() { runCatching { mapView.onDetach() } }
}

internal class HeatmapOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private var sprite: android.graphics.Bitmap? = null

    fun setPoints(p: List<LocationPoint>) { points = p }

    private fun ensureSprite() {
        if (sprite != null) return
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val grad = android.graphics.RadialGradient(
            cx, cx, cx,
            intArrayOf(Color.argb(200, 255, 80, 60), Color.argb(120, 255, 170, 0), Color.argb(0, 255, 255, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
        c.drawCircle(cx, cx, cx, p)
        sprite = bmp
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        ensureSprite()
        val s = sprite ?: return
        val zoom = mapView.zoomLevelDouble
        val alphaFrac = when {
            zoom >= 15 -> 0.04f
            zoom >= 13 -> 0.16f
            zoom >= 11 -> 0.50f
            else -> 0.70f
        }
        val spriteSize = when {
            zoom < 6 -> 26f
            zoom < 10 -> 44f
            zoom < 13 -> 64f
            else -> 80f
        }
        val half = spriteSize / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (alphaFrac * 255).toInt() }
        val src = android.graphics.Rect(0, 0, s.width, s.height)
        val dst = android.graphics.RectF()
        val projection: Projection = mapView.projection
        val out = android.graphics.Point()
        val viewW = mapView.width; val viewH = mapView.height
        for (pt in points) {
            projection.toPixels(GeoPoint(pt.lat, pt.lng), out)
            if (out.x < -spriteSize || out.x > viewW + spriteSize || out.y < -spriteSize || out.y > viewH + spriteSize) continue
            dst.set(out.x - half, out.y - half, out.x + half, out.y + half)
            canvas.drawBitmap(s, src, dst, paint)
        }
    }
}

// ---- Speed-graded coloured dots; larger and more visible at detail zoom ----

internal class PointsOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255)
    }
    var onPointTapped: ((LocationPoint) -> Unit)? = null

    fun setPoints(p: List<LocationPoint>) { points = p }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        if (mapView.zoomLevelDouble < 11.0 || points.isEmpty()) return false
        val projection = mapView.projection
        val tapX = e.x; val tapY = e.y
        val tapRadiusPx = 40f  // generous tap target
        var bestDist = Float.MAX_VALUE
        var bestPt: LocationPoint? = null
        val out = android.graphics.Point()
        for (pt in points) {
            projection.toPixels(GeoPoint(pt.lat, pt.lng), out)
            val dx = out.x - tapX; val dy = out.y - tapY
            val dist = dx * dx + dy * dy
            if (dist < tapRadiusPx * tapRadiusPx && dist < bestDist) {
                bestDist = dist
                bestPt = pt
            }
        }
        bestPt?.let { onPointTapped?.invoke(it) }
        return bestPt != null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        if (zoom < 11.0) return
        val alphaFrac = when {
            zoom >= 13 -> 1.0f
            zoom >= 12 -> 0.75f
            else -> 0.4f
        }
        val projection: Projection = mapView.projection
        val radius = when {
            zoom < 12 -> 3.5f
            zoom < 13 -> 5.0f
            zoom < 15 -> 7.0f
            else -> 9.0f
        }
        strokePaint.strokeWidth = (radius * 0.35f).coerceAtLeast(1.5f)
        val out = android.graphics.Point()
        val viewW = mapView.width; val viewH = mapView.height
        for (p in points) {
            projection.toPixels(GeoPoint(p.lat, p.lng), out)
            if (out.x < -20 || out.x > viewW + 20 || out.y < -20 || out.y > viewH + 20) continue
            val c = speedColor(p.speed)
            fillPaint.color = Color.argb((Color.alpha(c) * alphaFrac).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawCircle(out.x.toFloat(), out.y.toFloat(), radius, fillPaint)
            canvas.drawCircle(out.x.toFloat(), out.y.toFloat(), radius, strokePaint)
        }
    }
}

/** speed in m/s — matches the website's green→amber→red gradient */
private fun speedColor(speed: Double?): Int {
    val s = speed ?: 0.0
    return when {
        s < 1.0 -> Color.rgb(59, 130, 246)
        s < 5.0 -> Color.rgb(34, 197, 94)
        s < 14.0 -> Color.rgb(234, 179, 8)
        s < 30.0 -> Color.rgb(249, 115, 22)
        else -> Color.rgb(239, 68, 68)
    }
}

// ---- Have-I-been-here computation ----

private fun checkNearHere(
    context: android.content.Context,
    viewModel: MapViewModel,
    onResult: (NearHereResult?) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val compute = { lat: Double, lng: Double ->
        viewModel.checkHaveIBeenHere(lat, lng) { onResult(it) }
    }
    try {
        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) {
                    compute(last.latitude, last.longitude)
                } else {
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null,
                    ).addOnSuccessListener { cur ->
                        if (cur != null) compute(cur.latitude, cur.longitude) else onResult(null)
                    }.addOnFailureListener { onResult(null) }
                }
            }
            .addOnFailureListener { onResult(null) }
    } catch (_: SecurityException) {
        onResult(null)
    }
}

private const val SAME_SPOT_METERS = 200.0
private const val REGION_KM = 5.0

internal fun buildNearHere(
    userLat: Double,
    userLng: Double,
    locations: List<LocationPoint>,
): NearHereResult {
    val today = LocalDate.now()

    var regionCity: String? = null
    var regionState: String? = null
    var nearestPastDistKm = Double.MAX_VALUE
    for (pt in locations) {
        val day = parseDate(pt.timestamp)
        if (day == today) continue
        val distKm = haversineKm(userLat, userLng, pt.lat, pt.lng)
        if (distKm < nearestPastDistKm) {
            nearestPastDistKm = distKm
            regionCity = pt.city?.takeIf { it.isNotBlank() }
            regionState = pt.state?.takeIf { it.isNotBlank() }
        }
    }
    val regionLabel = listOfNotNull(regionCity, regionState).joinToString(", ").ifBlank { null }

    val regionPoints = locations.filter { pt ->
        val day = parseDate(pt.timestamp)
        if (day == today) return@filter false
        if (regionCity != null && !pt.city.isNullOrBlank()) {
            pt.city.equals(regionCity, ignoreCase = true)
        } else {
            haversineKm(userLat, userLng, pt.lat, pt.lng) <= REGION_KM
        }
    }

    data class DayAgg(
        var count: Int = 0,
        var bestDistM: Double = Double.MAX_VALUE,
        var lat: Double = 0.0,
        var lng: Double = 0.0,
        val timestamps: MutableList<Long> = mutableListOf(),
    )
    val byDay = HashMap<LocalDate, DayAgg>()
    var globalClosestM = Double.MAX_VALUE

    for (pt in regionPoints) {
        val distM = haversineKm(userLat, userLng, pt.lat, pt.lng) * 1000.0
        if (distM > SAME_SPOT_METERS) continue
        if (distM < globalClosestM) globalClosestM = distM
        val day = parseDate(pt.timestamp) ?: continue
        val tsMs = parseIsoMs(pt.timestamp) ?: continue
        val agg = byDay.getOrPut(day) { DayAgg() }
        agg.count++
        agg.timestamps.add(tsMs)
        if (distM < agg.bestDistM) {
            agg.bestDistM = distM
            agg.lat = pt.lat
            agg.lng = pt.lng
        }
    }

    val days = byDay.entries
        .sortedByDescending { it.key }
        .map { (date, agg) ->
            val timeSpent = computeTimeSpentSeconds(agg.timestamps)
            NearHereDay(
                date = date,
                pointCount = agg.count,
                closestMeters = agg.bestDistM,
                lat = agg.lat,
                lng = agg.lng,
                timeSpentSeconds = timeSpent,
            )
        }

    return NearHereResult(
        region = regionLabel,
        veryCloseDays = days.size,
        totalRegionPoints = regionPoints.size,
        closestMeters = if (globalClosestM == Double.MAX_VALUE) null else globalClosestM,
        days = days,
    )
}

private fun computeTimeSpentSeconds(timestampsMs: List<Long>): Int {
    if (timestampsMs.size < 2) return 0
    val sorted = timestampsMs.sorted()
    var totalMs = 0L
    for (i in 1 until sorted.size) {
        val gap = sorted[i] - sorted[i - 1]
        totalMs += minOf(gap, 3_600_000L)  // cap each gap at 1 hour like the server does
    }
    return (totalMs / 1000L).toInt()
}

private fun parseIsoMs(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(ts).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(ts).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { null }
    }
}

private fun parseDate(ts: String?): LocalDate? {
    if (ts.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(ts).toLocalDate()
        } catch (_: DateTimeParseException) {
            try { LocalDate.parse(ts.take(10)) } catch (_: DateTimeParseException) { null }
        }
    }
}

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
