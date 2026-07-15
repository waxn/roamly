package com.roamly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.roamly.R
import com.roamly.ui.theme.Coral
import com.roamly.ui.theme.CoralDeep
import com.roamly.ui.theme.Sky
import com.roamly.ui.theme.Sunshine
import com.roamly.ui.theme.Teal
import com.roamly.ui.theme.Violet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val BG = Color(0xFF0F1117)
private val ACCENTS = listOf(Teal, Coral, Sky, Violet, Sunshine, CoralDeep)

private enum class SplashStyle { JOURNEY, CONSTELLATION, RADAR, GRIDZOOM }

/**
 * Config generated fresh on every launch so the intro is never the same twice.
 * `points` are normalized (0..1) screen coords; `blips` reuse the pair as
 * (angleTurns, radiusFrac) for the radar style.
 */
private class SplashConfig(seed: Long) {
    val rng = Random(seed)
    val style = SplashStyle.entries[rng.nextInt(SplashStyle.entries.size)]
    val accent = ACCENTS[rng.nextInt(ACCENTS.size)]
    val accent2 = ACCENTS.filter { it != accent }[rng.nextInt(ACCENTS.size - 1)]

    // A randomized route from a corner, wandering through waypoints to center.
    val points: List<Offset> = buildList {
        val corners = listOf(
            Offset(0.10f, 0.86f), Offset(0.88f, 0.84f),
            Offset(0.12f, 0.16f), Offset(0.86f, 0.18f),
        )
        var p = corners[rng.nextInt(corners.size)]
        add(p)
        val hops = 3 + rng.nextInt(3) // 3..5 waypoints
        repeat(hops) {
            // step toward center with lateral jitter
            val tx = 0.5f + (rng.nextFloat() - 0.5f) * 0.5f
            val ty = 0.5f + (rng.nextFloat() - 0.5f) * 0.5f
            p = Offset(p.x + (tx - p.x) * 0.55f, p.y + (ty - p.y) * 0.55f)
            add(p)
        }
        add(Offset(0.5f, 0.5f)) // arrive at the logo
    }

    // Scattered fixes for constellation / radar blips.
    val scatter: List<Offset> = buildList {
        repeat(7) {
            add(Offset(0.15f + rng.nextFloat() * 0.7f, 0.15f + rng.nextFloat() * 0.7f))
        }
    }
    val radarBlips: List<Offset> = buildList {
        repeat(6) { add(Offset(rng.nextFloat(), 0.25f + rng.nextFloat() * 0.65f)) }
    }
}

