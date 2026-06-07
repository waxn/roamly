package com.roamly.ui.map

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import com.roamly.ui.search.SearchScreen
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.claySoftShadow
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

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showTimeMenu by remember { mutableStateOf(false) }

    // Reuse a single MapView for the life of the ViewModel (survives tab
    // switches). Creating a new one per revisit reopens osmdroid's shared tile
    // cache and crashes when a stale instance closes it on GC. Built with the
    // application context so the VM-retained View never leaks the Activity.
    val holder = remember(viewModel) {
        viewModel.mapHolder ?: run {
            val appContext = context.applicationContext
            Configuration.getInstance().load(appContext, appContext.getSharedPreferences("osmdroid", 0))
            Configuration.getInstance().userAgentValue = "Roamly/1.0"
            // Pin osmdroid's tile cache to app-private storage so it never depends on
            // external-storage permission (which would crash on re-entry/older devices).
            Configuration.getInstance().osmdroidBasePath = appContext.cacheDir
            Configuration.getInstance().osmdroidTileCache = java.io.File(appContext.cacheDir, "osmdroid_tiles").apply { mkdirs() }
            val heatmap = HeatmapOverlay()
            val points = PointsOverlay()
            val mv = MapView(appContext).apply {
                // CRITICAL for tab reuse: osmdroid's onDetachedFromWindow() calls
                // onDetach() when destroyMode is true (the default), which destroys
                // the tile provider AND overlays the moment we navigate away — so
                // the retained MapView came back as a blank grid with no points.
                // Turning it off keeps the view alive across tab switches; we still
                // tear it down manually in the ViewModel's onCleared().
                setDestroyMode(false)
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(10.0)
                controller.setCenter(GeoPoint(20.0, 0.0))
                // heatmap below, dots on top
                overlays.add(heatmap)
                overlays.add(points)
            }
            MapHolder(mv, heatmap, points).also { viewModel.mapHolder = it }
        }
    }
    val mapView = holder.mapView
    val heatmapOverlay = holder.heatmap
    val pointsOverlay = holder.points

    // Listen for pan/zoom and trigger debounced bbox loads
    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                emit()
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                emit()
                return false
            }
            private fun emit() {
                val bbox = mapView.boundingBox ?: return
                viewModel.onViewportChanged(
                    zoom = mapView.zoomLevelDouble,
                    minLat = bbox.latSouth,
                    maxLat = bbox.latNorth,
                    minLng = bbox.lonWest,
                    maxLng = bbox.lonEast,
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

    // Apply new points to both overlays + auto-fit when not following a focus.
    // Keyed on the data (not a bare SideEffect) so it only rebuilds the overlay
    // geometry when locations actually change — a SideEffect here re-pushed the
    // whole point list and invalidated the map on every recomposition (e.g. during
    // navigation transitions), hammering the main thread.
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
            // zoomToBoundingBox divides by the MapView's pixel size, so it crashes if
            // run before the view is laid out (0×0). With the disk cache, locations can
            // be ready on the very first composition — before layout — so defer the fit
            // to after measurement when the view has no size yet.
            if (mapView.width > 0 && mapView.height > 0) fit() else mapView.post { fit() }
            didAutoFit = true
        }
        mapView.invalidate()
    }
    // Reset auto-fit when the time period (and thus dataset) changes
    LaunchedEffect(state.timePeriod) { didAutoFit = false }

    // Honor focus jumps (from past-visit taps)
    LaunchedEffect(state.focus?.key) {
        state.focus?.let {
            mapView.controller.setZoom(it.zoom)
            mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        // After a tab switch the retained MapView is re-parented; nudge it to
        // re-request tiles for the visible area so it doesn't come back as a blank
        // grid. A post() ensures it runs after the view is laid out.
        mapView.post {
            mapView.invalidate()
            mapView.controller.setZoom(mapView.zoomLevelDouble)
        }
        onDispose {
            // Only pause on leave. Calling onDetach() here tears down osmdroid's
            // shared tile cache; the next time this screen is entered a fresh
            // MapView reopens it and crashes ("attempt to re-open an already-closed
            // object"). Pausing avoids that and the View is GC'd normally.
            mapView.onPause()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            // The MapView is reused across visits, so detach it from a previous
            // container before this AndroidView re-parents it.
            factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Top-left time period chip
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 12.dp)
                .claySoftShadow(20.dp, Clay.colors.shadowDark, Clay.colors.shadowLight, depth = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Clay.colors.surfaceTop, Clay.colors.surfaceBottom)))
        ) {
            TextButton(onClick = { showTimeMenu = true }) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(state.timePeriod.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = showTimeMenu, onDismissRequest = { showTimeMenu = false }) {
                TimePeriod.entries.forEach { period ->
                    DropdownMenuItem(
                        text = { Text(period.label) },
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
                .claySoftShadow(20.dp, Clay.colors.shadowDark, Clay.colors.shadowLight, depth = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Clay.colors.surfaceTop, Clay.colors.surfaceBottom)))
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
                    .claySoftShadow(14.dp, Clay.colors.shadowDark, Clay.colors.shadowLight, depth = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Clay.colors.surfaceTop, Clay.colors.surfaceBottom)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "loading detail…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Right-side zoom controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ZoomFab(icon = Icons.Rounded.Add, desc = "Zoom in") { mapView.controller.zoomIn() }
            ZoomFab(icon = Icons.Rounded.Remove, desc = "Zoom out") { mapView.controller.zoomOut() }
        }

        // "Have I been here?" action
        ExtendedFloatingActionButton(
            onClick = {
                val hasFine = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    startNearHere()
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            },
            text = { Text("have i been here?") },
            icon = {
                Icon(
                Icons.Rounded.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (state.stats != null) 120.dp else 16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )

        if (state.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )
        }

        // Bottom stats strip
        state.stats?.let { stats ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .claySoftShadow(22.dp, Clay.colors.shadowDark, Clay.colors.shadowLight, depth = 16.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Clay.colors.surfaceTop, Clay.colors.surfaceBottom)))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
    }

    if (showSearch) {
        SearchScreen(
            onClose = { showSearch = false },
            onPlaceClick = { lat, lng ->
                viewModel.focusOn(lat, lng, zoom = 16.0)
                showSearch = false
            },
        )
    }

    if (checkingHere) {
        AlertDialog(
            onDismissRequest = { checkingHere = false },
            title = { Text("have you been here?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("checking your history at this spot…")
                }
            },
            confirmButton = {},
        )
    }

    nearHereError?.let { msg ->
        AlertDialog(
            onDismissRequest = { nearHereError = null },
            title = { Text("have you been here?") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { nearHereError = null }) { Text("ok") } },
        )
    }

    nearHereResult?.let { result ->
        if (showAllDays) {
            AllDaysScreen(
                result = result,
                onClose = { showAllDays = false; nearHereResult = null },
                onBack = { showAllDays = false },
                onDayClick = { day ->
                    viewModel.focusOn(day.lat, day.lng, zoom = 17.0)
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
                    viewModel.focusOn(day.lat, day.lng, zoom = 17.0)
                    nearHereResult = null
                }
            )
        }
    }
}

