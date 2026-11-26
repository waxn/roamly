package com.roamly.ui.flights

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roamly.data.api.FlightDto
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MapViewHost
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.SegmentedSwitch
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberAtlasMapView
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Your flight log: tickets, a route map, and the numbers. */
@Composable
fun FlightsScreen(
    viewModel: FlightsViewModel,
    useMiles: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.load() }
    val c = Atlas.colors

    PushedScreen(
        title = "Flights",
        onBack = onBack,
        subtitle = if (state.flights.isNotEmpty()) "${state.flights.size} flights" else null,
        actions = {
            IconAction(Icons.Rounded.Add, "Add a flight by hand", onAdd)
            IconAction(Icons.Rounded.QrCodeScanner, "Scan a boarding pass", onScan, container = c.accent, tint = c.onAccent)
        },
    ) {
        if (state.flights.isNotEmpty()) {
            SegmentedSwitch(listOf("Log", "Map", "Stats"), tab, { tab = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.trackMessage ?: "${state.flights.count { it.hasTrack }} of ${state.flights.size} have a recorded track",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f),
                )
                AtlasButton("Match tracks", viewModel::matchAllTracks, kind = ButtonKind.Ghost, compact = true)
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                state.loading && state.flights.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                state.flights.isEmpty() -> Column {
                    EmptyState(
                        Icons.Rounded.Flight, state.error ?: "No flights yet",
                        body = "Scan a boarding pass — or a screenshot or Wallet pass — or add one by its number and date.",
                        tint = c.cyan,
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AtlasButton("Scan a pass", onScan, Modifier.weight(1f), icon = Icons.Rounded.QrCodeScanner)
                        AtlasButton("Add by hand", onAdd, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Rounded.Add)
                    }
                }
                else -> AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "flightsTab") { t ->
                    when (t) {
                        0 -> FlightList(state.flights, useMiles, onOpen)
                        1 -> FlightMap(state.flights, state.tracks, state.trackSources)
                        else -> FlightStats(state.flights, useMiles)
                    }
                }
            }
        }
    }
}

// ── Shared figures ───────────────────────────────────────────────────────────

internal fun code(f: FlightDto, dep: Boolean) =
    (if (dep) f.depIata.ifBlank { f.depIcao } else f.arrIata.ifBlank { f.arrIcao }).ifBlank { "?" }

internal fun durationMin(f: FlightDto): Long? {
    val (a, b) = if (f.actualDep != null && f.actualArr != null) f.actualDep to f.actualArr else f.schedDep to f.schedArr
    if (a == null || b == null) return null
    return runCatching { Duration.between(OffsetDateTime.parse(a), OffsetDateTime.parse(b)).toMinutes() }
        .getOrNull()?.takeIf { it in 1 until 24 * 60 }
}

internal fun fmtDist(km: Double?, miles: Boolean): String =
    if (km == null) "–" else if (miles) "%,d mi".format((km * 0.621371).toLong()) else "%,d km".format(km.toLong())

internal fun fmtDur(min: Long?): String =
    if (min == null) "–" else if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

// ── Log: one ticket per flight ──────────────────────────────────────────────

@Composable
private fun FlightList(flights: List<FlightDto>, miles: Boolean, onOpen: (Int) -> Unit) {
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM yyyy") }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = dockAwareBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(flights, key = { it.id }) { f -> Ticket(f, miles, dateFmt) { onOpen(f.id) } }
    }
}