/**
 * Splash: a procedurally-generated map intro that differs on every launch.
 * One of four styles is chosen at random — a wandering GPS route, a
 * constellation of fixes wiring together, a radar sweep, or a fly-through
 * that zooms along a map grid — and the Roamly mark springs in at center.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val cfg = remember { SplashConfig(System.nanoTime()) }

    val trackProgress = remember { Animatable(0f) }
    val markScale = remember { Animatable(0f) }
    val markAlpha = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)),
        label = "pulse",
    )

    LaunchedEffect(Unit) {
        launch { trackProgress.animateTo(1f, tween(1150, easing = FastOutSlowInEasing)) }
        delay(560)
        launch { markAlpha.animateTo(1f, tween(280)) }
        markScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(360)
        screenAlpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(BG),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = trackProgress.value
            if (t <= 0f) return@Canvas
            when (cfg.style) {
                SplashStyle.JOURNEY -> drawJourney(cfg, t)
                SplashStyle.CONSTELLATION -> drawConstellation(cfg, t)
                SplashStyle.RADAR -> drawRadar(cfg, t)
                SplashStyle.GRIDZOOM -> drawGridZoom(cfg, t)
            }
        }

        Image(
            painter = painterResource(R.drawable.roamly_favicon),
            contentDescription = "Roamly",
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    val s = markScale.value * if (markAlpha.value > 0.9f) pulse else 1f
                    scaleX = s; scaleY = s
                    alpha = markAlpha.value
                },
        )
    }
}

/** Smooth path through normalized points, mapped to pixel space. */
private fun DrawScope.buildTrack(points: List<Offset>): Path {
    val w = size.width; val h = size.height
    val pts = points.map { Offset(it.x * w, it.y * h) }
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val prev = pts[i - 1]; val cur = pts[i]
        val mid = Offset((prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
        path.quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
    }
    path.lineTo(pts.last().x, pts.last().y)
    return path
}

private fun DrawScope.strokeTrack(path: Path, accent: Color, drawn: Float) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val total = measure.length
    val trimmed = Path()
    measure.getSegment(0f, total * drawn, trimmed, true)

    drawPath(trimmed, accent.copy(alpha = 0.16f), style = Stroke(18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(trimmed, accent.copy(alpha = 0.92f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    drawCircle(accent.copy(alpha = 0.5f), 6.dp.toPx(), measure.getPosition(0f))
    if (drawn < 0.98f) {
        val tip = measure.getPosition(total * drawn)
        drawCircle(Coral, 9.dp.toPx(), tip)
        drawCircle(Color.White.copy(alpha = 0.85f), 4.dp.toPx(), tip)
    }
}

private fun DrawScope.drawJourney(cfg: SplashConfig, t: Float) {
    val path = buildTrack(cfg.points)
    strokeTrack(path, cfg.accent, t)

    // Waypoint pins light up as the line passes them.
    val w = size.width; val h = size.height
    cfg.points.forEachIndexed { i, np ->
        val frac = i.toFloat() / (cfg.points.size - 1)
        if (i > 0 && i < cfg.points.size - 1 && t >= frac) {
            val pos = Offset(np.x * w, np.y * h)
            drawCircle(cfg.accent2, 5.dp.toPx(), pos)
            drawCircle(BG, 2.5.dp.toPx(), pos)
        }
    }
}

private fun DrawScope.drawConstellation(cfg: SplashConfig, t: Float) {
    val w = size.width; val h = size.height
    val nodes = cfg.scatter.map { Offset(it.x * w, it.y * h) } + Offset(w / 2f, h / 2f)
    val n = nodes.size
    val reveal = t * (n - 1) // how many edges/nodes are live

    // edges between consecutive revealed nodes
    for (i in 1 until n) {
        val edge = (reveal - (i - 1)).coerceIn(0f, 1f)
        if (edge <= 0f) break
        val a = nodes[i - 1]; val b = nodes[i]
        val end = Offset(a.x + (b.x - a.x) * edge, a.y + (b.y - a.y) * edge)
        drawLine(cfg.accent.copy(alpha = 0.85f), a, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
    // nodes pop in
    nodes.forEachIndexed { i, p ->
        if (reveal >= i - 0.5f) {
            drawCircle(cfg.accent.copy(alpha = 0.18f), 12.dp.toPx(), p)
            drawCircle(if (i == n - 1) Coral else cfg.accent2, 6.dp.toPx(), p)
            drawCircle(BG, 3.dp.toPx(), p)
        }
    }
}

private fun DrawScope.drawRadar(cfg: SplashConfig, t: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val center = Offset(cx, cy)
    val maxR = min(size.width, size.height) * 0.42f

    // range rings
    for (k in 1..3) {
        drawCircle(cfg.accent.copy(alpha = 0.14f), maxR * k / 3f, center, style = Stroke(1.5.dp.toPx()))
    }
    // sweeping wedge (1.25 turns over the animation)
    val sweep = t * 450f - 90f
    val rad = Math.toRadians(sweep.toDouble())
    val tip = Offset(cx + cos(rad).toFloat() * maxR, cy + sin(rad).toFloat() * maxR)
    drawLine(cfg.accent, center, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    // a few trailing sweep lines for a faded fan
    for (k in 1..5) {
        val a = Math.toRadians((sweep - k * 6f).toDouble())
        drawLine(
            cfg.accent.copy(alpha = 0.10f - k * 0.015f),
            center,
            Offset(cx + cos(a).toFloat() * maxR, cy + sin(a).toFloat() * maxR),
            strokeWidth = 2.dp.toPx(),
        )
    }
    // blips pop in sequence
    cfg.radarBlips.forEachIndexed { i, b ->
        val thr = i.toFloat() / cfg.radarBlips.size
        if (t >= thr) {
            val ang = Math.toRadians((b.x * 360f).toDouble())
            val r = b.y * maxR
            val pos = Offset(cx + cos(ang).toFloat() * r, cy + sin(ang).toFloat() * r)
            val pop = ((t - thr) * 6f).coerceIn(0f, 1f)
            drawCircle(cfg.accent2.copy(alpha = 0.2f), 10.dp.toPx() * pop, pos)
            drawCircle(cfg.accent2, 4.dp.toPx() * pop, pos)
        }
    }
}

private fun DrawScope.drawGridZoom(cfg: SplashConfig, t: Float) {
    val w = size.width; val h = size.height
    val cx = w / 2f; val cy = h / 2f
    val zoom = 0.7f + 0.7f * t          // fly in
    val panX = -0.18f * w * t
    val panY = 0.18f * h * t
    val cell = min(w, h) / 6f

    // Map grid slides + scales underneath — the "zooming along a map" feel.
    withTransform({
        scale(zoom, zoom, pivot = Offset(cx, cy))
        translate(panX, panY)
    }) {
        val gridColor = cfg.accent.copy(alpha = 0.16f)
        var x = -cell
        while (x <= w + cell) {
            drawLine(gridColor, Offset(x, -cell), Offset(x, h + cell), strokeWidth = 1.dp.toPx())
            x += cell
        }
        var y = -cell
        while (y <= h + cell) {
            drawLine(gridColor, Offset(-cell, y), Offset(w + cell, y), strokeWidth = 1.dp.toPx())
            y += cell
        }
        // faint lat/long crosses at intersections
        var gx = -cell
        while (gx <= w + cell) {
            var gy = -cell
            while (gy <= h + cell) {
                drawCircle(cfg.accent.copy(alpha = 0.12f), 1.5.dp.toPx(), Offset(gx, gy))
                gy += cell
            }
            gx += cell
        }
    }

    // Track drawn in fixed screen space so the camera appears to follow it.
    val path = buildTrack(cfg.points)
    strokeTrack(path, cfg.accent2, t)
}