@Composable
private fun ZoomFab(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .claySoftShadow(14.dp, Clay.colors.shadowDark, Clay.colors.shadowLight, depth = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Clay.colors.surfaceTop, Clay.colors.surfaceBottom))),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
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
)

data class NearHereResult(
    val region: String?,        // e.g. "Portland, OR" — city, state of the user's current area
    val veryCloseDays: Int,     // # of distinct past days with a point within ~150m
    val totalRegionPoints: Int, // # of all past points in that region
    val closestMeters: Double?, // closest past point in meters
    val days: List<NearHereDay>,// past days with a near-exact match (~300m), newest first
)

private const val PREVIEW_DAYS = 4

@Composable
private fun NearHereDialog(
    result: NearHereResult,
    onDismiss: () -> Unit,
    onShowAll: () -> Unit,
    onDayClick: (NearHereDay) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("have you been here?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    result.days.isNotEmpty() -> {
                        val closest = result.closestMeters?.let { "${it.toInt()} m away" } ?: ""
                        ResultHeader(
                            "yes — you've been right here",
                            "${result.veryCloseDays} day${if (result.veryCloseDays != 1) "s" else ""}${if (closest.isNotEmpty()) " · closest $closest" else ""}",
                            MaterialTheme.colorScheme.secondary,
                        )
                    }
                    result.totalRegionPoints > 0 ->
                        ResultHeader(
                            "in this area, but not this exact spot",
                            "${result.totalRegionPoints} past points in ${result.region ?: "this region"}",
                            MaterialTheme.colorScheme.primary,
                        )
                    else ->
                        ResultHeader("first time here", "no past points nearby", MaterialTheme.colorScheme.onSurface)
                }

                result.region?.let {
                    Text(
                        "region: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (result.days.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "past visits to this spot",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        result.days.take(PREVIEW_DAYS).forEach { day ->
                            DayRow(day, onClick = { onDayClick(day) })
                        }
                    }
                    if (result.days.size > PREVIEW_DAYS) {
                        TextButton(
                            onClick = onShowAll,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("show all ${result.days.size} days")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close") } }
    )
}

