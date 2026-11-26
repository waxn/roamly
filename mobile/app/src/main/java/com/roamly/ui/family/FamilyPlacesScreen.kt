package com.roamly.ui.family

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roamly.data.api.FamilyPlaceItem
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.RowDivider
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.ToggleRow
import com.roamly.ui.kit.avatarMarker
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

/** Shared places: any accepted member can add one and set their own arrive/leave alerts. */
@Composable
fun FamilyPlacesContent(viewModel: FamilyViewModel, state: FamilyUiState) {
    val c = Atlas.colors
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FamilyPlaceItem?>(null) }

    if (adding) {
        val center = state.locations?.members?.firstOrNull { it.isYou }?.latest?.let { it.lat to it.lng }
        AddPlace(center, onCancel = { adding = false }) { name, lat, lng, radius ->
            viewModel.createPlace(name, lat, lng, radius) { _, _ -> adding = false }
        }
        return
    }
    selected?.let { place -> PlaceDetail(place, viewModel) { selected = null }; return }

    val places = state.selectedCircle?.places.orEmpty()
    Box(Modifier.fillMaxSize()) {
        if (places.isEmpty()) {
            EmptyState(Icons.Rounded.Place, "No places yet",
                body = "Add a place everyone in the circle can get alerts for — like home, school or work.",
                tint = c.amber, action = "Add a place", onAction = { adding = true })
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = dockAwareBottom(90.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(places, key = { it.id }) { place ->
                    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp), onClick = { selected = place }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(SquircleShape(12.dp)).background(placeColor(place.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(14.dp).clip(SquircleShape(50)).background(placeColor(place.color)))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                                Text("${place.radiusM.toInt()} m radius", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                        }
                    }
                }
            }
            AtlasButton("Add place", { adding = true }, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = dockAwareBottom(16.dp)), icon = Icons.Rounded.Add)
        }
    }
}

private fun placeColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFFF7A3D))

@Composable
private fun PlaceDetail(place: FamilyPlaceItem, viewModel: FamilyViewModel, onBack: () -> Unit) {
    val c = Atlas.colors
    var onEnter by remember { mutableStateOf(true) }
    var onExit by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(place.id) {
        when (val r = viewModel.getAlerts(place.id)) {
            is com.roamly.data.repository.Result.Success -> { onEnter = r.data.onEnter; onExit = r.data.onExit }
            is com.roamly.data.repository.Result.Error -> {}
        }
        loaded = true
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Back to places", onBack, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Text(place.name, style = MaterialTheme.typography.titleLarge, color = c.text)
        }
        Box(Modifier.fillMaxWidth().height(180.dp).clip(SquircleShape(SquircleRadius.panel))) { PlaceMap(place) }
        SectionTitle("My alerts")
        Text("On by default for every member — turn either off just for yourself.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        RowGroup {
            ToggleRow("When someone arrives", onEnter, { v -> onEnter = v; viewModel.setAlerts(place.id, v, null) { _, _ -> } },
                icon = Icons.Rounded.Login, iconTint = c.success, enabled = loaded)
            RowDivider()
            ToggleRow("When someone leaves", onExit, { v -> onExit = v; viewModel.setAlerts(place.id, null, v) { _, _ -> } },
                icon = Icons.Rounded.Logout, iconTint = c.amber, enabled = loaded)
        }
        status?.let { ErrorNote(it) }
        AtlasButton("Delete place", { confirmDelete = true }, Modifier.fillMaxWidth(), kind = com.roamly.ui.kit.ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
    }
    if (confirmDelete) {
        AtlasDialog(
            title = "Delete “${place.name}”?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { viewModel.deletePlace(place.id) { ok, err -> if (ok) onBack() else status = err }; confirmDelete = false },
            destructive = true, icon = Icons.Rounded.DeleteOutline,
            body = { Text("This removes it for everyone in the circle, along with everyone's alerts for it.") },
        )
    }
}

@Composable
private fun PlaceMap(place: FamilyPlaceItem) {
    val color = placeColor(place.color)
    val mapView = rememberAtlasMapView(interactive = false)
    LaunchedEffect(place) {
        val center = GeoPoint(place.lat, place.lng)
        mapView.overlays.clear()
        mapView.overlays.add(radiusRing(mapView, center, place.radiusM, color))
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(center)
        mapView.invalidate()
    }
    MapViewHost(mapView, Modifier.fillMaxSize())
}

private fun radiusRing(mapView: MapView, center: GeoPoint, radiusM: Double, color: Color): Polygon =
    Polygon(mapView).apply {
        points = Polygon.pointsAsCircle(center, radiusM)
        fillPaint.color = color.copy(alpha = 0.18f).toArgb()
        outlinePaint.color = color.toArgb()
        outlinePaint.strokeWidth = 4f
        outlinePaint.isAntiAlias = true
        infoWindow = null
    }

/** Tap the map to drop a pin, so nobody has to type coordinates. Centres on
 *  the caller's own last shared location when there is one. */
@Composable
private fun AddPlace(
    initialCenter: Pair<Double, Double>?,
    onCancel: () -> Unit,
    onSave: (name: String, lat: Double, lng: Double, radiusM: Double) -> Unit,
) {
    val c = Atlas.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var picked by remember { mutableStateOf(initialCenter?.let { GeoPoint(it.first, it.second) }) }
    var name by remember { mutableStateOf("") }
    var radiusIndex by remember { mutableIntStateOf(1) }
    val radii = listOf(75.0, 150.0, 300.0, 500.0)
    val mapView = rememberAtlasMapView(interactive = true)
    BackHandler(onBack = onCancel)

    fun redraw() {
        mapView.overlays.removeAll { it is Marker || it is Polygon }
        picked?.let { p ->
            mapView.overlays.add(radiusRing(mapView, p, radii[radiusIndex], c.accent))
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                icon = avatarMarker(context, c.accent.toArgb(), null, 22f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
            })
        }
        mapView.invalidate()
    }
    LaunchedEffect(mapView) {
        mapView.controller.setZoom(if (initialCenter != null) 15.0 else 3.0)
        mapView.controller.setCenter(picked ?: GeoPoint(20.0, 0.0))
        // A plain Overlay + onSingleTapConfirmed, the same tap mechanism MapScreen uses.
        mapView.overlays.add(0, object : Overlay() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent, mv: MapView): Boolean {
                val gp = mv.projection.fromPixels(e.x.toInt(), e.y.toInt())
                picked = GeoPoint(gp.latitude, gp.longitude)
                haptics.tick()
                redraw()
                return true
            }
        })
        redraw()
    }
    LaunchedEffect(radiusIndex) { redraw() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ArrowBack, "Cancel", onCancel, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Text("Add place", style = MaterialTheme.typography.titleLarge, color = c.text)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(SquircleShape(SquircleRadius.panel))) {
            MapViewHost(mapView, Modifier.fillMaxSize())
            if (picked == null) {
                Panel(Modifier.align(Alignment.TopCenter).padding(12.dp), tone = PanelTone.Glass, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Tap the map to drop a pin", style = MaterialTheme.typography.labelLarge, color = c.text)
                }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom(12.dp)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AtlasField(name, { name = it }, label = "Name", placeholder = "School")
            Text("Radius", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            SegmentedSwitch(radii.map { "${it.toInt()} m" }, radiusIndex, { radiusIndex = it }, Modifier.fillMaxWidth())
            AtlasButton("Save place", { picked?.let { onSave(name.trim(), it.latitude, it.longitude, radii[radiusIndex]) } },
                Modifier.fillMaxWidth(), enabled = name.isNotBlank() && picked != null, feel = Feel.Confirm)
        }
    }
}
