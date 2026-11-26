package com.roamly.ui.tripblog

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.roamly.data.api.TripCommentDto
import com.roamly.data.api.TripDay
import com.roamly.data.api.TripMedia
import com.roamly.data.api.TripPostDto
import com.roamly.data.api.TripStatus
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.Eyebrow
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MEMBER_TRACK_COLORS
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.avatarMarker
import com.roamly.ui.kit.fitTo
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import com.roamly.ui.trips.mediaUrl
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val EMOJI = listOf("❤️", "😂", "😮", "😢", "👏")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")

/**
 * The featured trip blog: where the travellers are now, a Feed | Map switch,
 * and the day-grouped feed — the app's twin of the web page
 * (tracker/static/tracker/trip-blog.js). Posting opens [TripComposeScreen].
 */
@Composable
fun TripBlogScreen(viewModel: TripBlogViewModel, onBack: () -> Unit, onCompose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = Atlas.colors
    var page by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Index of each post in the flattened list (header + pending slots, then day headers and posts).
    fun indexOfPost(id: Int): Int? {
        var i = 2
        for (d in state.days) {
            i += 1
            for (p in d.posts) { if (p.id == id) return i; i += 1 }
        }
        return null
    }

    Box(Modifier.fillMaxSize()) {
        PushedScreen(
            title = state.status?.blog?.name ?: "Trip blog",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Refresh, "Refresh", viewModel::reload) },
        ) {
            if (state.blogId != null) {
                SegmentedSwitch(listOf("Feed", "Map"), page, { page = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            }
            when {
                state.blogId == null && !state.loading ->
                    EmptyState(Icons.Rounded.Public, "No trip blog yet", body = state.error ?: "Create one on the web under Adventures.")
                page == 1 -> TripMap(state.status, Modifier.fillMaxSize().padding(12.dp).clip(SquircleShape(SquircleRadius.panel))) { postId ->
                    page = 0
                    scope.launch { indexOfPost(postId)?.let { listState.animateScrollToItem(it) } }
                }
                else -> LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
                ) {
                    item(key = "header") { state.status?.let { Header(it) } ?: Spacer(Modifier.height(1.dp)) }
                    item(key = "pending") {
                        if (state.pendingPosts > 0) {
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                                Spacer(Modifier.width(8.dp))
                                Text("${state.pendingPosts} post${if (state.pendingPosts == 1) "" else "s"} uploading — they appear once they're in.",
                                    style = MaterialTheme.typography.bodySmall, color = c.accent)
                            }
                        }
                    }
                    if (state.loading && state.days.isEmpty()) {
                        item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = c.accent) } }
                    }
                    if (!state.loading && state.days.isEmpty()) {
                        item { EmptyState(Icons.Rounded.Edit, "No posts yet", body = state.error ?: "Tap New post to write the first one.") }
                    }
                    state.days.forEach { day ->
                        item(key = "day-${day.date}") { DayHeader(day) }
                        items(day.posts, key = { "post-${it.id}" }) { p -> PostCard(p, state.serverUrl, viewModel) }
                    }
                    if (state.nextBefore != null) {
                        item(key = "more") {
                            LaunchedEffect(state.nextBefore) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent) }
                        }
                    }
                }
            }
        }
        if (state.blogId != null && page == 0) {
            AtlasButton("New post", onCompose, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), icon = Icons.Rounded.Edit)
        }
    }
}

