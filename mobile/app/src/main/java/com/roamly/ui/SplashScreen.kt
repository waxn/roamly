package com.roamly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roamly.R
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Per-launch randomness: the contour wobble and the route's wander. */
private class SplashSeed(seed: Long) {
    private val rng = Random(seed)
    /** Harmonic offsets per contour ring, so each launch draws a different "terrain". */
    val rings: List<FloatArray> = List(6) { FloatArray(4) { rng.nextFloat() * 2f * PI.toFloat() } }
    val wobble: Float = 0.05f + rng.nextFloat() * 0.05f
    /** Normalised waypoints from an off-centre start into the middle. */
    val route: List<Offset> = buildList {
        val starts = listOf(Offset(0.08f, 0.92f), Offset(0.92f, 0.9f), Offset(0.1f, 0.12f), Offset(0.9f, 0.1f))
        var p = starts[rng.nextInt(starts.size)]
        add(p)
        repeat(3) {
            val tx = 0.5f + (rng.nextFloat() - 0.5f) * 0.55f
            val ty = 0.5f + (rng.nextFloat() - 0.5f) * 0.55f
            p = Offset(p.x + (tx - p.x) * 0.6f, p.y + (ty - p.y) * 0.6f)
            add(p)
        }
        add(Offset(0.5f, 0.5f))
    }
}

/**
 * The intro: topographic contour rings draw themselves outward from the
 * centre while a glowing route arcs in from a corner; the Roamly mark lands
 * with a spring and a tap of haptics, the wordmark rises under it, and the
 * whole thing fades into the app. The terrain and the route are reseeded on
 * every launch, so it is never quite the same twice.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val seed = remember { SplashSeed(System.nanoTime()) }
    val contours = remember { Animatable(0f) }
    val route = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { contours.animateTo(1f, tween(1300, easing = Emphasized)) }
        launch { glow.animateTo(1f, tween(900)) }
        launch { delay(120); route.animateTo(1f, tween(950, easing = Emphasized)) }
        delay(820)
        launch { mark.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
        delay(140)
        haptics.click()
        word.animateTo(1f, tween(380, easing = Emphasized))
        delay(320)
        out.animateTo(0f, tween(260))
        onFinished()
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = out.value }.background(c.bg),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // A warm glow pooling under the mark.
            drawCircle(
                Brush.radialGradient(listOf(c.accent.copy(alpha = 0.22f * glow.value), Color.Transparent), center, size.minDimension * 0.55f),
                radius = size.minDimension * 0.55f, center = center,
            )
            drawContours(seed, contours.value, c.lineStrong, c.accent)
            drawRoute(seed, route.value, c.accent, c.amber, c.text)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.roamly_favicon),
                contentDescription = "Roamly",
                modifier = Modifier.size(92.dp).graphicsLayer {
                    scaleX = 0.4f + 0.6f * mark.value; scaleY = 0.4f + 0.6f * mark.value
                    alpha = mark.value.coerceIn(0f, 1f)
                },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "ROAMLY",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, letterSpacing = (2 + 6 * word.value).sp),
                color = c.text,
                modifier = Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 24f },
            )
        }
    }
}

private fun DrawScope.drawContours(seed: SplashSeed, t: Float, line: Color, accent: Color) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.62f
    seed.rings.forEachIndexed { i, phase ->
        // Rings start one after another, inner first.
        val local = ((t * 1.6f) - i * 0.12f).coerceIn(0f, 1f)
        if (local <= 0f) return@forEachIndexed
        val r = maxR * (0.22f + i * 0.15f)
        val path = Path()
        val steps = 96
        for (s in 0..steps) {
            val a = s / steps.toFloat() * 2f * PI.toFloat()
            val wob = 1f + seed.wobble * (sin(2 * a + phase[0]) * 0.6f + sin(3 * a + phase[1]) * 0.3f + sin(5 * a + phase[2]) * 0.15f)
            val x = cx + cos(a + phase[3]) * r * wob
            val y = cy + sin(a + phase[3]) * r * wob
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val measure = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        measure.getSegment(0f, measure.length * local, seg, true)
        val color = if (i == 1) accent.copy(alpha = 0.35f) else line.copy(alpha = line.alpha * (1f - i * 0.12f))
        drawPath(seg, color, style = Stroke(width = (if (i == 1) 1.6f else 1.1f).dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawRoute(seed: SplashSeed, t: Float, from: Color, to: Color, head: Color) {
    if (t <= 0f) return
    val pts = seed.route.map { Offset(it.x * size.width, it.y * size.height) }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val p = pts[i - 1]; val q = pts[i]
            quadraticBezierTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
        }
        lineTo(pts.last().x, pts.last().y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val drawn = Path()
    measure.getSegment(0f, measure.length * t, drawn, true)
    val brush = Brush.linearGradient(listOf(from.copy(alpha = 0.2f), from, to), pts.first(), pts.last())
    drawPath(drawn, brush, alpha = 0.25f, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(drawn, brush, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (t < 0.97f) {
        val tip = measure.getPosition(measure.length * t)
        drawCircle(to.copy(alpha = 0.35f), 12.dp.toPx(), tip)
        drawCircle(head, 4.5.dp.toPx(), tip)
    }
}
