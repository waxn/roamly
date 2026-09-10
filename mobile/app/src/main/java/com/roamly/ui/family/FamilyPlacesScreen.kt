package com.roamly.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roamly.data.api.FamilyPlaceItem
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay

/** Self-serve shared places: any accepted circle member can create one and
 *  configure their own enter/exit alerts. CustomPlace has no mobile UI at
 *  all today, so this whole surface is new rather than a port. */
@Composable
fun FamilyPlacesContent(viewModel: FamilyViewModel, state: FamilyUiState) {
    var showAddPlace by remember { mutableStateOf(false) }
    var selectedPlace by remember { mutableStateOf<FamilyPlaceItem?>(null) }

    if (showAddPlace) {
        val center = state.locations?.members?.firstOrNull { it.isYou }?.latest?.let { it.lat to it.lng }
        AddPlaceScreen(
            initialCenter = center,
            onCancel = { showAddPlace = false },
            onSave = { name, lat, lng, radius ->
                viewModel.createPlace(name, lat, lng, radius) { _, _ -> showAddPlace = false }
            },
        )
        return
    }

    selectedPlace?.let { place ->
        PlaceDetailScreen(place = place, viewModel = viewModel, onBack = { selectedPlace = null })
        return
    }

    val places = state.selectedCircle?.places.orEmpty()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (places.isEmpty()) {
                item {
                    Text(
                        "No places yet. Add one everyone in the circle can get alerts for — like home, school or work.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(places) { place ->
                ClayCard(onClick = { selectedPlace = place }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(parsePlaceColor(place.color)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${place.radiusM.toInt()}m radius",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }
        ClayButton(
            onClick = { showAddPlace = true },
            gradient = Clay.colors.secondaryGradient,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add place")
        }
    }
}

private fun parsePlaceColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFE8763D))

@Composable
private fun PlaceDetailScreen(place: FamilyPlaceItem, viewModel: FamilyViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var onEnter by remember { mutableStateOf(true) }
    var onExit by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(place.id) {
        when (val r = viewModel.getAlerts(place.id)) {
            is com.roamly.data.repository.Result.Success -> {
                onEnter = r.data.onEnter; onExit = r.data.onExit
            }
            is com.roamly.data.repository.Result.Error -> {}
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(place.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
        )
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ClayCard {
                Text("My alerts", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "On by default for every circle member — turn either off just for yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify when someone arrives")
                    Switch(checked = onEnter, enabled = loaded, onCheckedChange = { checked ->
                        onEnter = checked
                        scope.launch { viewModel.setAlerts(place.id, checked, null) { _, _ -> } }
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify when someone leaves")
                    Switch(checked = onExit, enabled = loaded, onCheckedChange = { checked ->
                        onExit = checked
                        scope.launch { viewModel.setAlerts(place.id, null, checked) { _, _ -> } }
                    })
                }
            }

            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("Delete place")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${place.name}\"?") },
            text = { Text("This removes it for everyone in the circle, along with everyone's alerts for it.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlace(place.id) { ok, err ->
                        if (ok) onBack() else status = err
                    }
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

/** Tap-to-place-a-pin, the whole point being that a non-technical family
 *  member never has to type coordinates. Centers on the caller's own last
 *  shared location when available. */
@Composable
private fun AddPlaceScreen(
    initialCenter: Pair<Double, Double>?,
    onCancel: () -> Unit,
    onSave: (name: String, lat: Double, lng: Double, radiusM: Double) -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(initialCenter?.let { GeoPoint(it.first, it.second) }) }
    var name by remember { mutableStateOf("") }
    var radiusIndex by remember { mutableIntStateOf(1) }
    val radiusOptions = listOf(75.0, 150.0, 300.0, 500.0)

    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            controller.setZoom(if (initialCenter != null) 15.0 else 3.0)
            controller.setCenter(picked ?: GeoPoint(20.0, 0.0))
        }
    }
    var marker by remember { mutableStateOf<Marker?>(null) }

    fun placeMarker(point: GeoPoint) {
        picked = point
        marker?.let { mapView.overlays.remove(it) }
        val m = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(m)
        marker = m
        mapView.invalidate()
    }

    LaunchedEffect(mapView) {
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { placeMarker(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        picked?.let { placeMarker(it) }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add place") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") } },
        )
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
                modifier = Modifier.fillMaxSize(),
            )
            if (picked == null) {
                Card(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    Text("Tap the map to drop a pin", modifier = Modifier.padding(10.dp))
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. \"School\"") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Radius", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                radiusOptions.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = radiusIndex == i,
                        onClick = { radiusIndex = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = radiusOptions.size),
                    ) { Text("${r.toInt()}m") }
                }
            }
            ClayButton(
                onClick = { picked?.let { onSave(name.trim(), it.latitude, it.longitude, radiusOptions[radiusIndex]) } },
                gradient = Clay.colors.secondaryGradient,
                enabled = name.isNotBlank() && picked != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save place") }
        }
    }
}
