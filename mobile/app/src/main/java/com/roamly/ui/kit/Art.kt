package com.roamly.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * A static sketch of a real track — the points projected into the box, no
 * tiles. Safe inside a scrolling list, where a live MapView is not (it is
 * disposed and recreated on every scroll and steals taps).
 */
@Composable
fun TrackSketch(
    lines: List<Pair<List<Pair<Double, Double>>, Color>>,
    modifier: Modifier = Modifier,
    background: Color = Atlas.colors.surfaceHigh,
) {
    val c = Atlas.colors
    val prepared = remember(lines) {
        // Downsample each line; find the bounds over all of them.
        val sampled = lines.map { (pts, col) ->
            val step = (pts.size / 400).coerceAtLeast(1)
            pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex } to col
        }
        val all = sampled.flatMap { it.first }
        if (all.isEmpty()) null else {
            val minLat = all.minOf { it.first }; val maxLat = all.maxOf { it.first }
            val minLng = all.minOf { it.second }; val maxLng = all.maxOf { it.second }
            Triple(sampled, minLat to maxLat, minLng to maxLng)
        }
    }
    Canvas(modifier) {
        drawRect(background)
        // A faint graticule so the sketch reads as a map.
        val grid = c.line
        for (i in 1..5) {
            drawLine(grid, Offset(size.width * i / 6f, 0f), Offset(size.width * i / 6f, size.height), 1f)
            drawLine(grid, Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f), 1f)
        }
        val p = prepared ?: return@Canvas
        val (sampled, latR, lngR) = p
        val pad = 20.dp.toPx()
        val midLat = Math.toRadians((latR.first + latR.second) / 2)
        val spanX = ((lngR.second - lngR.first) * cos(midLat)).coerceAtLeast(1e-6)
        val spanY = (latR.second - latR.first).coerceAtLeast(1e-6)
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val ox = (size.width - spanX.toFloat() * scale) / 2f
        val oy = (size.height - spanY.toFloat() * scale) / 2f
        fun proj(lat: Double, lng: Double) = Offset(
            ox + ((lng - lngR.first) * cos(midLat)).toFloat() * scale,
            oy + ((latR.second - lat)).toFloat() * scale,
        )
        sampled.forEach { (pts, col) ->
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
