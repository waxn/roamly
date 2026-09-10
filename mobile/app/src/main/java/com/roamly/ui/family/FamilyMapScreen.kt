package com.roamly.ui.family

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roamly.data.api.FamilyLocationsResponse
import com.roamly.ui.trips.MEMBER_TRACK_COLORS
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** The family live map — one dot (+ a short recent trail) per sharing, accepted
 *  member. Reuses the existing osmdroid pattern (JournalsScreen's day-track
 *  card, TripDetailScreen's member tracks) rather than inventing a new one,
 *  and the same MEMBER_TRACK_COLORS palette Adventure member tracks use. */
@Composable
fun FamilyMapContent(state: FamilyUiState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val locations = state.locations

    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            controller.setZoom(13.0)
        }
    }

    LaunchedEffect(locations) {
        mapView.overlays.clear()
        val fitPoints = mutableListOf<GeoPoint>()
        locations?.members?.forEachIndexed { idx, member ->
            val latest = member.latest ?: return@forEachIndexed
            val color = MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size]
            val here = GeoPoint(latest.lat, latest.lng)
            fitPoints += here

            if (member.trail.size >= 2) {
                val geo = member.trail.map { p -> GeoPoint(p[1], p[0]) } // [lng, lat] -> GeoPoint(lat,lng)
                mapView.overlays.add(Polyline(mapView).apply {
                    setPoints(geo)
                    outlinePaint.color = color
                    outlinePaint.alpha = 140
                    outlinePaint.strokeWidth = 5f
                    outlinePaint.isAntiAlias = true
                    infoWindow = null
                })
            }

            val sizePx = with(density) { 22.dp.toPx() }.toInt()
            val dot = ShapeDrawable(OvalShape()).apply {
                paint.color = color
                intrinsicWidth = sizePx
                intrinsicHeight = sizePx
                setBounds(0, 0, sizePx, sizePx)
            }
            mapView.overlays.add(Marker(mapView).apply {
                position = here
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = if (member.isYou) "You" else member.displayName
                snippet = "Last seen ${relativeTimeLabel(latest.timestamp)}"
            })
        }
        mapView.invalidate()
        if (fitPoints.isNotEmpty()) {
            mapView.post {
                runCatching {
                    if (fitPoints.size == 1) mapView.controller.setCenter(fitPoints[0])
                    else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(fitPoints), false, 64)
                }
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize(),
        )

        FilledIconButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Icon(Icons.Rounded.Refresh, "Refresh") }

        if (locations != null && locations.members.none { it.latest != null }) {
            Card(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(
                    "No one in this circle has shared a location yet.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A short, dependency-free "Xm ago" / "Xh ago" label from an ISO-8601 timestamp.
 *  Django's datetime.isoformat() emits a "+00:00" offset, not "Z" — parsed via
 *  OffsetDateTime rather than Instant.parse, which is stricter about that. */
private fun relativeTimeLabel(iso: String): String {
    return runCatching {
        val instant = java.time.OffsetDateTime.parse(iso).toInstant()
        val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds.coerceAtLeast(0)
        when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }.getOrDefault("")
}
