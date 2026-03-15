package com.roamly.ui.map

import android.graphics.Paint
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            update = { mv ->
                if (state.locations.isNotEmpty()) {
                    mv.overlays.clear()
                    val sorted = state.locations.sortedBy { it.timestamp }
                    val points = sorted.map { GeoPoint(it.lat, it.lng) }
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = android.graphics.Color.parseColor("#1A73E8")
                        outlinePaint.strokeWidth = 4f
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    }
                    mv.overlays.add(polyline)
                    val last = sorted.last()
                    mv.controller.animateTo(GeoPoint(last.lat, last.lng))
                    mv.controller.setZoom(12.0)
                    mv.invalidate()
                }
            },
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

        // Zoom buttons
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

        // Loading indicator
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