@Composable
private fun Ticket(f: FlightDto, miles: Boolean, dateFmt: DateTimeFormatter, onClick: () -> Unit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(runCatching { LocalDate.parse(f.date).format(dateFmt) }.getOrDefault(f.date).uppercase(),
                style = MaterialTheme.typography.labelSmall, color = c.textMuted, modifier = Modifier.weight(1f))
            if (f.trackSource.isNotBlank()) {
                Tag(if (f.trackSource == "opensky") "OpenSky" else "Recorded", color = if (f.trackSource == "opensky") c.violet else c.cyan)
                Spacer(Modifier.width(6.dp))
            }
            Text(f.flightNumber.ifBlank { "—" }, style = AtlasType.mono, color = c.accent)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Endpoint(code(f, true), f.depCity.ifBlank { f.depName }, Alignment.Start)
            Box(Modifier.weight(1f).height(28.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                val dash = c.lineStrong
                Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                    drawLine(dash, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
                }
                Box(Modifier.size(28.dp).clip(SquircleShape(50)).background(c.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AirplanemodeActive, null, tint = c.accent, modifier = Modifier.size(18.dp).rotate(90f))
                }
            }
            Endpoint(code(f, false), f.arrCity.ifBlank { f.arrName }, Alignment.End)
        }
        // The stub.
        val details = listOf(
            f.airlineName, fmtDur(durationMin(f)), fmtDist(f.distanceKm, miles), f.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" },
        ).filter { !it.isNullOrBlank() && it != "–" }
        if (details.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun Endpoint(code: String, city: String, align: Alignment.Horizontal) {
    val c = Atlas.colors
    Column(horizontalAlignment = align) {
        Text(code, style = AtlasType.figure.copy(fontSize = 32.sp), color = c.text)
        Text(city, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp), textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start)
    }
}

// ── Map ──────────────────────────────────────────────────────────────────────

@Composable
private fun FlightMap(flights: List<FlightDto>, tracks: Map<Int, List<List<Double>>>, sources: Map<Int, String>) {
    val c = Atlas.colors
    val density = LocalDensity.current
    val mapView = rememberAtlasMapView(interactive = true)
    val routeColor = c.accent.toArgb()
    val airportColor = c.amber.toArgb()
    val recordedColor = c.cyan.toArgb()
    val openskyColor = c.violet.toArgb()

    LaunchedEffect(flights, tracks, sources) {
        mapView.minZoomLevel = 1.5
        mapView.overlays.clear()
        data class Ap(val code: String, val name: String, val lat: Double, val lon: Double, var n: Int = 0)
        val airports = mutableMapOf<String, Ap>()
        val routes = mutableMapOf<String, Pair<FlightDto, Int>>()
        val untracked = mutableSetOf<String>()
        val recorded = mutableListOf<Pair<String, List<List<Double>>>>()
        flights.forEach { f ->
            if (f.depLat == null || f.depLon == null || f.arrLat == null || f.arrLon == null) return@forEach
            val a = code(f, true); val b = code(f, false)
            val key = listOf(a, b).sorted().joinToString("-")
            routes[key] = f to ((routes[key]?.second ?: 0) + 1)
            // A recorded flight is drawn along the path actually flown; the
            // route's great circle covers only its untracked flights.
            val t = tracks[f.id]
            if (t != null && t.size >= 2) recorded += (if (sources[f.id] == "opensky") "osk:" else "") + key to t
            else untracked += key
            airports.getOrPut(a) { Ap(a, f.depName, f.depLat, f.depLon) }.n++
            airports.getOrPut(b) { Ap(b, f.arrName, f.arrLat, f.arrLon) }.n++
        }
        val maxRoute = (routes.values.maxOfOrNull { it.second } ?: 1).toFloat()
        recorded.forEach { (tagged, coords) ->
            val osk = tagged.startsWith("osk:")
            val key = tagged.removePrefix("osk:")
            val w = (routes[key]?.second ?: 1) / maxRoute
            wrappedRuns(coords).forEach { run ->
                mapView.overlays.add(Polyline(mapView).apply {
                    setPoints(run)
                    outlinePaint.color = if (osk) openskyColor else recordedColor
                    outlinePaint.alpha = (150 + 105 * w).toInt()
                    outlinePaint.strokeWidth = with(density) { (1.8f + 2.5f * w).dp.toPx() }
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                    title = "${key.replace("-", " ⇄ ")} · ${if (osk) "OpenSky track" else "recorded track"}"
                })
            }
        }
        routes.filterKeys { it in untracked }.forEach { (key, value) ->
            val (f, n) = value
            val w = n / maxRoute
            mapView.overlays.add(Polyline(mapView).apply {
                isGeodesic = true // great circles, like the website's arcs
                setPoints(listOf(GeoPoint(f.depLat!!, f.depLon!!), GeoPoint(f.arrLat!!, f.arrLon!!)))
                outlinePaint.color = routeColor
                outlinePaint.alpha = (120 + 135 * w).toInt()
                outlinePaint.strokeWidth = with(density) { (1.6f + 2.5f * w).dp.toPx() }
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
                title = "${key.replace("-", " ⇄ ")} · $n flight${if (n == 1) "" else "s"}"
            })
        }
        val fit = mutableListOf<GeoPoint>()
        val maxAp = (airports.values.maxOfOrNull { it.n } ?: 1).toFloat()
        airports.values.forEach { ap ->
            val px = with(density) { (8 + 10 * ap.n / maxAp).dp.toPx() }.toInt()
            val dot = ShapeDrawable(OvalShape()).apply {
                paint.color = airportColor
                setIntrinsicWidth(px); setIntrinsicHeight(px); setBounds(0, 0, px, px)
            }
            val p = GeoPoint(ap.lat, ap.lon)
            fit += p
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "${ap.code} · ${ap.name}"
                snippet = "${ap.n} visit${if (ap.n == 1) "" else "s"}"
            })
        }
        mapView.invalidate()
        if (fit.size >= 2) mapView.post { runCatching { mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(fit), false, 80) } }
    }

    Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = dockAwareBottom(12.dp)).clip(SquircleShape(SquircleRadius.panel))) {
        MapViewHost(mapView, Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp).clip(SquircleShape(50)).background(c.glass).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Legend(c.accent, "Route"); Legend(c.cyan, "Recorded"); Legend(c.violet, "OpenSky"); Legend(c.amber, "Airport")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 7.dp); Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.text)
    }
}

