package com.roamly.ui.map

import com.roamly.ui.kit.Units
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.LocationPoint
import com.roamly.data.prefs.MapLayers
import com.roamly.notes.QuickNoteLaunch
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasChip
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.Eyebrow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.Foldout
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.InfoPair
import com.roamly.ui.kit.LocalDockPadding
import com.roamly.ui.Routes
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.ToggleRow
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.notes.QuickNotePill
import com.roamly.ui.notes.QuickNoteSheet
import com.roamly.ui.notes.QuickNotesViewModel
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The map: full-bleed, with frosted controls floating over it.
 *
 * Top: the time range (‹ label ›) and, under it, the day's note pill; on the
 * right, map options and refresh. Right edge: zoom. Bottom: a stats card and
 * "Been here?", both clearing the dock.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    /** "What I did today". Null hides the notes pill (e.g. in a preview). */
    notesViewModel: QuickNotesViewModel? = null,
    /** Red-dot state of the Record button (an activity session is live). */
    recording: Boolean = false,
    /** Simple Mode: no Record, no Search, and a sheet with only the way to You. */
    simpleMode: Boolean = false,
    /** Pushes a route (see `Routes`). */
    onOpen: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val dark = Atlas.colors.isDark
    var showOptions by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var focusNoteComposer by remember { mutableStateOf(false) }
    val notesState = notesViewModel?.uiState?.collectAsState()?.value

    // The notes pill follows the map's range, so ‹ › walk the notes with the track.
    val rangeDays = state.rangeDays()
    val noteDay = state.noteDay()
    LaunchedEffect(notesViewModel, rangeDays, noteDay) { notesViewModel?.setRange(rangeDays, noteDay) }
    // The evening reminder was tapped: open straight into writing.
    val noteLaunch by QuickNoteLaunch.requested.collectAsState()
    LaunchedEffect(noteLaunch, notesViewModel) {
        if (noteLaunch && notesViewModel != null) {
            QuickNoteLaunch.consume()
            notesViewModel.refreshToday()
            notesViewModel.edit(LocalDate.now())
            focusNoteComposer = true
            showNotes = true
        }
    }

    // One MapView for the app's lifetime, retained on the (activity-scoped) ViewModel.
    val holder = remember(viewModel) {
        viewModel.mapHolder ?: run {
            val appContext = context.applicationContext
            Configuration.getInstance().load(appContext, appContext.getSharedPreferences("osmdroid", 0))
            Configuration.getInstance().userAgentValue = "Roamly/1.0"
            Configuration.getInstance().osmdroidBasePath = appContext.cacheDir
            Configuration.getInstance().osmdroidTileCache = java.io.File(appContext.cacheDir, "osmdroid_tiles").apply { mkdirs() }
            val density = appContext.resources.displayMetrics.density
            val fog = FogOverlay().apply { isEnabled = false }
            val inferred = InferredOverlay(density).apply { isEnabled = false }
            val heatmap = HeatmapOverlay()
            val points = PointsOverlay()
            val places = PlacesOverlay(density).apply { isEnabled = false }
            val mv = MapView(appContext).apply {
                setDestroyMode(false)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                // We draw our own zoom controls.
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(10.0)
                controller.setCenter(GeoPoint(20.0, 0.0))
                // Fog under the track so the dots stay readable; places on top.
                overlays.add(fog)
                overlays.add(inferred)
                overlays.add(heatmap)
                overlays.add(points)
                overlays.add(places)
            }
            MapHolder(mv, heatmap, points, fog, inferred, places).also { viewModel.mapHolder = it }
        }
    }
    val mapView = holder.mapView

    LaunchedEffect(holder, state.fogCells, state.fogPerDeg) {
        holder.fog.setCells(state.fogCells, state.fogPerDeg); mapView.invalidate()
    }
    LaunchedEffect(holder, state.inferred) { holder.inferred.setBatches(state.inferred); mapView.invalidate() }
    LaunchedEffect(holder, state.places) { holder.places.setPlaces(state.places); mapView.invalidate() }
    LaunchedEffect(holder, state.layers.fog, state.layers.filledGaps, state.layers.places) {
        holder.fog.isEnabled = state.layers.fog
        holder.inferred.isEnabled = state.layers.filledGaps
        holder.places.isEnabled = state.layers.places
        mapView.invalidate()
    }
    // Basemap: re-applied when the pick, the Mapbox token or the theme changes.
    val tileLoading = Atlas.colors.surface.toArgb()
    LaunchedEffect(mapView, state.basemap, state.mapboxToken, dark) {
        mapView.applyBasemap(state.basemap, state.mapboxToken, dark, tileLoading)
    }

    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean { emit(); return false }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean { emit(); return false }
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

    var nearHere by remember { mutableStateOf<NearHereResult?>(null) }
    var checkingHere by remember { mutableStateOf(false) }
    var nearHereError by remember { mutableStateOf<String?>(null) }
    var tappedPoint by remember { mutableStateOf<LocationPoint?>(null) }

    LaunchedEffect(holder.points) {
        holder.points.onPointTapped = { pt -> haptics.click(); tappedPoint = pt }
    }

    val startNearHere = {
        checkingHere = true
        nearHereError = null
        checkNearHere(context, viewModel) { result ->
            checkingHere = false
            if (result != null) { haptics.success(); nearHere = result }
            else { haptics.reject(); nearHereError = "Couldn't get your location — try again outdoors." }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true || p[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startNearHere()
    }
    // Answer ahead of the tap when the permission is already there, so the button is instant.
    LaunchedEffect(Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) runCatching {
            com.roamly.tracking.LocationSource.get(context).lastKnown { l -> if (l != null) viewModel.prewarmNearHere(l.latitude, l.longitude) }
        }
    }
    val askBeenHere = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) startNearHere()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // The track draws the filtered points; the auto-fit frames everything loaded.
    // didAutoFit lives on the ViewModel so coming back to the tab keeps the camera.
    val visible = remember(state.locations, state.layers) { state.visibleLocations() }
    LaunchedEffect(visible) {
        holder.heatmap.setPoints(visible)
        holder.points.setPoints(visible)
        mapView.invalidate()
    }
    LaunchedEffect(state.locations) {
        if (!viewModel.didAutoFit && state.focus == null && state.locations.isNotEmpty()) {
            val pts = state.locations
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
            for (i in pts.indices) {
                val la = pts[i].lat; val lo = pts[i].lng
                if (la < minLat) minLat = la
                if (la > maxLat) maxLat = la
                if (lo < minLng) minLng = lo
                if (lo > maxLng) maxLng = lo
            }
            val fit = {
                if (pts.size == 1) {
                    mapView.controller.animateTo(GeoPoint(minLat, minLng))
                    mapView.controller.setZoom(13.0)
                } else {
                    mapView.zoomToBoundingBox(BoundingBox(maxLat, maxLng, minLat, minLng), false, 120)
                }
            }
            if (mapView.width > 0 && mapView.height > 0) fit() else mapView.post { fit() }
            viewModel.didAutoFit = true
        }
        mapView.invalidate()
    }
    LaunchedEffect(state.focus?.key) {
        state.focus?.let {
            mapView.controller.setZoom(it.zoom)
            mapView.controller.animateTo(GeoPoint(it.lat, it.lng))
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        // requestLayout, not setZoom(current): that fired a ZoomEvent and a full
        // detail query on every return to the tab.
        mapView.post { mapView.invalidate(); mapView.requestLayout() }
        onDispose { mapView.onPause() }
    }

    val c = Atlas.colors
    val dock = LocalDockPadding.current

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize(),
        )
        // A soft scrim under the status bar so the controls read on any basemap.
        Box(
            Modifier.fillMaxWidth().height(140.dp).background(
                Brush.verticalGradient(listOf(c.bg.copy(alpha = if (dark) 0.75f else 0.45f), Color.Transparent))
            )
        )

        // ── Top: search, refresh, preferences; then the range and the day note ──
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(start = 14.dp, top = 10.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (simpleMode) Spacer(Modifier.weight(1f))
                else SearchPill(Modifier.weight(1f)) { haptics.click(); onOpen(Routes.ASK) }
                GlassButton(Icons.Rounded.Refresh, "Refresh map", busy = state.isRefreshing, enabled = !state.isRefreshing) { viewModel.refresh() }
                GlassButton(Icons.Rounded.Tune, "Map preferences") { showOptions = true }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RangePill(
                    modifier = Modifier.weight(1f),
                    label = state.periodLabel,
                    custom = state.timePeriod == TimePeriod.CUSTOM,
                    canBack = state.canStepBack,
                    canForward = state.canStepForward,
                    onStep = { dir -> haptics.tick(); viewModel.stepRange(dir) },
                    onOpen = { showRange = true },
                )
                if (notesState != null && !notesState.hidden) {
                    QuickNotePill(state = notesState, modifier = Modifier.weight(1f), onClick = {
                        notesViewModel?.refreshToday()
                        focusNoteComposer = false
                        showNotes = true
                    })
                }
            }
        }

        // Thin progress line while the period loads, and a chip while detail streams in.
        AnimatedVisibility(state.isLoading, Modifier.align(Alignment.TopCenter).statusBarsPadding(), enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.accent, trackColor = Color.Transparent, strokeCap = StrokeCap.Round,
            )
        }
        AnimatedVisibility(
            state.isLoadingMore,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 118.dp),
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
        ) {
            GlassChip {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.accent)
                Spacer(Modifier.width(8.dp))
                Text("Loading detail", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
        }
        state.error?.let { msg ->
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 164.dp, start = 16.dp, end = 16.dp)) {
                GlassChip { Dot(c.danger); Spacer(Modifier.width(8.dp)); Text(msg, style = MaterialTheme.typography.bodySmall, color = c.text) }
            }
        }

        // ── Right edge: zoom ────────────────────────────────────────────
        ZoomRail(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            onIn = { haptics.tick(); mapView.controller.zoomIn() },
            onOut = { haptics.tick(); mapView.controller.zoomOut() },
        )

        // ── Bottom: been-here, above the sheet's peek (Record lives in the dock) ──
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = dock + 12.dp)) {
            BeenHereButton(checking = checkingHere, onClick = askBeenHere)
        }

        nearHereError?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); nearHereError = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = dock + 130.dp, start = 16.dp, end = 16.dp)) {
                GlassChip { Dot(c.danger); Spacer(Modifier.width(8.dp)); Text(msg, style = MaterialTheme.typography.bodySmall, color = c.text) }
            }
        }

    }

    if (state.showDateRangePicker) {
        RangePickerDialog(
            onDismiss = viewModel::dismissDateRangePicker,
            onPick = { viewModel.setCustomDateRange(it) },
        )
    }

    if (showNotes && notesViewModel != null && notesState != null) {
        QuickNoteSheet(
            state = notesState,
            focusComposer = focusNoteComposer,
            onDraftChange = notesViewModel::onDraftChange,
            onSave = notesViewModel::save,
            onEdit = notesViewModel::edit,
            onShowDay = { day -> showNotes = false; viewModel.navigateToDate(day.toString()) },
            onDismiss = { showNotes = false; focusNoteComposer = false },
        )
    }

    if (showRange) {
        RangeSheet(state = state, onDismiss = { showRange = false }, onPeriod = viewModel::setTimePeriod)
    }
    if (showOptions) {
        MapOptionsSheet(
            state = state,
            onDismiss = { showOptions = false },
            onBasemap = viewModel::setBasemap,
            onLayers = viewModel::updateLayers,
        )
    }

    nearHere?.let { result ->
        NearHereSheet(
            result = result,
            onDismiss = { nearHere = null },
            onDayClick = { day ->
                viewModel.navigateToDate(day.date.toString(), day.lat, day.lng)
                nearHere = null
            },
        )
    }

    tappedPoint?.let { pt ->
        PointSheet(
            pt = pt,
            deviceName = pt.deviceId?.let { state.deviceNames[it] ?: it },
            onDismiss = { tappedPoint = null },
            onDelete = {
                val id = pt.id
                tappedPoint = null
                viewModel.deletePoint(id) { ok ->
                    if (ok) haptics.confirm() else haptics.reject()
                    Toast.makeText(context, if (ok) "Point deleted" else "Couldn't delete point", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

// ── Floating controls ──────────────────────────────────────────────────────

@Composable
private fun glassModifier(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = Atlas.colors
    return Modifier
        .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(c.glass)
        .border(1.dp, c.lineStrong, shape)
}

@Composable
private fun GlassChip(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        glassModifier(SquircleShape(50)).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun GlassButton(icon: ImageVector, desc: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Atlas.colors
    Box(
        glassModifier(SquircleShape(50)).size(46.dp).tappable(enabled = enabled, pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
        else Icon(icon, desc, tint = c.text, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun RangePill(
    modifier: Modifier = Modifier,
    label: String,
    custom: Boolean,
    canBack: Boolean,
    canForward: Boolean,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    val c = Atlas.colors
    Row(modifier.then(glassModifier(SquircleShape(50))).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        StepArrow(Icons.Rounded.ChevronLeft, "Previous range", canBack) { onStep(-1) }
        Row(
            Modifier.weight(1f).clip(SquircleShape(50)).tappable(pressedScale = 0.95f, onClick = onOpen).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(if (custom) Icons.Rounded.DateRange else Icons.Rounded.CalendarMonth, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.ExpandMore, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
        }
        StepArrow(Icons.Rounded.ChevronRight, "Next range", canForward) { onStep(1) }
    }
}

@Composable
private fun StepArrow(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    Box(
        Modifier.size(width = 38.dp, height = 46.dp).clip(SquircleShape(50))
            .tappable(enabled = enabled, feel = Feel.None, pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) c.text else c.textFaint.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ZoomRail(modifier: Modifier, onIn: () -> Unit, onOut: () -> Unit) {
    val c = Atlas.colors
    Column(modifier.then(glassModifier(SquircleShape(50))).width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).tappable(feel = Feel.None, pressedScale = 0.85f, onClick = onIn), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Zoom in", tint = c.text, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.width(22.dp).height(1.dp).background(c.lineStrong))
        Box(Modifier.size(46.dp).tappable(feel = Feel.None, pressedScale = 0.85f, onClick = onOut), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Remove, "Zoom out", tint = c.text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BeenHereButton(checking: Boolean, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        glassModifier(SquircleShape(50)).tappable(enabled = !checking, pressedScale = 0.94f, onClick = onClick)
            .heightIn(min = 44.dp).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
        else Icon(Icons.Rounded.MyLocation, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (checking) "Checking…" else "Been here?", style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

@Composable
private fun SearchPill(modifier: Modifier, onClick: () -> Unit) {
    val c = Atlas.colors
    Row(
        modifier.then(glassModifier(SquircleShape(50))).height(46.dp).tappable(pressedScale = 0.97f, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Search or ask", style = MaterialTheme.typography.bodyLarge, color = c.textMuted, maxLines = 1)
    }
}

// ── Date range ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(onDismiss: () -> Unit, onPick: (DateRange) -> Unit) {
    val c = Atlas.colors
    val picker = rememberDateRangePickerState()
    val colors = DatePickerDefaults.colors(
        containerColor = c.surface,
        selectedDayContainerColor = c.accent,
        selectedDayContentColor = c.onAccent,
        dayInSelectionRangeContainerColor = c.accentSoft,
        todayDateBorderColor = c.accent,
        todayContentColor = c.accent,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        shape = SquircleShape(SquircleRadius.dialog),
        tonalElevation = 0.dp,
        confirmButton = {
            AtlasButton("Apply", compact = true, feel = Feel.Confirm, onClick = {
                val startMs = picker.selectedStartDateMillis
                if (startMs == null) { onDismiss(); return@AtlasButton }
                val zone = ZoneId.systemDefault()
                val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
                val end = picker.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: start
                onPick(DateRange(start, end))
            })
        },
        dismissButton = { AtlasButton("Cancel", onDismiss, kind = ButtonKind.Ghost, compact = true) },
    ) {
        DateRangePicker(state = picker, colors = colors, modifier = Modifier.wrapContentHeight().padding(top = 16.dp))
    }
}

// ── Map options ────────────────────────────────────────────────────────────

private val TRANSPORT_LABELS = mapOf(
    "walk" to "Walk", "cycle" to "Cycle", "vehicle" to "Vehicle",
    "plane" to "Plane", "still" to "Still", "none" to "Unclassified",
)

/**
 * Map options, grouped like the web sidebar: the time range on top (a pick
 * slides the sheet away first, since the map it changes is behind the scrim),
 * then folding groups for map display, track and extra data. Toggles keep the
 * sheet open so several can be set at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RangeSheet(state: MapUiState, onDismiss: () -> Unit, onPeriod: (TimePeriod) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss(); action() }
    }
    AtlasSheet(onDismiss = onDismiss, sheetState = sheetState, title = "Time range") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePeriod.entries.forEach { period ->
                    val isCustom = period == TimePeriod.CUSTOM
                    val label = when {
                        !isCustom -> period.label
                        state.timePeriod == TimePeriod.CUSTOM && state.customDateRange != null -> state.customDateRange.label
                        else -> "Pick dates…"
                    }
                    AtlasChip(
                        label = label,
                        selected = state.timePeriod == period,
                        icon = if (isCustom) Icons.Rounded.DateRange else null,
                        onClick = { closeThen { onPeriod(period) } },
                    )
                }
            }
        }
    }
}

/** Everything about how the map looks lives here, and only here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MapOptionsSheet(
    state: MapUiState,
    onDismiss: () -> Unit,
    onBasemap: (String) -> Unit,
    onLayers: ((MapLayers) -> MapLayers) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layers = state.layers
    AtlasSheet(onDismiss = onDismiss, sheetState = sheetState, title = "Map preferences") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Foldout("Map display", layers.openDisplay, { onLayers { it.copy(openDisplay = !it.openDisplay) } },
                icon = Icons.Rounded.Layers, badge = layers.displayOn.takeIf { it > 0 }?.let { "$it on" }) {
                Text("Basemap", style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted)
                val basemaps = buildList {
                    add("Dark"); add("Streets"); add("Satellite")
                    if (state.mapboxToken.isNotBlank()) { add("Mapbox Streets"); add("Mapbox Outdoors"); add("Mapbox Satellite") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    basemaps.forEach { name -> AtlasChip(name, name == state.basemap, { onBasemap(name) }) }
                }
                ToggleRow("Fog of war", layers.fog, { onLayers { l -> l.copy(fog = !l.fog) } },
                    subtitle = "Dim everywhere you've never been", modifier = Modifier.padding(horizontal = 0.dp))
            }
            Foldout("Track", layers.openTrack, { onLayers { it.copy(openTrack = !it.openTrack) } },
                icon = Icons.Rounded.Timeline, badge = layers.trackOn.takeIf { it > 0 }?.let { "$it on" }) {
                Text("Transport", style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLayers.TRANSPORT_MODES.forEach { mode ->
                        val on = mode in layers.modes
                        AtlasChip(TRANSPORT_LABELS[mode] ?: mode, on, {
                            onLayers { l -> l.copy(modes = if (on) l.modes - mode else l.modes + mode) }
                        })
                    }
                }
                ToggleRow("Filled gaps", layers.filledGaps, { onLayers { l -> l.copy(filledGaps = !l.filledGaps) } },
                    subtitle = "Points the map editor filled in, drawn dashed")
                ToggleRow("Hide outliers", layers.hideSuspect, { onLayers { l -> l.copy(hideSuspect = !l.hideSuspect) } },
                    subtitle = "Leave out fixes the quality scan flagged")
                ToggleRow("Hide track", layers.hideTrack, { onLayers { l -> l.copy(hideTrack = !l.hideTrack) } },
                    subtitle = "Show only the other layers")
            }
            Foldout("Extra data", layers.openExtra, { onLayers { it.copy(openExtra = !it.openExtra) } },
                icon = Icons.Rounded.Place, badge = layers.extraOn.takeIf { it > 0 }?.let { "$it on" }) {
                ToggleRow("My places", layers.places, { onLayers { l -> l.copy(places = !l.places) } },
                    subtitle = "Your saved places and their radius")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Point detail ───────────────────────────────────────────────────────────

@Composable
private fun PointSheet(pt: LocationPoint, deviceName: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = Atlas.colors
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm:ss") }
    val time = remember(pt.timestamp) {
        runCatching { java.time.OffsetDateTime.parse(pt.timestamp).atZoneSameInstant(ZoneId.systemDefault()).format(fmt) }
            .getOrDefault(pt.timestamp)
    }
    var confirming by remember { mutableStateOf(false) }
    AtlasSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Place, tint = Color(speedColor(pt.speed)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val place = listOfNotNull(pt.city, pt.state, pt.country).joinToString(", ")
                    Text(place.ifBlank { "Location point" }, style = MaterialTheme.typography.titleLarge, color = c.text)
                    Text(time, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(20.dp))
            val pairs = buildList {
                deviceName?.takeIf { it.isNotBlank() }?.let { add("Device" to it) }
                add("Coordinates" to "%.5f, %.5f".format(pt.lat, pt.lng))
                pt.speed?.let { add("Speed" to "%.1f %s".format(Units.speed(it * 3.6), Units.speedUnit)) }
                pt.accuracy?.let { add("Accuracy" to "${it.toInt()} m") }
                pt.altitude?.let { add("Altitude" to "${it.toInt()} m") }
                pt.battery?.let { add("Battery" to "${it.toInt()}%") }
            }
            pairs.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    row.forEach { (k, v) -> InfoPair(k, v, Modifier.weight(1f), mono = k == "Coordinates") }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (confirming) {
                Text("This permanently removes this GPS point. It can't be undone.", style = MaterialTheme.typography.bodySmall, color = c.danger)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AtlasButton("Cancel", { confirming = false }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                    AtlasButton("Delete", onDelete, Modifier.weight(1f), kind = ButtonKind.Danger, feel = Feel.Heavy)
                }
            } else {
                AtlasButton("Delete point", { confirming = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
            }
        }
    }
}

// ── Have I been here? ──────────────────────────────────────────────────────

@Composable
private fun NearHereSheet(result: NearHereResult, onDismiss: () -> Unit, onDayClick: (NearHereDay) -> Unit) {
    val c = Atlas.colors
    AtlasSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            val (title, sub, tint) = when {
                result.days.isNotEmpty() -> Triple(
                    "You've been right here",
                    buildString {
                        append("${result.veryCloseDays} day${if (result.veryCloseDays != 1) "s" else ""}")
                        result.closestMeters?.let { append(" · closest ${it.toInt()} m away") }
                    },
                    c.success,
                )
                result.totalRegionPoints > 0 -> Triple(
                    "Nearby, but not this spot",
                    "${result.totalRegionPoints} past points in ${result.region ?: "this area"}",
                    c.accent,
                )
                else -> Triple("First time here", "Nothing in your history nearby", c.cyan)
            }
            IconTile(Icons.Rounded.TravelExplore, tint = tint, size = 52.dp, iconSize = 26.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            result.region?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = c.textFaint)
            }
        }
        if (result.days.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Past visits", Modifier.padding(horizontal = 24.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(result.days) { day -> VisitRow(day) { onDayClick(day) } }
            }
        }
    }
}

@Composable
private fun VisitRow(day: NearHereDay, onClick: () -> Unit) {
    val c = Atlas.colors
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM yyyy") }
    Row(
        Modifier.fillMaxWidth().clip(SquircleShape(SquircleRadius.control)).background(c.surfaceHigh)
            .tappable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(day.date.format(fmt), style = MaterialTheme.typography.bodyLarge, color = c.text)
            Text(
                buildString {
                    append("${day.closestMeters.toInt()} m · ${day.pointCount} pts")
                    if (day.timeSpentSeconds > 0) append(" · ${formatDuration(day.timeSpentSeconds)}")
                },
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint)
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
