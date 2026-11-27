package com.roamly.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A small generated "map" for something with no picture of its own: a tinted
 * ground with faint contour rings and a route wandering across it. The same
 * [seed] always draws the same art, so a trip keeps its look.
 */
@Composable
fun RouteArt(seed: Int, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = Atlas.colors
    val color = tint ?: c.ramp[Math.floorMod(seed, c.ramp.size)]
    val geo = remember(seed) {
        val rng = Random(seed * 7919 + 17)
        val pts = buildList {
            var x = 0.08f + rng.nextFloat() * 0.15f
            var y = 0.25f + rng.nextFloat() * 0.5f
            add(Offset(x, y))
            repeat(5) {
                x += 0.12f + rng.nextFloat() * 0.08f
                y = (y + (rng.nextFloat() - 0.5f) * 0.45f).coerceIn(0.15f, 0.85f)
                add(Offset(x.coerceAtMost(0.94f), y))
            }
        }
        val center = Offset(0.3f + rng.nextFloat() * 0.4f, 0.3f + rng.nextFloat() * 0.4f)
        val phase = rng.nextFloat() * 6.28f
        Triple(pts, center, phase)
    }
    Canvas(modifier) {
        val (pts, center, phase) = geo
        drawRect(Brush.linearGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.08f)), Offset.Zero, Offset(size.width, size.height)))
        // Contours.
        val cx = center.x * size.width; val cy = center.y * size.height
        for (i in 1..4) {
            val r = size.minDimension * 0.18f * i
            val path = Path()
            for (s in 0..48) {
                val a = s / 48f * 2f * PI.toFloat()
                val w = 1f + 0.08f * sin(3 * a + phase + i)
                val x = cx + cos(a) * r * w * 1.4f
                val y = cy + sin(a) * r * w
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
        }
        // Route.
        val p = pts.map { Offset(it.x * size.width, it.y * size.height) }
        val route = Path().apply {
            moveTo(p[0].x, p[0].y)
            for (i in 1 until p.size) {
                val a = p[i - 1]; val b = p[i]
                quadraticBezierTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            lineTo(p.last().x, p.last().y)
        }
        drawPath(route, color.copy(alpha = 0.25f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 3.5.dp.toPx(), p.first())
        drawCircle(Color.White, 4.dp.toPx(), p.last())
        drawCircle(color, 2.2.dp.toPx(), p.last())
    }
}

/**
 * A static picture of a real track: OSM tiles for the basemap (night-tinted in
 * the dark theme) with the points projected over them. No live MapView, so it is
 * safe inside a scrolling list, where one is disposed and recreated on every
 * scroll and steals taps. Tiles that have not arrived yet leave the plain
 * [background], so it degrades to the old sketch offline.
 */
@Composable
fun TrackSketch(
    lines: List<Pair<List<Pair<Double, Double>>, Color>>,
    modifier: Modifier = Modifier,
    background: Color = Atlas.colors.surfaceHigh,
    pad: androidx.compose.ui.unit.Dp = 20.dp,
    grid: Boolean = true,
    basemap: Boolean = true,
) {
    val c = Atlas.colors
    val context = LocalContext.current
    val prepared = remember(lines) {
        // Downsample each line; find the bounds over all of them (in Mercator world units).
        val sampled = lines.map { (pts, col) ->
            val step = (pts.size / 400).coerceAtLeast(1)
            pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex } to col
        }
        val all = sampled.flatMap { it.first }
        if (all.isEmpty()) null else {
            val minX = all.minOf { StaticBasemap.worldX(it.second) }; val maxX = all.maxOf { StaticBasemap.worldX(it.second) }
            val minY = all.minOf { StaticBasemap.worldY(it.first) }; val maxY = all.maxOf { StaticBasemap.worldY(it.first) }
            Pair(sampled, doubleArrayOf(minX, maxX, minY, maxY))
        }
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val padPx = with(LocalDensity.current) { pad.toPx() }

    // The fit: pixels per world unit, and the world point at the centre of the box.
    val view = remember(prepared, box, padPx) {
        val p = prepared
        if (p == null || box.width == 0 || box.height == 0) null else {
            val b = p.second
            val spanX = (b[1] - b[0]).coerceAtLeast(1e-7)
            val spanY = (b[3] - b[2]).coerceAtLeast(1e-7)
            // A lone point (or a tiny track) should not zoom to street level.
            val scale = minOf((box.width - 2 * padPx) / spanX, (box.height - 2 * padPx) / spanY)
                .coerceAtMost(256.0 * (1 shl 15))
            doubleArrayOf(scale, (b[0] + b[1]) / 2, (b[2] + b[3]) / 2)
        }
    }

    // Tiles covering the box, at the zoom where each is drawn at least 1:1 (sharp, never upscaled).
    val tiles = remember(view, box, basemap) {
        val v = view
        if (!basemap || v == null) emptyList() else {
            val z = ceil(ln(v[0] / 256.0) / ln(2.0)).toInt().coerceIn(1, 17)
            val n = 1 shl z
            val halfW = box.width / 2.0 / v[0]; val halfH = box.height / 2.0 / v[0]
            val x0 = floor((v[1] - halfW) * n).toInt(); val x1 = floor((v[1] + halfW) * n).toInt()
            val y0 = floor((v[2] - halfH) * n).toInt().coerceAtLeast(0); val y1 = floor((v[2] + halfH) * n).toInt().coerceAtMost(n - 1)
            buildList { for (ty in y0..y1) for (tx in x0..x1) add(StaticBasemap.Tile(z, Math.floorMod(tx, n), ty) to tx) }
        }
    }
    var loaded by remember { mutableIntStateOf(0) }
    LaunchedEffect(tiles) {
        tiles.map { it.first }.distinctBy { it.key }.filter { StaticBasemap.cached(it) == null }.forEach { t ->
            launch { if (StaticBasemap.load(context, t) != null) loaded++ }
        }
    }
    val tint = remember(c.isDark) {
        if (c.isDark) ColorFilter.colorMatrix(ColorMatrix(NIGHT_TILE_MATRIX)) else null
    }

    Canvas(modifier.onSizeChanged { box = it }) {
        drawRect(background)
        // Reading it here is what redraws the canvas as tiles arrive.
        if (loaded < 0) return@Canvas
        val v = view
        if (basemap && v != null) {
            val n = 1 shl ((tiles.firstOrNull()?.first?.z) ?: 1)
            tiles.forEach { (t, tx) ->
                val img = StaticBasemap.cached(t) ?: return@forEach
                val left = size.width / 2 + ((tx.toDouble() / n) - v[1]) * v[0]
                val top = size.height / 2 + ((t.y.toDouble() / n) - v[2]) * v[0]
                val px = v[0] / n
                // +1 px so neighbouring tiles overlap rather than leave hairline seams.
                drawImage(
                    img, dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt()),
                    dstSize = IntSize(ceil(px).toInt() + 1, ceil(px).toInt() + 1),
                    colorFilter = tint, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
            // Keep the track legible over busy tiles.
            drawRect(background.copy(alpha = 0.18f))
        } else if (grid) {
            // A faint graticule so the plain sketch reads as a map.
            for (i in 1..5) {
                drawLine(c.line, Offset(size.width * i / 6f, 0f), Offset(size.width * i / 6f, size.height), 1f)
                drawLine(c.line, Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f), 1f)
            }
        }
        val p = prepared ?: return@Canvas
        if (v == null) return@Canvas
        fun proj(lat: Double, lng: Double) = Offset(
            (size.width / 2 + (StaticBasemap.worldX(lng) - v[1]) * v[0]).toFloat(),
            (size.height / 2 + (StaticBasemap.worldY(lat) - v[2]) * v[0]).toFloat(),
        )
        p.first.forEach { (pts, col) ->
            if (pts.size < 2) return@forEach
            val path = Path()
            pts.forEachIndexed { i, (la, lo) -> val o = proj(la, lo); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, col.copy(alpha = 0.28f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, col, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(col, 4.dp.toPx(), proj(pts.first().first, pts.first().second))
            drawCircle(Color.White, 4.5.dp.toPx(), proj(pts.last().first, pts.last().second))
            drawCircle(col, 2.5.dp.toPx(), proj(pts.last().first, pts.last().second))
        }
    }
}

/** Same night tint the live maps put on OSM tiles (see MapEngine's NIGHT_MATRIX). */
private val NIGHT_TILE_MATRIX = floatArrayOf(
    -0.26f, -0.50f, -0.10f, 0f, 236f,
    -0.27f, -0.53f, -0.10f, 0f, 242f,
    -0.28f, -0.55f, -0.11f, 0f, 255f,
     0f,     0f,     0f,    1f, 0f,
)