/**
 * A recorded track as osmdroid runs: longitudes wrapped back into ±180 and the
 * line split wherever it crosses the date line, since osmdroid (unlike the
 * web map) draws a coordinate past 180 nowhere useful.
 */
internal fun wrappedRuns(coords: List<List<Double>>): List<List<GeoPoint>> {
    val runs = mutableListOf<MutableList<GeoPoint>>()
    var cur = mutableListOf<GeoPoint>()
    var prevLon: Double? = null
    coords.forEach { c ->
        if (c.size < 2) return@forEach
        val lon = ((c[0] + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (prevLon != null && kotlin.math.abs(lon - prevLon!!) > 180.0) {
            if (cur.size >= 2) runs += cur
            cur = mutableListOf()
        }
        cur += GeoPoint(c[1], lon)
        prevLon = lon
    }
    if (cur.size >= 2) runs += cur
    return runs
}

// ── Stats ────────────────────────────────────────────────────────────────────

@Composable
private fun FlightStats(flights: List<FlightDto>, miles: Boolean) {
    val c = Atlas.colors
    val totalKm = flights.sumOf { it.distanceKm ?: 0.0 }
    val totalMin = flights.sumOf { durationMin(it) ?: 0L }
    val airports = flights.flatMap { listOf(code(it, true), code(it, false)) }.toSet()
    val countries = flights.flatMap { listOf(it.depCountry, it.arrCountry) }.filter { it.isNotBlank() }.toSet()
    val airlines = flights.map { it.airlineName }.filter { it.isNotBlank() }.toSet()
    fun tally(key: (FlightDto) -> String?) = flights.mapNotNull { key(it)?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = dockAwareBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Text(fmtDist(totalKm, miles), style = MaterialTheme.typography.displayMedium, color = c.text)
            Text("flown across ${flights.size} flights", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatBlock("%.2f×".format(totalKm / 40075.0), "round the Earth", small = true, color = c.cyan)
                StatBlock("%.1f%%".format(totalKm / 384400.0 * 100), "to the Moon", small = true, color = c.violet)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(if (totalMin >= 48 * 60) "%.1fd".format(totalMin / 1440.0) else fmtDur(totalMin), "in the air", Modifier.weight(1f))
            Tile("${airports.size}", "airports", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("${airlines.size}", "airlines", Modifier.weight(1f))
            Tile("${countries.size}", "countries", Modifier.weight(1f))
        }

        val years = tally { it.date.take(4) }.sortedBy { it.first }
        if (years.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Flights per year")
            val max = years.maxOf { it.second }.toFloat()
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                years.takeLast(12).forEach { (y, n) ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text("$n", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f * n / max).clip(SquircleShape(6.dp)).background(c.accent))
                        Spacer(Modifier.height(6.dp))
                        Text("'${y.takeLast(2)}", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
                    }
                }
            }
        }

        Bars("Top airports", flights.flatMap { f ->
            listOf("${code(f, true)} · ${f.depCity.ifBlank { f.depName }}", "${code(f, false)} · ${f.arrCity.ifBlank { f.arrName }}")
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }, "visit", c.amber)
        Bars("Top routes", tally { listOf(code(it, true), code(it, false)).sorted().joinToString(" ⇄ ") }, tint = c.accent)
        Bars("Airlines", tally { it.airlineName }, tint = c.cyan)
        AircraftStats(flights, miles)
        Bars("Seat class", tally { it.seatClass.replaceFirstChar { ch -> ch.uppercase() } }, tint = c.rose)

        val withDist = flights.filter { it.distanceKm != null }
        if (withDist.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
            SectionTitle("Records")
            val hi = withDist.maxBy { it.distanceKm!! }
            val lo = withDist.minBy { it.distanceKm!! }
            Fact("Longest", "${code(hi, true)} → ${code(hi, false)} · ${fmtDist(hi.distanceKm, miles)}")
            Fact("Shortest", "${code(lo, true)} → ${code(lo, false)} · ${fmtDist(lo.distanceKm, miles)}")
            Fact("Average", fmtDist(totalKm / withDist.size, miles))
        }
    }
}

/** "Boeing 737-800" -> "Boeing 737", "Airbus A321neo" -> "Airbus A321". */
internal fun acFamily(model: String): String {
    val parts = model.trim().split(Regex("\\s+"), limit = 3)
    if (parts.isEmpty() || parts[0].isBlank()) return ""
    val type = parts.getOrNull(1).orEmpty()
    val base = Regex("^[A-Za-z]*\\d+").find(type)?.value ?: type.split('-', '(').first()
    return if (base.isBlank()) parts[0] else "${parts[0]} $base"
}

@Composable
private fun AircraftStats(flights: List<FlightDto>, miles: Boolean) {
    val c = Atlas.colors
    val withAc = flights.filter { it.aircraftModel.isNotBlank() }
    if (withAc.isEmpty() && flights.none { it.registration.isNotBlank() }) return
    fun count(key: (FlightDto) -> String) = flights.map(key).filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Aircraft")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("${withAc.map { it.aircraftModel }.toSet().size}", "types", small = true)
            StatBlock("${withAc.map { acFamily(it.aircraftModel) }.toSet().size}", "families", small = true)
            StatBlock("${flights.map { it.registration }.filter { it.isNotBlank() }.toSet().size}", "tails", small = true)
        }
        val byType = withAc.groupBy { it.aircraftModel }
        if (byType.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val far = byType.maxBy { (_, fs) -> fs.sumOf { it.distanceKm ?: 0.0 } }
            Fact("Most distance on one type", "${far.key} · ${fmtDist(far.value.sumOf { it.distanceKm ?: 0.0 }, miles)}")
            val aloft = byType.maxBy { (_, fs) -> fs.sumOf { durationMin(it) ?: 0L } }
            val aloftMin = aloft.value.sumOf { durationMin(it) ?: 0L }
            if (aloftMin > 0) Fact("Most time aloft on one type", "${aloft.key} · ${fmtDur(aloftMin)}")
        }
    }
    Bars("Most flown types", count { it.aircraftModel }, tint = c.lime)
    Bars("Aircraft families", count { acFamily(it.aircraftModel) }, tint = c.lime)
    Bars("Manufacturers", count { it.aircraftModel.trim().substringBefore(' ') }, tint = c.lime)
    val modelOf = flights.filter { it.registration.isNotBlank() }.associate { it.registration to it.aircraftModel }
    Bars("Tails flown more than once", count { it.registration }.filter { it.second > 1 }
        .map { (r, n) -> (modelOf[r]?.takeIf { m -> m.isNotBlank() }?.let { "$r · $it" } ?: r) to n }, tint = c.sky)
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Panel(modifier, padding = PaddingValues(16.dp)) { StatBlock(value, label) }
}

@Composable
private fun Bars(title: String, entries: List<Pair<String, Int>>, unit: String = "flight", tint: Color = Atlas.colors.accent) {
    if (entries.isEmpty()) return
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle(title)
        val max = entries.first().second.toFloat()
        entries.take(6).forEach { (name, n) ->
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text("$n $unit${if (n == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(SquircleShape(50)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(n / max).clip(SquircleShape(50)).background(tint))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = Atlas.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        Text(value, style = AtlasType.mono, color = c.text)
    }
}
