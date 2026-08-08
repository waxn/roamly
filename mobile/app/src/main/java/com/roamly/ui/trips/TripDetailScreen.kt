package com.roamly.ui.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.Comment
import com.roamly.data.api.TimelineEvent
import com.roamly.data.api.TripLatLng
import com.roamly.data.api.TripResponse
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge
import com.roamly.ui.theme.ClaySurface
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun TripDetailScreen(
    tripId: Int,
    onBack: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val clay = Clay.colors
    var showFullMap by remember { mutableStateOf(false) }
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    if (showFullMap && state.trip != null) {
        TripFullMapScreen(
            trip = state.trip!!,
            onBack = { showFullMap = false },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClayBackButton(onBack)
                Spacer(Modifier.width(8.dp))
                Text(
                    state.trip?.name ?: "Adventure",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::togglePublic) {
                    Icon(
                        Icons.Rounded.Public,
                        "Toggle public",
                        tint = if (state.trip?.isPublic == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.trip?.let { trip ->
                        item {
                            // Hero card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(20.dp)
                            ) {
                                Column {
                                    Text(trip.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                    trip.description?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        HeroStat("${trip.pointCount}", "points")
                                        HeroStat("${trip.memberCountResolved}", "members")
                                        val days = listOfNotNull(trip.startTime?.take(10), trip.endTime?.take(10)).joinToString(" → ")
                                        if (days.isNotEmpty()) HeroStat(days, "")
                                    }
                                }
                            }
                        }

                        // Map preview — non-scrollable, tap to fullscreen
                        if (trip.locations.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { showFullMap = true },
                                ) {
                                    AdventureMap(
                                        points = trip.locations,
                                        scrollable = false,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    // "Tap to expand" hint
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text("Expand map ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }

                        // Story — the author-written block document, read-only.
                        if (trip.body.isNotEmpty()) {
                            items(trip.body) { block ->
                                StoryBlock(
                                    block = block,
                                    places = trip.places,
                                    photos = trip.photos,
                                    serverUrl = state.serverUrl,
                                    onMapEmbedTap = { showFullMap = true },
                                )
                            }
                        }
                    }

                    if (state.events.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ClayIconBadge(Icons.Rounded.ChatBubbleOutline, size = 56.dp, cornerRadius = 20.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("No updates yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                Text("Tap + to add an update or milestone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(state.events) { event ->
                            if (event.type == "milestone") MilestoneCard(event)
                            else BlurbCard(
                                event = event,
                                comments = state.comments[event.id],
                                isExpanded = state.expandedBlurbId == event.id,
                                onDelete = { viewModel.deleteBlurb(event.id) },
                                onToggleComments = { viewModel.toggleComments(event.id) },
                                onPostComment = { text -> viewModel.createComment(event.id, text) },
                                onDeleteComment = { cid -> viewModel.deleteComment(event.id, cid) },
                            )
                        }
                    }
                }
            }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(60.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { viewModel.showAddTypeDialog() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, "Add", tint = Color.White, modifier = Modifier.size(28.dp))
        }

        state.error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
    }

    // --- Dialogs ---
    if (state.showAddTypeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddTypeDialog,
            title = { Text("What do you want to add?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::showBlurbDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update / Post")
                    }
                    OutlinedButton(onClick = viewModel::showMilestoneDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Milestone")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::hideAddTypeDialog) { Text("Cancel") } }
        )
    }

    if (state.showBlurbDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::hideBlurbDialog,
            title = { Text("Add update") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's happening?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            },
            confirmButton = { TextButton(onClick = { viewModel.createBlurb(text) }) { Text("Post") } },
            dismissButton = { TextButton(onClick = viewModel::hideBlurbDialog) { Text("Cancel") } }
        )
    }

    if (state.showMilestoneDialog) {
        MilestoneCreateDialog(
            onDismiss = viewModel::hideMilestoneDialog,
            onConfirm = { emoji, title, desc, date -> viewModel.createMilestone(emoji, title, desc, date) }
        )
    }
}

/** A small osmdroid map drawing the adventure's track as a coral polyline,
 *  auto-fit to the route — the mobile equivalent of the website's trip map. */
@Composable
private fun AdventureMap(
    points: List<TripLatLng>,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val context = LocalContext.current
    val mapView = remember(scrollable) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(scrollable)
            isClickable = scrollable
            controller.setZoom(12.0)
        }
    }

    LaunchedEffect(points) {
        mapView.overlays.clear()
        if (points.isNotEmpty()) {
            val geo = points.map { GeoPoint(it.lat, it.lng) }
            val line = Polyline(mapView).apply {
                setPoints(geo)
                outlinePaint.color = android.graphics.Color.rgb(249, 115, 79)
                outlinePaint.strokeWidth = 7f
                outlinePaint.isAntiAlias = true
                // Disable the default info window so tapping shows nothing
                infoWindow = null
                setOnClickListener { _, _, _ -> false }
            }
            mapView.overlays.add(line)
            mapView.post {
                if (geo.size == 1) {
                    mapView.controller.setZoom(14.0)
                    mapView.controller.setCenter(geo.first())
                } else {
                    runCatching { mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 64) }
                }
                mapView.invalidate()
            }
        }
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

@Composable
private fun ClayBackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
    }
}

@Composable
private fun BlurbCard(
    event: TimelineEvent,
    comments: List<Comment>?,
    isExpanded: Boolean,
    onDelete: () -> Unit,
    onToggleComments: () -> Unit,
    onPostComment: (String) -> Unit,
    onDeleteComment: (Int) -> Unit,
) {
    ClayCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(event.author)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(event.author ?: "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(event.createdAt?.take(10) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (!event.text.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(event.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        event.locationName?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleComments).padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            val count = comments?.size ?: event.commentCount
            Text(if (count > 0) "$count comment${if (count != 1) "s" else ""}" else "Comment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(visible = isExpanded) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                if (comments == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary)
                } else {
                    comments.forEach { c -> CommentRow(c, onDelete = { onDeleteComment(c.id) }) }
                }
                var commentText by remember { mutableStateOf("") }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = commentText, onValueChange = { commentText = it },
                        placeholder = { Text("Add a comment…", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        if (commentText.isNotBlank()) { onPostComment(commentText); commentText = "" }
                    }) { Icon(Icons.Rounded.Send, "Post", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String?) {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CommentRow(comment: Comment, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Avatar(comment.author)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(comment.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(comment.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
        }
        if (comment.canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Delete, "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MilestoneCard(event: TimelineEvent) {
    val clay = Clay.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.emoji ?: "🏆", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(event.title ?: "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                event.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f))
                }
                event.date?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = Color(0xFF052B26).copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun MilestoneCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (emoji: String, title: String, description: String, date: String) -> Unit
) {
    var emoji by remember { mutableStateOf("🏁") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add milestone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = emoji, onValueChange = { emoji = it }, label = { Text("Emoji") }, modifier = Modifier.width(84.dp), singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD) *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("2025-01-15") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(emoji, title, description, date) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full-screen map for an adventure — shows the polyline on real tiles with zoom
 *  controls and a back button. Opened when the user taps the mini-map card. */
@Composable
private fun TripFullMapScreen(trip: TripResponse, onBack: () -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
        }
    }

    LaunchedEffect(trip.locations) {
        mapView.overlays.clear()
        if (trip.locations.isNotEmpty()) {
            val geo = trip.locations.map { GeoPoint(it.lat, it.lng) }
            val line = Polyline(mapView).apply {
                setPoints(geo)
                outlinePaint.color = android.graphics.Color.rgb(249, 115, 79)
                outlinePaint.strokeWidth = 7f
                outlinePaint.isAntiAlias = true
                infoWindow = null
                setOnClickListener { _, _, _ -> false }
            }
            mapView.overlays.add(line)
            mapView.post {
                runCatching {
                    if (geo.size == 1) { mapView.controller.setZoom(14.0); mapView.controller.setCenter(geo.first()) }
                    else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 80)
                }
                mapView.invalidate()
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView }, modifier = Modifier.fillMaxSize())

        // Back button
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.ArrowBack, "Back", modifier = Modifier.size(18.dp))
                Text(trip.name, style = MaterialTheme.typography.labelLarge)
            }
        }

        // Zoom controls
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                IconButton(onClick = { mapView.controller.zoomIn() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Add, "Zoom in", modifier = Modifier.size(18.dp))
                }
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                IconButton(onClick = { mapView.controller.zoomOut() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Remove, "Zoom out", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
