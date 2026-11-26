package com.roamly.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MEMBER_TRACK_COLORS
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.avatarMarker
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.fitTo
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The family live map: an initial-lettered marker (and a short recent trail)
 * per sharing, accepted member, in the same per-member palette Adventure
 * tracks use. Under it, a strip of people; tap one to fly to them.
 */
@Composable
fun FamilyMapContent(state: FamilyUiState, onRefresh: () -> Unit) {
    val c = Atlas.colors
    val context = LocalContext.current
    val locations = state.locations
    val mapView = rememberAtlasMapView(interactive = true)
    val members = locations?.members.orEmpty()

    LaunchedEffect(locations) {
        mapView.overlays.clear()
        val fit = mutableListOf<GeoPoint>()
        members.forEachIndexed { idx, member ->
            val latest = member.latest ?: return@forEachIndexed
            val color = MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size]
            val here = GeoPoint(latest.lat, latest.lng)
            fit += here
            if (member.trail.size >= 2) {
                mapView.overlays.add(Polyline(mapView).apply {
                    setPoints(member.trail.map { p -> GeoPoint(p[1], p[0]) }) // [lng, lat]
                    outlinePaint.color = color
                    outlinePaint.alpha = 150
                    outlinePaint.strokeWidth = 7f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                    infoWindow = null
                })
            }
            mapView.overlays.add(Marker(mapView).apply {
                position = here
                icon = avatarMarker(context, color, (if (member.isYou) "You" else member.displayName).take(1).uppercase())
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = if (member.isYou) "You" else member.displayName
                snippet = "Last seen ${relativeTimeLabel(latest.timestamp)}"
            })
        }
        mapView.invalidate()
        if (fit.isNotEmpty()) mapView.fitTo(fit, 96)
    }

    Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = dockAwareBottom(12.dp)).clip(SquircleShape(SquircleRadius.panel))) {
        MapViewHost(mapView, Modifier.fillMaxSize())
        IconAction(Icons.Rounded.Refresh, "Refresh", onRefresh, Modifier.align(Alignment.TopEnd).padding(12.dp), container = c.glass, bordered = true)

        if (locations != null && members.none { it.latest != null }) {
            Panel(Modifier.align(Alignment.Center).padding(24.dp), tone = PanelTone.Glass) {
                Text("No one in this circle has shared a location yet.", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        if (members.any { it.latest != null }) {
            LazyRow(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(members) { idx, m ->
                    val latest = m.latest ?: return@itemsIndexed
                    val color = Color(MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size])
                    Row(
                        Modifier.clip(SquircleShape(50)).background(c.glass)
                            .tappable(pressedScale = 0.94f) {
                                mapView.controller.animateTo(GeoPoint(latest.lat, latest.lng), 16.0, 600L)
                            }
                            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(SquircleShape(50)).background(color), contentAlignment = Alignment.Center) {
                            Text((if (m.isYou) "You" else m.displayName).take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(if (m.isYou) "You" else m.displayName, style = MaterialTheme.typography.labelLarge, color = c.text)
                            Text(
                                relativeTimeLabel(latest.timestamp) + (latest.battery?.let { " · ${it.toInt()}%" } ?: ""),
                                style = MaterialTheme.typography.labelMedium, color = c.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Xm ago" / "Xh ago" from an ISO-8601 timestamp. Django emits a "+00:00"
 *  offset rather than "Z", hence OffsetDateTime rather than Instant.parse. */
internal fun relativeTimeLabel(iso: String): String = runCatching {
    val instant = java.time.OffsetDateTime.parse(iso).toInstant()
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds.coerceAtLeast(0)
    when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}.getOrDefault("")