@Composable
private fun AllDaysScreen(
    result: NearHereResult,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onDayClick: (NearHereDay) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("all visits to this spot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${result.days.size} days${result.region?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("close") }
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(day.date.format(fmt), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${day.closestMeters.toInt()} m · ${day.pointCount} pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Heatmap overlay: translucent radial sprites accumulating into a heat blob ----

/** Holds the single reusable MapView and its overlays so the ViewModel can
 *  retain them across navigation and detach the map cleanly when destroyed. */
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
            intArrayOf(Color.argb(220, 255, 80, 60), Color.argb(140, 255, 170, 0), Color.argb(0, 255, 255, 0)),
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
        // Fade out the heatmap when the user zooms in to detail level
        val alphaFrac = when {
            zoom >= 15 -> 0.05f
            zoom >= 13 -> 0.18f
            zoom >= 11 -> 0.55f
            else -> 0.75f
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
        val viewW = mapView.width
        val viewH = mapView.height
        for (pt in points) {
            projection.toPixels(GeoPoint(pt.lat, pt.lng), out)
            if (out.x < -spriteSize || out.x > viewW + spriteSize || out.y < -spriteSize || out.y > viewH + spriteSize) continue
            dst.set(out.x - half, out.y - half, out.x + half, out.y + half)
            canvas.drawBitmap(s, src, dst, paint)
        }
    }
}

// ---- Speed-graded coloured dots; fades out at low zoom so the heatmap leads ----

internal class PointsOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(180, 255, 255, 255)
    }

    fun setPoints(p: List<LocationPoint>) {
        points = p
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        if (zoom < 11.0) return  // heatmap-only territory
        val alphaFrac = when {
            zoom >= 13 -> 1.0f
            zoom >= 12 -> 0.7f
            else -> 0.35f
        }
        val projection: Projection = mapView.projection
        val radius = when {
            zoom < 12 -> 2.5f
            zoom < 13 -> 3.5f
            zoom < 15 -> 5.0f
            else -> 6.5f
        }
        val out = android.graphics.Point()
        val viewW = mapView.width
        val viewH = mapView.height
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
        s < 1.0 -> Color.rgb(59, 130, 246)      // stationary: blue
        s < 5.0 -> Color.rgb(34, 197, 94)       // walking: green
        s < 14.0 -> Color.rgb(234, 179, 8)      // cycling/jogging: amber
        s < 30.0 -> Color.rgb(249, 115, 22)     // driving slow: orange
        else -> Color.rgb(239, 68, 68)          // fast: red
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
        // lastLocation is frequently null (esp. right after boot / fresh install),
        // so fall back to an active fix before giving up.
        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) {
                    compute(last.latitude, last.longitude)
                } else {
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        null,
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

// "Right here" — same store / same building / same spot
private const val SAME_SPOT_METERS = 200.0
// "In this region" — same neighbourhood / town area
private const val REGION_KM = 5.0

internal fun buildNearHere(
    userLat: Double,
    userLng: Double,
    locations: List<LocationPoint>,
): NearHereResult {
    val today = LocalDate.now()

    // 1) Find the user's current region by picking the city/state of the
    //    nearest past point (excluding today). The city field is already
    //    reverse-geocoded server-side.
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

    // 2) Filter to points in that region (by city match) OR fall back to
    //    everything within REGION_KM of the user if no city match.
    val regionPoints = locations.filter { pt ->
        val day = parseDate(pt.timestamp)
        if (day == today) return@filter false
        if (regionCity != null && !pt.city.isNullOrBlank()) {
            pt.city.equals(regionCity, ignoreCase = true)
        } else {
            haversineKm(userLat, userLng, pt.lat, pt.lng) <= REGION_KM
        }
    }

    // 3) Within those region points, find ones close enough to count as
    //    "same spot" and aggregate by day.
    data class DayAgg(
        var count: Int = 0,
        var bestDistM: Double = Double.MAX_VALUE,
        var lat: Double = 0.0,
        var lng: Double = 0.0,
    )
    val byDay = HashMap<LocalDate, DayAgg>()
    var globalClosestM = Double.MAX_VALUE

    for (pt in regionPoints) {
        val distM = haversineKm(userLat, userLng, pt.lat, pt.lng) * 1000.0
        if (distM > SAME_SPOT_METERS) continue
        if (distM < globalClosestM) globalClosestM = distM
        val day = parseDate(pt.timestamp) ?: continue
        val agg = byDay.getOrPut(day) { DayAgg() }
        agg.count++
        if (distM < agg.bestDistM) {
            agg.bestDistM = distM
            agg.lat = pt.lat
            agg.lng = pt.lng
        }
    }

    val days = byDay.entries
        .sortedByDescending { it.key }
        .map { (date, agg) ->
            NearHereDay(
                date = date,
                pointCount = agg.count,
                closestMeters = agg.bestDistM,
                lat = agg.lat,
                lng = agg.lng,
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

private fun parseDate(ts: String?): LocalDate? {
    if (ts.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(ts).toLocalDate()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(ts.take(10))
            } catch (_: DateTimeParseException) { null }
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
