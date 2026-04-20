package com.roamly.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.roamly.data.api.LocationPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val StreetsTiles = XYTileSource(
    "RoamlyStreets",
    1,
    19,
    256,
    ".png",
    arrayOf("https://basemaps.cartocdn.com/light_all/")
)

private val DarkTiles = XYTileSource(
    "RoamlyDark",
    1,
    19,
    256,
    ".png",
    arrayOf("https://basemaps.cartocdn.com/dark_all/")
)

private val SatelliteTiles = XYTileSource(
    "RoamlySatellite",
    1,
    19,
    256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
        val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
        val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$y/$x$imageFilenameEnding"
    }
}

private fun tileSourceFor(layer: MapLayer) = when (layer) {
    MapLayer.STREETS -> StreetsTiles
    MapLayer.DARK -> DarkTiles
    MapLayer.SATELLITE -> SatelliteTiles
}

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        MapView(context).apply {
            setTileSource(tileSourceFor(state.mapLayer))
            setMultiTouchControls(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    // Near-here dialog state
    var nearHereResult by remember { mutableStateOf<NearHereResult?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) checkNearHere(context, state.locations) { nearHereResult = it }
    }

    // Update map overlays whenever locations change (SideEffect runs after every recomposition)
    SideEffect {
        mapView.setTileSource(tileSourceFor(state.mapLayer))
        mapView.overlays.clear()
        if (state.locations.isNotEmpty()) {
            val sorted = state.locations.sortedBy { it.timestamp }
            val points = sorted.map { GeoPoint(it.lat, it.lng) }
            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = android.graphics.Color.parseColor("#3B82F6")
                outlinePaint.strokeWidth = 4f
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            mapView.overlays.add(polyline)
            if (points.size == 1) {
                mapView.controller.animateTo(points.first())
                mapView.controller.setZoom(13.0)
            } else {
                val bbox = BoundingBox.fromGeoPoints(points)
                mapView.zoomToBoundingBox(bbox, true, 96)
            }
        }
        mapView.invalidate()
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

        // Time period chips
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TimePeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.timePeriod == period,
                        onClick = { viewModel.setTimePeriod(period) },
                        label = { Text(period.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                MapLayer.entries.forEach { layer ->
                    FilterChip(
                        selected = state.mapLayer == layer,
                        onClick = { viewModel.setMapLayer(layer) },
                        label = { Text(layer.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        // Right-side controls: zoom + near-here
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Card(elevation = CardDefaults.cardElevation(4.dp)) {
                IconButton(
                    onClick = { mapView.controller.zoomIn() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
            }
            Card(elevation = CardDefaults.cardElevation(4.dp)) {
                IconButton(
                    onClick = { mapView.controller.zoomOut() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
            }
        }

        // "Have I been near here?" action
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
            text = { Text("Have I Been Here?") },
            icon = {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (state.stats != null) 120.dp else 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        )

        // Loading indicator
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Spacer(Modifier.size(8.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }

        // Stats card
        state.stats?.let { stats ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Your Journey", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        StatChip("${stats.totalPoints}", "Points")
                        Spacer(Modifier.width(12.dp))
                        StatChip("${stats.countries}", "Countries")
                        Spacer(Modifier.width(12.dp))
                        StatChip("${stats.cities}", "Cities")
                    }
                }
            }
        }

        // Error
        state.error?.let {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
    }

    // Near-here result dialog
    nearHereResult?.let { result ->
        AlertDialog(
            onDismissRequest = { nearHereResult = null },
            title = { Text("Have you been near here?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        result.withinOneKm > 0 -> {
                            Text("✅ Yes! You've been within 1 km of here.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.secondary)
                            Text("${result.withinOneKm} recorded point${if (result.withinOneKm != 1) "s" else ""} within 1 km",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        result.withinFiveKm > 0 -> {
                            Text("✅ Yes! You've been within 5 km of here.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.secondary)
                            Text("${result.withinFiveKm} point${if (result.withinFiveKm != 1) "s" else ""} within 5 km",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        result.withinTwentyFiveKm > 0 -> {
                            Text("↗️ Nearby — you've been within 25 km.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${result.withinTwentyFiveKm} point${if (result.withinTwentyFiveKm != 1) "s" else ""} within 25 km",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text("❌ No records within 25 km of your current location.",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("(Based on ${state.locations.size} loaded points for the selected period)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    result.nearestCity?.let {
                        Text("Nearest logged city: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { nearHereResult = null }) { Text("OK") } }
        )
    }
}

data class NearHereResult(
    val withinOneKm: Int,
    val withinFiveKm: Int,
    val withinTwentyFiveKm: Int,
    val nearestCity: String?,
)

private fun checkNearHere(
    context: android.content.Context,
    locations: List<LocationPoint>,
    onResult: (NearHereResult) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    try {
        client.lastLocation.addOnSuccessListener { location ->
            if (location == null) return@addOnSuccessListener
            val userLat = location.latitude
            val userLng = location.longitude

            var within1 = 0
            var within5 = 0
            var within25 = 0
            var nearestCity: String? = null
            var smallestDist = Double.MAX_VALUE

            for (pt in locations) {
                val dist = haversineKm(userLat, userLng, pt.lat, pt.lng)
                if (dist < smallestDist) {
                    smallestDist = dist
                    nearestCity = pt.city
                }
                if (dist <= 1.0) within1++
                if (dist <= 5.0) within5++
                if (dist <= 25.0) within25++
            }

            onResult(NearHereResult(within1, within5, within25, nearestCity))
        }
    } catch (_: SecurityException) { /* permission not granted */ }
}

/** Haversine great-circle distance in kilometres */
private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