private fun dayLabel(iso: String): String = runCatching { LocalDate.parse(iso.take(10)).format(DAY_FMT) }.getOrDefault(iso)
private fun wallTime(local: String): String = runCatching { LocalDateTime.parse(local).format(TIME_FMT) }.getOrDefault("")
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
    val c = Atlas.colors
    val b = s.blog
    val (label, big) = when {
        s.stats.upcoming -> "Starts ${dayLabel(b.startDate)}" to b.name
        !b.live -> "Trip ended ${b.endDate?.let(::dayLabel) ?: ""}" to b.name
        s.home != null -> "Staying in" to listOf(s.home.city, s.home.country).filter { it.isNotBlank() }.joinToString(", ")
        s.people.isNotEmpty() -> "Now in" to (s.people.firstOrNull { !it.stale } ?: s.people.first()).city.ifBlank { b.name }
        else -> dayLabel(b.startDate) to b.name
    }
    Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.live && !s.stats.upcoming) { Dot(c.accent, 7.dp, pulse = true); Spacer(Modifier.width(7.dp)) }
            Eyebrow(label)
        }
        Spacer(Modifier.height(6.dp))
        Text(big, style = MaterialTheme.typography.headlineMedium, color = c.text)
        if (s.together.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(s.together, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        s.people.filter { it.stale }.forEach {
            Text("${it.name} · last seen ${it.city.ifBlank { "somewhere" }} ${ago(it.seen)}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!s.stats.upcoming) StatBlock("${s.stats.day}", if (b.live) "day" else "days", small = true, color = c.accent)
            StatBlock("${s.stats.countries}", if (s.stats.countries == 1) "country" else "countries", small = true)
            StatBlock("${s.stats.cities}", if (s.stats.cities == 1) "place" else "places", small = true)
            StatBlock("${s.stats.posts}", if (s.stats.posts == 1) "post" else "posts", small = true)
        }
    }
}

@Composable
private fun DayHeader(d: TripDay) {
    val c = Atlas.colors
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text("Day ${d.day}", style = MaterialTheme.typography.headlineSmall, color = c.text)
        Spacer(Modifier.width(10.dp))
        Text((dayLabel(d.date) + if (d.city.isNotBlank()) " · ${d.city}" else "").uppercase(),
            style = MaterialTheme.typography.labelSmall, color = c.textMuted,
            modifier = Modifier.weight(1f).padding(bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Avatar(name: String, id: Int, url: String?) {
    val color = Color(MEMBER_TRACK_COLORS[Math.floorMod(id, MEMBER_TRACK_COLORS.size)])
    Box(Modifier.size(36.dp).clip(SquircleShape(50)).background(color), Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun PostCard(p: TripPostDto, server: String, vm: TripBlogViewModel) {
    val c = Atlas.colors
    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth().padding(bottom = 12.dp), padding = PaddingValues(0.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.author.name, p.author.id, mediaUrl(server, p.author.avatar))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.author.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(wallTime(p.localTime), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    p.place?.let { pl ->
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Place, null, Modifier.size(12.dp), tint = c.accent)
                        Text(listOf(pl.city.ifBlank { pl.name }, pl.country).filter { it.isNotBlank() }.joinToString(", "),
                            style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 170.dp))
                    }
                }
            }
            if (p.mine) IconAction(Icons.Rounded.DeleteOutline, "Delete post", { confirmDelete = true }, container = null, tint = c.textMuted, size = 38.dp, iconSize = 18.dp)
        }
        if (p.text.isNotBlank()) Text(p.text, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        else Spacer(Modifier.height(10.dp))
        if (p.media.isNotEmpty()) {
            MediaBlock(p.media, server, onImage = { lightbox = it }, onVideo = { m ->
                mediaUrl(server, m.url)?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4")) } }
            })
        }
        Reactions(p) { vm.react(p, it) }
        Comments(p, vm)
    }
    lightbox?.let { start -> Lightbox(p.media.filter { it.type == "image" }, server, start) { lightbox = null } }
    if (confirmDelete) {
        AtlasDialog(
            title = "Delete this post?",
            onDismiss = { confirmDelete = false },
            confirmText = "Delete",
            onConfirm = { confirmDelete = false; vm.deletePost(p) },
            destructive = true, icon = Icons.Rounded.DeleteOutline,
            body = { Text("Its photos, videos and comments go with it.") },
        )
    }
}

@Composable
private fun MediaBlock(media: List<TripMedia>, server: String, onImage: (Int) -> Unit, onVideo: (TripMedia) -> Unit) {
    val c = Atlas.colors
    val images = media.filter { it.type == "image" }
    @Composable
    fun tile(m: TripMedia, modifier: Modifier, big: Boolean) {
        Box(modifier.background(c.surfaceHigh).tappable(pressedScale = 0.98f) {
            if (m.type == "video") { if (m.url != null) onVideo(m) } else onImage(images.indexOf(m).coerceAtLeast(0))
        }, Alignment.Center) {
            val src = if (m.type == "video") m.poster else if (big) m.url else (m.thumb ?: m.url)
            if (src != null) AsyncImage(model = mediaUrl(server, src), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (m.type == "video") {
                if (m.url == null) Text("Video is processing…", style = MaterialTheme.typography.bodySmall, color = Color.White)
                else Box(Modifier.size(54.dp).clip(SquircleShape(50)).background(Color(0x99000000)), Alignment.Center) {
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
                AsyncImage(model = mediaUrl(server, images[i].url), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            IconAction(Icons.Rounded.Close, "Close", onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                container = Color.White.copy(alpha = 0.15f), tint = Color.White)
            if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
    }
}

@Composable
private fun Reactions(p: TripPostDto, onReact: (String) -> Unit) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EMOJI.forEach { e ->
            val n = p.reactions[e] ?: 0
            val on = e in p.myReactions
            val bg by animateColorAsState(if (on) c.accentSoft else c.surfaceHigh, label = "react")
            Row(
                Modifier.clip(SquircleShape(50)).background(bg).border(1.dp, if (on) c.accent.copy(alpha = 0.6f) else Color.Transparent, SquircleShape(50))
                    .tappable(feel = Feel.None, pressedScale = 0.85f) { haptics.toggle(!on); onReact(e) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e)
                if (n > 0) { Spacer(Modifier.width(4.dp)); Text("$n", style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.textMuted) }
            }
        }
    }
}

@Composable
private fun Comments(p: TripPostDto, vm: TripBlogViewModel) {
    val c = Atlas.colors
    var text by remember(p.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        if (p.commentCount > p.comments.size) {
            Text("View all ${p.commentCount} comments", style = MaterialTheme.typography.labelLarge, color = c.accent,
                modifier = Modifier.clip(SquircleShape(8.dp)).tappable(feel = Feel.Tick) { vm.loadAllComments(p) }.padding(vertical = 6.dp))
        }
        p.comments.forEach { cm -> CommentRow(cm) { vm.deleteComment(p, cm) } }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AtlasField(text, { text = it }, Modifier.weight(1f), placeholder = "Write a comment…", singleLine = false, maxLines = 4)
            Spacer(Modifier.width(8.dp))
            val can = text.isNotBlank() && !sending
            IconAction(Icons.Rounded.ArrowUpward, "Send", {
                sending = true
                vm.comment(p, text.trim()) { ok -> sending = false; if (ok) text = "" }
            }, enabled = can, container = if (can) c.accent else c.surfaceHighest, tint = if (can) c.onAccent else c.textFaint)
        }
    }
}

@Composable
private fun CommentRow(cm: TripCommentDto, onDelete: () -> Unit) {
    val c = Atlas.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cm.name, style = MaterialTheme.typography.labelLarge, color = if (cm.traveller) c.accent else c.text)
            Spacer(Modifier.width(8.dp))
            Text(ago(cm.at), style = MaterialTheme.typography.labelMedium, color = c.textFaint, modifier = Modifier.weight(1f))
            if (cm.canDelete) IconAction(Icons.Rounded.DeleteOutline, "Delete comment", onDelete, container = null, tint = c.textFaint, size = 28.dp, iconSize = 14.dp)
        }
        Text(cm.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

/** Post pins plus each traveller's fuzzed circle. Tapping a pin opens its post. */
@Composable
fun TripMap(status: TripStatus?, modifier: Modifier = Modifier, onPin: (Int) -> Unit) {
    val c = Atlas.colors
    val context = LocalContext.current
    val accent = c.accent.toArgb()
    val mapView = rememberAtlasMapView(interactive = true)
    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        mapView.overlays.clear()
        mapView.controller.setZoom(3.0)
        val fit = mutableListOf<GeoPoint>()
        s.people.forEach { person ->
            val color = MEMBER_TRACK_COLORS[Math.floorMod(person.id, MEMBER_TRACK_COLORS.size)]
            val centre = GeoPoint(person.spot.lat, person.spot.lon)
            fit += centre
            mapView.overlays.add(Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(centre, person.spot.radiusM.toDouble())
                fillPaint.color = android.graphics.Color.argb(46, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
                outlinePaint.color = color
                outlinePaint.strokeWidth = 3f
                title = person.name
                snippet = "${person.city} · ${ago(person.seen)}"
            })
        }
        s.pins.forEach { pin ->
            val gp = GeoPoint(pin.lat, pin.lon)
            fit += gp
            mapView.overlays.add(Marker(mapView).apply {
                position = gp
                icon = avatarMarker(context, accent, null, 18f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> onPin(pin.id); true }
            })
        }
        mapView.invalidate()
        mapView.fitTo(fit, 64)
    }
    Box(modifier) {
        MapViewHost(mapView, Modifier.fillMaxSize())
        Text("Locations are approximate", style = MaterialTheme.typography.labelMedium, color = c.textMuted,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(SquircleShape(50)).background(c.glass).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
