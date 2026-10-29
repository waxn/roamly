package com.roamly.ui.tripblog

import android.content.Intent
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.roamly.data.api.TripCommentDto
import com.roamly.data.api.TripDay
import com.roamly.data.api.TripMedia
import com.roamly.data.api.TripPostDto
import com.roamly.data.api.TripStatus
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.trips.MEMBER_TRACK_COLORS
import com.roamly.ui.trips.mediaUrl
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val EMOJI = listOf("❤️", "😂", "😮", "😢", "👏")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d")
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")

/**
 * The Trip tab: the featured trip blog's header ("Staying in Kyoto"), a
 * Feed | Map toggle, and the day-grouped feed — the app's twin of the web
 * page (tracker/static/tracker/trip-blog.js). Posting opens [TripComposeScreen].
 */
@Composable
fun TripBlogScreen(viewModel: TripBlogViewModel, onCompose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var mapMode by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Index of each post in the flattened list (header, toggle-less rows…).
    fun indexOfPost(id: Int): Int? {
        var i = 2                          // header + pending banner slot
        for (d in state.days) {
            i += 1                         // day header
            for (p in d.posts) { if (p.id == id) return i; i += 1 }
        }
        return null
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = !mapMode, onClick = { mapMode = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Feed") }
                    SegmentedButton(selected = mapMode, onClick = { mapMode = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Map") }
                }
                IconButton(onClick = viewModel::reload) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
            when {
                state.blogId == null && !state.loading -> Empty(state.error ?: "No trip yet. Create one on the web under Adventures.")
                mapMode -> TripMap(state.status, Modifier.fillMaxSize()) { postId ->
                    mapMode = false
                    scope.launch { indexOfPost(postId)?.let { listState.animateScrollToItem(it) } }
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    item(key = "header") { state.status?.let { Header(it) } ?: Spacer(Modifier.height(1.dp)) }
                    item(key = "pending") {
                        if (state.pendingPosts > 0) {
                            Text("${state.pendingPosts} post${if (state.pendingPosts == 1) "" else "s"} uploading — they appear once they're in.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    if (state.loading && state.days.isEmpty()) {
                        item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
                    }
                    if (!state.loading && state.days.isEmpty()) {
                        item { Empty(state.error ?: "No posts yet. Tap New post to write the first one.") }
                    }
                    state.days.forEach { day ->
                        item(key = "day-${day.date}") { DayHeader(day) }
                        items(day.posts, key = { "post-${it.id}" }) { p ->
                            PostCard(p, state.serverUrl, viewModel)
                        }
                    }
                    if (state.nextBefore != null) {
                        item(key = "more") {
                            LaunchedEffect(state.nextBefore) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        if (state.blogId != null) {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                icon = { Icon(Icons.Rounded.Edit, null) },
                text = { Text("New post") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun Empty(msg: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dayLabel(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DAY_FMT) }.getOrDefault(iso)

private fun wallTime(local: String): String =
    runCatching { LocalDateTime.parse(local).format(TIME_FMT) }.getOrDefault("")

private fun ago(iso: String): String = runCatching {
    val s = java.time.Duration.between(java.time.OffsetDateTime.parse(iso).toInstant(), java.time.Instant.now()).seconds
    when {
        s < 90 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 2 * 86400 -> "yesterday"
        else -> "${s / 86400} days ago"
    }
}.getOrDefault("")

@Composable
private fun Header(s: TripStatus) {
    val b = s.blog
    val (label, big) = when {
        s.stats.upcoming -> "Starts ${dayLabel(b.startDate)}" to b.name
        !b.live -> "Trip ended ${b.endDate?.let(::dayLabel) ?: ""}" to b.name
        s.home != null -> "Staying in" to listOf(s.home.city, s.home.country).filter { it.isNotBlank() }.joinToString(", ")
        s.people.isNotEmpty() -> "Now in" to (s.people.firstOrNull { !it.stale } ?: s.people.first()).city.ifBlank { b.name }
        else -> dayLabel(b.startDate) to b.name
    }
    ClayCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(b.name.uppercase(), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(big, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (s.together.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(s.together, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        s.people.filter { it.stale }.forEach {
            Text("${it.name} · last seen ${it.city.ifBlank { "somewhere" }} ${ago(it.seen)}",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (!s.stats.upcoming) Stat("${s.stats.day}", if (b.live) "Day" else "Days")
            Stat("${s.stats.countries}", if (s.stats.countries == 1) "Country" else "Countries")
            Stat("${s.stats.cities}", if (s.stats.cities == 1) "Place" else "Places")
            Stat("${s.stats.posts}", if (s.stats.posts == 1) "Post" else "Posts")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayHeader(d: TripDay) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text("Day ${d.day}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Text(dayLabel(d.date) + if (d.city.isNotBlank()) " · ${d.city}" else "",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Avatar(name: String, id: Int, url: String?) {
    val color = Color(MEMBER_TRACK_COLORS[Math.floorMod(id, MEMBER_TRACK_COLORS.size)])
    Box(Modifier.size(34.dp).clip(CircleShape).background(color), Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PostCard(p: TripPostDto, server: String, vm: TripBlogViewModel) {
    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ClayCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = 0.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.author.name, p.author.id, mediaUrl(server, p.author.avatar))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.author.name, style = MaterialTheme.typography.titleSmall)
                Text(wallTime(p.localTime), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p.place?.let { pl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(listOf(pl.city.ifBlank { pl.name }, pl.country).filter { it.isNotBlank() }.joinToString(", "),
                         style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                         maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                }
            }
            if (p.mine) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Post options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete post") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            } else Spacer(Modifier.width(10.dp))
        }
        if (p.text.isNotBlank()) {
            Text(p.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        } else Spacer(Modifier.height(10.dp))
        if (p.media.isNotEmpty()) {
            MediaBlock(p.media, server, onImage = { lightbox = it }, onVideo = { m ->
                mediaUrl(server, m.url)?.let { u ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4"))
                    }
                }
            })
        }
        Reactions(p) { vm.react(p, it) }
        Comments(p, vm)
    }
    lightbox?.let { start -> Lightbox(p.media.filter { it.type == "image" }, server, start) { lightbox = null } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this post?") },
            text = { Text("Its photos, videos and comments go with it.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePost(p) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MediaBlock(media: List<TripMedia>, server: String, onImage: (Int) -> Unit, onVideo: (TripMedia) -> Unit) {
    val images = media.filter { it.type == "image" }
    @Composable
    fun tile(m: TripMedia, modifier: Modifier, big: Boolean) {
        Box(modifier.background(Color(0x22000000)).clickable {
            if (m.type == "video") { if (m.url != null) onVideo(m) } else onImage(images.indexOf(m).coerceAtLeast(0))
        }, Alignment.Center) {
            val src = if (m.type == "video") m.poster else if (big) m.url else (m.thumb ?: m.url)
            if (src != null) AsyncImage(model = mediaUrl(server, src), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (m.type == "video") {
                if (m.url == null) Text("Video is processing…", style = MaterialTheme.typography.bodySmall, color = Color.White)
                else Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)), Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
    val first = media.first()
    val ratio = if ((first.w ?: 0) > 0 && (first.h ?: 0) > 0) (first.w!!.toFloat() / first.h!!).coerceIn(0.6f, 2f) else 1.5f
    tile(first, Modifier.fillMaxWidth().aspectRatio(ratio), big = true)
    if (media.size > 1) {
        Spacer(Modifier.height(3.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items(media.drop(1), key = { it.id }) { m -> tile(m, Modifier.size(110.dp), big = false) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Lightbox(images: List<TripMedia>, server: String, start: Int, onClose: () -> Unit) {
    if (images.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, images.size - 1)) { images.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                AsyncImage(model = mediaUrl(server, images[i].url), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
            if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
    }
}

@Composable
private fun Reactions(p: TripPostDto, onReact: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EMOJI.forEach { e ->
            val n = p.reactions[e] ?: 0
            val on = e in p.myReactions
            val border = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Row(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp))
                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                .clickable { onReact(e) }.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(e)
                if (n > 0) { Spacer(Modifier.width(4.dp)); Text("$n", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun Comments(p: TripPostDto, vm: TripBlogViewModel) {
    var text by remember(p.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
        if (p.commentCount > p.comments.size) {
            Text("View all ${p.commentCount} comments", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.clickable { vm.loadAllComments(p) }.padding(vertical = 6.dp))
        }
        p.comments.forEach { c -> CommentRow(c) { vm.deleteComment(p, c) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Write a comment…") },
                singleLine = false, maxLines = 4, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
            IconButton(enabled = text.isNotBlank() && !sending, onClick = {
                sending = true
                vm.comment(p, text.trim()) { ok -> sending = false; if (ok) text = "" }
            }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        }
    }
}

@Composable
private fun CommentRow(c: TripCommentDto, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = MaterialTheme.typography.labelLarge,
                 color = if (c.traveller) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(ago(c.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            if (c.canDelete) Text("delete", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onDelete).padding(4.dp))
        }
        Text(c.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Post pins + each traveller's fuzzed circle. Tapping a pin opens its post. */
@Composable
fun TripMap(status: TripStatus?, modifier: Modifier = Modifier, onPin: (Int) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            controller.setZoom(3.0)
        }
    }
    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        mapView.overlays.clear()
        val fit = mutableListOf<GeoPoint>()
        val pinPx = with(density) { 14.dp.toPx() }.toInt()
        s.people.forEach { person ->
            val color = MEMBER_TRACK_COLORS[Math.floorMod(person.id, MEMBER_TRACK_COLORS.size)]
            val centre = GeoPoint(person.spot.lat, person.spot.lon)
            fit += centre
            mapView.overlays.add(Polygon(mapView).apply {
                setPoints(Polygon.pointsAsCircle(centre, person.spot.radiusM.toDouble()))
                fillPaint.color = android.graphics.Color.argb(46, android.graphics.Color.red(color),
                    android.graphics.Color.green(color), android.graphics.Color.blue(color))
                outlinePaint.color = color
                outlinePaint.strokeWidth = 3f
                title = person.name
                snippet = "${person.city} · ${ago(person.seen)}"
            })
        }
        s.pins.forEach { pin ->
            val gp = GeoPoint(pin.lat, pin.lon)
            fit += gp
            val dot = ShapeDrawable(OvalShape()).apply {
                paint.color = android.graphics.Color.argb(255, (primary.red * 255).toInt(), (primary.green * 255).toInt(), (primary.blue * 255).toInt())
                setIntrinsicWidth(pinPx)
                setIntrinsicHeight(pinPx)
                setBounds(0, 0, pinPx, pinPx)
            }
            mapView.overlays.add(Marker(mapView).apply {
                position = gp
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> onPin(pin.id); true }
            })
        }
        mapView.invalidate()
        if (fit.isNotEmpty()) mapView.post {
            runCatching {
                if (fit.size == 1) { mapView.controller.setZoom(11.0); mapView.controller.setCenter(fit[0]) }
                else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(fit), false, 64)
            }
        }
    }
    DisposableEffect(mapView) { mapView.onResume(); onDispose { mapView.onPause() } }
    Box(modifier) {
        AndroidView(factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
            modifier = Modifier.fillMaxSize())
        Text("Locations are approximate", style = MaterialTheme.typography.labelSmall,
             color = Clay.colors.moss, modifier = Modifier.align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(6.dp))
    }
}
