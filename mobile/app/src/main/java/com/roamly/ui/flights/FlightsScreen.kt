package com.roamly.ui.flights

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roamly.data.api.FlightDto
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The Flights page inside the Data tab: List | Map | Stats. */
@Composable
fun FlightsScreen(
    viewModel: FlightsViewModel,
    useMiles: Boolean,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ClayButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan pass", fontWeight = FontWeight.Bold)
            }
            ClayOutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add by hand")
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            listOf("List", "Map", "Stats").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)) { Text(label) }
            }
        }

        if (state.flights.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.trackMessage ?: "${state.flights.count { it.hasTrack }} of ${state.flights.size} flights have a recorded track",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = viewModel::matchAllTracks) { Text("Match tracks") }
            }
        }
        when {
            state.loading && state.flights.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.flights.isEmpty() -> Empty(state.error)
            tab == 0 -> FlightList(state.flights, useMiles, onOpen)
            tab == 1 -> FlightMap(state.flights, state.tracks, state.trackSources)
            else -> FlightStats(state.flights, useMiles)
        }
    }
}

@Composable
private fun Empty(error: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Flight, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(error ?: "No flights yet", fontWeight = FontWeight.SemiBold)
        Text("Scan a boarding pass, or add a flight by its number and date.",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Shared figures ───────────────────────────────────────────────────────────

internal fun code(f: FlightDto, dep: Boolean) =
    (if (dep) f.depIata.ifBlank { f.depIcao } else f.arrIata.ifBlank { f.arrIcao }).ifBlank { "?" }

internal fun durationMin(f: FlightDto): Long? {
    val (a, b) = if (f.actualDep != null && f.actualArr != null) f.actualDep to f.actualArr else f.schedDep to f.schedArr
    if (a == null || b == null) return null
    return runCatching {
        Duration.between(OffsetDateTime.parse(a), OffsetDateTime.parse(b)).toMinutes()
    }.getOrNull()?.takeIf { it in 1 until 24 * 60 }
}

internal fun fmtDist(km: Double?, miles: Boolean): String =
    if (km == null) "–" else if (miles) "%,d mi".format((km * 0.621371).toLong()) else "%,d km".format(km.toLong())

internal fun fmtDur(min: Long?): String =
    if (min == null) "–" else if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

// ── List ─────────────────────────────────────────────────────────────────────

@Composable
private fun FlightList(flights: List<FlightDto>, miles: Boolean, onOpen: (Int) -> Unit) {
    val dateFmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(flights, key = { it.id }) { f ->
            ClayCard(onClick = { onOpen(f.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${code(f, true)} → ${code(f, false)}", fontFamily = FontFamily.Monospace,
                                 fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            if (f.trackSource.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(if (f.trackSource == "opensky") "OPENSKY" else "TRACK", fontFamily = FontFamily.Monospace,
                                     color = if (f.trackSource == "opensky") Clay.colors.mauve else Clay.colors.mapBlue,
                                     style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("${f.depCity.ifBlank { f.depName }} → ${f.arrCity.ifBlank { f.arrName }}",
                             style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(runCatching { LocalDate.parse(f.date).format(dateFmt) }.getOrDefault(f.date),
                             style = MaterialTheme.typography.bodySmall)
                        Text(f.flightNumber.ifBlank { "–" }, fontFamily = FontFamily.Monospace,
                             style = MaterialTheme.typography.bodySmall, color = Clay.colors.mapBlue)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(listOf(f.airlineName, f.aircraftModel, fmtDur(durationMin(f)), fmtDist(f.distanceKm, miles),
                            f.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" })
                        .filter { !it.isNullOrBlank() && it != "–" }.joinToString(" · "),
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Map ──────────────────────────────────────────────────────────────────────

@Composable
private fun FlightMap(flights: List<FlightDto>, tracks: Map<Int, List<List<Double>>>, sources: Map<Int, String>) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = Clay.colors.rust.toArgb()
    val airportColor = Clay.colors.ochre.toArgb()
    val recordedColor = Clay.colors.mapBlue.toArgb()
    val openskyColor = Clay.colors.mauve.toArgb()
    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "Roamly/1.0"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles").apply { mkdirs() }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setDestroyMode(false)
            minZoomLevel = 1.5
            controller.setZoom(2.0)
        }
    }

    LaunchedEffect(flights, tracks, sources) {
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
            // route's great-circle line covers only its untracked flights.
            val t = tracks[f.id]
            if (t != null && t.size >= 2) recorded += (if (sources[f.id] == "opensky") "osk:" else "") + key to t
            else untracked += key
            airports.getOrPut(a) { Ap(a, f.depName, f.depLat, f.depLon) }.n++
            airports.getOrPut(b) { Ap(b, f.arrName, f.arrLat, f.arrLon) }.n++
        }
        val maxRoute = (routes.values.maxOfOrNull { it.second } ?: 1).toFloat()
        val fit = mutableListOf<GeoPoint>()
        recorded.forEach { (tagged, coords) ->
            val osk = tagged.startsWith("osk:")
            val key = tagged.removePrefix("osk:")
            val n = routes[key]?.second ?: 1
            val w = n / maxRoute
            wrappedRuns(coords).forEach { run ->
                mapView.overlays.add(Polyline(mapView).apply {
                    setPoints(run)
                    outlinePaint.color = if (osk) openskyColor else recordedColor
                    outlinePaint.alpha = (140 + 115 * w).toInt()
                    outlinePaint.strokeWidth = with(density) { (1.5f + 2.5f * w).dp.toPx() }
                    outlinePaint.isAntiAlias = true
                    title = "${key.replace("-", " ⇄ ")} · ${if (osk) "OpenSky track" else "recorded track"}"
                })
            }
        }
        routes.filterKeys { it in untracked }.forEach { (key, value) ->
            val (f, n) = value
            val w = n / maxRoute
            mapView.overlays.add(Polyline(mapView).apply {
                setGeodesic(true)   // great circles, like the website's arcs
                setPoints(listOf(GeoPoint(f.depLat!!, f.depLon!!), GeoPoint(f.arrLat!!, f.arrLon!!)))
                outlinePaint.color = accent
                outlinePaint.alpha = (110 + 140 * w).toInt()
                outlinePaint.strokeWidth = with(density) { (1.5f + 2.5f * w).dp.toPx() }
                outlinePaint.isAntiAlias = true
                title = "${key.replace("-", " ⇄ ")} · $n flight${if (n == 1) "" else "s"}"
            })
        }
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
        if (fit.size >= 2) mapView.post {
            runCatching { mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(fit), false, 80) }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
    AndroidView(
        factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView },
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
    )
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
    val totalKm = flights.sumOf { it.distanceKm ?: 0.0 }
    val totalMin = flights.sumOf { durationMin(it) ?: 0L }
    val airports = flights.flatMap { listOf(code(it, true), code(it, false)) }.toSet()
    val countries = flights.flatMap { listOf(it.depCountry, it.arrCountry) }.filter { it.isNotBlank() }.toSet()
    val airlines = flights.map { it.airlineName }.filter { it.isNotBlank() }.toSet()

    fun tally(key: (FlightDto) -> String?) = flights.mapNotNull { key(it)?.takeIf { s -> s.isNotBlank() } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("${flights.size}", "flights", Modifier.weight(1f))
            Tile(fmtDist(totalKm, miles), "flown", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(if (totalMin >= 48 * 60) "%.1fd".format(totalMin / 1440.0) else fmtDur(totalMin), "in the air", Modifier.weight(1f))
            Tile("${airports.size}", "airports", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("${airlines.size}", "airlines", Modifier.weight(1f))
            Tile("${countries.size}", "countries", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("%.2f×".format(totalKm / 40075.0), "around the Earth", Modifier.weight(1f))
            Tile("%.1f%%".format(totalKm / 384400.0 * 100), "to the Moon", Modifier.weight(1f))
        }

        val years = tally { it.date.take(4) }.sortedBy { it.first }
        if (years.isNotEmpty()) ClayCard {
            Text("Flights per year", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            val max = years.maxOf { it.second }.toFloat()
            LazyRow(Modifier.height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(years) { (y, n) ->
                    Column(Modifier.width(34.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.Bottom) {
                        Text("$n", style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.width(26.dp).fillMaxHeight(0.75f * n / max)
                            .background(MaterialTheme.colorScheme.primary))
                        Text(y.takeLast(2).let { "'$it" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Bars("Top airports", flights.flatMap { f ->
            listOf("${code(f, true)} · ${f.depCity.ifBlank { f.depName }}", "${code(f, false)} · ${f.arrCity.ifBlank { f.arrName }}")
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }, "visit")
        Bars("Top routes", tally { listOf(code(it, true), code(it, false)).sorted().joinToString(" ⇄ ") })
        Bars("Airlines", tally { it.airlineName })
        AircraftStats(flights, miles)
        Bars("Seat class", tally { it.seatClass.replaceFirstChar { c -> c.uppercase() } })

        val withDist = flights.filter { it.distanceKm != null }
        if (withDist.isNotEmpty()) ClayCard {
            Text("Records", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
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
    val withAc = flights.filter { it.aircraftModel.isNotBlank() }
    if (withAc.isEmpty() && flights.none { it.registration.isNotBlank() }) return
    fun count(key: (FlightDto) -> String) = flights.map(key).filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    ClayCard {
        Text("Aircraft", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("${withAc.map { it.aircraftModel }.toSet().size}", "types", Modifier.weight(1f))
            MiniStat("${withAc.map { acFamily(it.aircraftModel) }.toSet().size}", "families", Modifier.weight(1f))
            MiniStat("${flights.map { it.registration }.filter { it.isNotBlank() }.toSet().size}", "tails", Modifier.weight(1f))
        }
        val byType = withAc.groupBy { it.aircraftModel }
        if (byType.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val far = byType.maxBy { (_, fs) -> fs.sumOf { it.distanceKm ?: 0.0 } }
            Fact("Most distance on one type", "${far.key} · ${fmtDist(far.value.sumOf { it.distanceKm ?: 0.0 }, miles)}")
            val aloft = byType.maxBy { (_, fs) -> fs.sumOf { durationMin(it) ?: 0L } }
            val aloftMin = aloft.value.sumOf { durationMin(it) ?: 0L }
            if (aloftMin > 0) Fact("Most time aloft on one type", "${aloft.key} · ${fmtDur(aloftMin)}")
        }
    }
    Bars("Most flown types", count { it.aircraftModel })
    Bars("Aircraft families", count { acFamily(it.aircraftModel) })
    Bars("Manufacturers", count { it.aircraftModel.trim().substringBefore(' ') })
    val modelOf = flights.filter { it.registration.isNotBlank() }.associate { it.registration to it.aircraftModel }
    Bars("Tails flown more than once", count { it.registration }.filter { it.second > 1 }
        .map { (r, n) -> (modelOf[r]?.takeIf { m -> m.isNotBlank() }?.let { "$r · $it" } ?: r) to n })
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    ClayCard(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bars(title: String, entries: List<Pair<String, Int>>, unit: String = "flight") {
    if (entries.isEmpty()) return
    ClayCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val max = entries.first().second.toFloat()
        entries.take(6).forEach { (name, n) ->
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodySmall)
                Text("$n $unit${if (n == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(n / max).background(MaterialTheme.colorScheme.primary))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
