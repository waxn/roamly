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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
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

    val pointsOverlay = remember { PointsOverlay() }

    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
            overlays.add(pointsOverlay)
        }
    }

    var nearHereResult by remember { mutableStateOf<NearHereResult?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) checkNearHere(context, state.locations) { nearHereResult = it }
    }

    // Apply new points + auto-fit when not following a focus
    SideEffect {
        pointsOverlay.setPoints(state.locations)
        if (state.focus == null && state.locations.isNotEmpty()) {
            val geoPoints = state.locations.map { GeoPoint(it.lat, it.lng) }
            if (geoPoints.size == 1) {
                mapView.controller.animateTo(geoPoints.first())
                mapView.controller.setZoom(13.0)
            } else {
                val bbox = BoundingBox.fromGeoPoints(geoPoints)
                mapView.zoomToBoundingBox(bbox, false, 96)
            }
        }
        mapView.invalidate()
    }

    // Honor focus jumps (from past-visit taps)
    LaunchedEffect(state.focus?.key) {
        state.focus?.let {
            mapView.controller.setZoom(it.zoom)
            mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Top-center time period chip
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 6.dp,
            tonalElevation = 0.dp,
        ) {
            Box {
                TextButton(onClick = { showTimeMenu = true }) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.timePeriod.label, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
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
        }

        if (state.detailLimited) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "showing reduced points",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = viewModel::loadAllPoints, enabled = !state.isLoadingMore) {
                        Text(if (state.isLoadingMore) "loading..." else "load all")
                    }
                }
            }
        }

        // Right-side zoom controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ZoomFab(icon = Icons.Filled.Add, desc = "Zoom in") { mapView.controller.zoomIn() }
            ZoomFab(icon = Icons.Filled.Remove, desc = "Zoom out") { mapView.controller.zoomOut() }
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
                    checkNearHere(context, state.locations) { nearHereResult = it }
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
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = if (state.stats != null) 120.dp else 16.dp),
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
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 8.dp,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatChip("${stats.totalPoints}", "points")
                    StatChip("${stats.cities}", "cities")
                    StatChip("${stats.countries}", "countries")
                }
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

    nearHereResult?.let { result ->
        NearHereDialog(
            result = result,
            onDismiss = { nearHereResult = null },
            onDayClick = { day ->
                viewModel.focusOn(day.lat, day.lng, zoom = 15.0)
                nearHereResult = null
            }
        )
    }
}

@Composable
private fun ZoomFab(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Near-here dialog ----

data class NearHereDay(
    val date: LocalDate,
    val pointCount: Int,
    val lat: Double,
    val lng: Double,
    val city: String?,
)

data class NearHereResult(
    val withinOneKm: Int,
    val withinFiveKm: Int,
    val withinTwentyFiveKm: Int,
    val nearestCity: String?,
    val days: List<NearHereDay>,
)

@Composable
private fun NearHereDialog(
    result: NearHereResult,
    onDismiss: () -> Unit,
    onDayClick: (NearHereDay) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("have you been here?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    result.withinOneKm > 0 ->
                        ResultHeader("yes — within 1 km", "${result.withinOneKm} point${if (result.withinOneKm != 1) "s" else ""} recorded", MaterialTheme.colorScheme.secondary)
                    result.withinFiveKm > 0 ->
                        ResultHeader("yes — within 5 km", "${result.withinFiveKm} point${if (result.withinFiveKm != 1) "s" else ""} recorded", MaterialTheme.colorScheme.secondary)
                    result.withinTwentyFiveKm > 0 ->
                        ResultHeader("nearby — within 25 km", "${result.withinTwentyFiveKm} point${if (result.withinTwentyFiveKm != 1) "s" else ""} recorded", MaterialTheme.colorScheme.primary)
                    else -> ResultHeader("no records within 25 km", "of your current location", MaterialTheme.colorScheme.onSurface)
                }

                result.nearestCity?.let {
                    Text(
                        "nearest logged: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (result.days.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "past visits (tap to view)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(result.days) { day ->
                            DayRow(day, onClick = { onDayClick(day) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close") } }
    )
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
                val sub = listOfNotNull(day.city, "${day.pointCount} pts").joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Custom overlay: speed-graded coloured dots ----

private class PointsOverlay : Overlay() {
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
        val projection: Projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        val radius = when {
            zoom < 6 -> 2.2f
            zoom < 10 -> 3.0f
            zoom < 13 -> 4.0f
            zoom < 15 -> 5.0f
            else -> 6.5f
        }
        val out = android.graphics.Point()
        val viewW = mapView.width
        val viewH = mapView.height
        for (p in points) {
            projection.toPixels(GeoPoint(p.lat, p.lng), out)
            if (out.x < -20 || out.x > viewW + 20 || out.y < -20 || out.y > viewH + 20) continue
            fillPaint.color = speedColor(p.speed)
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
    locations: List<LocationPoint>,
    onResult: (NearHereResult) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    try {
        client.lastLocation.addOnSuccessListener { location ->
            if (location == null) return@addOnSuccessListener
            onResult(buildNearHere(location.latitude, location.longitude, locations))
        }
    } catch (_: SecurityException) { /* permission not granted */ }
}

internal fun buildNearHere(
    userLat: Double,
    userLng: Double,
    locations: List<LocationPoint>,
): NearHereResult {
    val today = LocalDate.now()

    var within1 = 0
    var within5 = 0
    var within25 = 0
    var nearestCity: String? = null
    var smallestDist = Double.MAX_VALUE

    // Aggregate past-day visits within 5km, excluding today's points
    data class DayAgg(
        var count: Int = 0,
        var bestDist: Double = Double.MAX_VALUE,
        var lat: Double = 0.0,
        var lng: Double = 0.0,
        var city: String? = null,
    )
    val byDay = HashMap<LocalDate, DayAgg>()

    for (pt in locations) {
        val dist = haversineKm(userLat, userLng, pt.lat, pt.lng)
        if (dist < smallestDist) {
            smallestDist = dist
            nearestCity = pt.city
        }
        if (dist <= 1.0) within1++
        if (dist <= 5.0) within5++
        if (dist <= 25.0) within25++

        if (dist <= 5.0) {
            val day = parseDate(pt.timestamp) ?: continue
            if (day == today) continue  // exclude today
            val agg = byDay.getOrPut(day) { DayAgg() }
            agg.count++
            if (dist < agg.bestDist) {
                agg.bestDist = dist
                agg.lat = pt.lat
                agg.lng = pt.lng
                agg.city = pt.city ?: agg.city
            }
        }
    }

    val days = byDay.entries
        .sortedByDescending { it.key }
        .map { (date, agg) ->
            NearHereDay(date = date, pointCount = agg.count, lat = agg.lat, lng = agg.lng, city = agg.city)
        }

    // Recompute "within" counts excluding today as well, so we don't say "yes" purely
    // because the user is standing here right now.
    var w1NoToday = 0; var w5NoToday = 0; var w25NoToday = 0
    for (pt in locations) {
        val day = parseDate(pt.timestamp) ?: continue
        if (day == today) continue
        val dist = haversineKm(userLat, userLng, pt.lat, pt.lng)
        if (dist <= 1.0) w1NoToday++
        if (dist <= 5.0) w5NoToday++
        if (dist <= 25.0) w25NoToday++
    }

    return NearHereResult(
        withinOneKm = w1NoToday,
        withinFiveKm = w5NoToday,
        withinTwentyFiveKm = w25NoToday,
        nearestCity = nearestCity,
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
