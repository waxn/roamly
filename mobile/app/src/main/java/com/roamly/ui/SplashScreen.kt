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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.roamly.R
import com.roamly.ui.theme.Coral
import com.roamly.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash: a clean GPS track line draws itself across the screen, ending at
 * the Roamly logo in the center. The route resembles a real journey —
 * starting from one corner, visiting a few waypoints, then arriving at center.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val trackProgress = remember { Animatable(0f) }
    val markScale    = remember { Animatable(0f) }
    val markAlpha    = remember { Animatable(0f) }
    val screenAlpha  = remember { Animatable(1f) }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)),
        label = "pulse",
    )

    LaunchedEffect(Unit) {
        launch { trackProgress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        delay(550)
        launch { markAlpha.animateTo(1f, tween(280)) }
        markScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(350)
        screenAlpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(Color(0xFF0F1117)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cx = w / 2f; val cy = h / 2f

            // A readable journey: start bottom-left, head up through two clear
            // waypoints (like visiting landmarks), then arrive at center.
            val path = Path().apply {
                moveTo(0.08f * w, 0.88f * h)                      // start: bottom-left corner
                lineTo(0.22f * w, 0.75f * h)                       // head diagonally up-right
                cubicTo(
                    0.28f * w, 0.62f * h,
                    0.15f * w, 0.52f * h,
                    0.25f * w, 0.42f * h,                          // curve left, then bend right
                )
                lineTo(0.40f * w, 0.38f * h)                       // go east
                cubicTo(
                    0.50f * w, 0.36f * h,
                    0.55f * w, 0.28f * h,
                    0.65f * w, 0.30f * h,                          // gentle arc up to north area
                )
                lineTo(0.75f * w, 0.42f * h)                       // turn south-east
                cubicTo(
                    0.80f * w, 0.50f * h,
                    0.72f * w, 0.58f * h,
                    cx, cy,                                         // curve back to center
                )
            }

            val measure = PathMeasure()
            measure.setPath(path, false)
            val totalLen = measure.length
            val drawn = trackProgress.value

            if (drawn > 0f) {
                val trimmed = Path()
                measure.getSegment(0f, totalLen * drawn, trimmed, true)

                // Soft glow behind the line
                drawPath(
                    trimmed,
                    color = Teal.copy(alpha = 0.18f),
                    style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Main track line
                drawPath(
                    trimmed,
                    color = Teal.copy(alpha = 0.9f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                // Start dot (slightly behind)
                val startPos = measure.getPosition(0f)
                drawCircle(Teal.copy(alpha = 0.5f), 6.dp.toPx(), startPos)

                // Moving dot at tip
                if (drawn < 0.97f) {
                    val tipPos = measure.getPosition(totalLen * drawn)
                    drawCircle(Coral, 9.dp.toPx(), tipPos)
                    drawCircle(Color.White.copy(alpha = 0.85f), 4.dp.toPx(), tipPos)
                }

                // Waypoint dots at key turns (appear once the line passes them)
                data class WP(val frac: Float, val label: Float)
                val waypoints = listOf(0.25f, 0.52f, 0.75f)
                waypoints.forEach { frac ->
                    if (drawn >= frac) {
                        val wPos = measure.getPosition(totalLen * frac)
                        drawCircle(Teal, 5.dp.toPx(), wPos)
                        drawCircle(Color(0xFF0F1117), 2.5.dp.toPx(), wPos)
                    }
                }
            }
        }

        // Roamly mark springs in as the track reaches center
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
