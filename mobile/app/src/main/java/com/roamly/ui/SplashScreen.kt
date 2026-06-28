package com.roamly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.roamly.R
import com.roamly.ui.theme.Coral
import com.roamly.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash screen: a GPS track line traces its way across the screen, ending at
 * the Roamly icon in the center. The mark springs in as the line approaches,
 * then the whole screen fades out.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val trackProgress = remember { Animatable(0f) }
    val markScale    = remember { Animatable(0f) }
    val markAlpha    = remember { Animatable(0f) }
    val screenAlpha  = remember { Animatable(1f) }

    // Subtle pulse on the mark while we wait
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)),
        label = "pulse",
    )

    LaunchedEffect(Unit) {
        // 1. Draw the track across the screen (800ms)
        launch { trackProgress.animateTo(1f, tween(900, easing = LinearEasing)) }
        delay(400)
        // 2. Icon springs in partway through the track draw
        launch { markAlpha.animateTo(1f, tween(300)) }
        markScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(300)
        // 3. Fade out
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
        // GPS track line drawn across the full canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // A "track" path that wanders across the screen, ending at center
            val path = Path().apply {
                moveTo(0.05f * w, 0.78f * h)
                cubicTo(0.12f * w, 0.55f * h,  0.08f * w, 0.30f * h,  0.20f * w, 0.22f * h)
                cubicTo(0.32f * w, 0.14f * h,  0.38f * w, 0.38f * h,  0.28f * w, 0.52f * h)
                cubicTo(0.18f * w, 0.66f * h,  0.34f * w, 0.75f * h,  0.45f * w, 0.68f * h)
                cubicTo(0.56f * w, 0.61f * h,  0.50f * w, 0.42f * h,  0.62f * w, 0.36f * h)
                cubicTo(0.74f * w, 0.30f * h,  0.82f * w, 0.18f * h,  0.88f * w, 0.12f * h)
                cubicTo(0.94f * w, 0.06f * h,  0.90f * w, 0.38f * h,  0.80f * w, 0.50f * h)
                cubicTo(0.70f * w, 0.62f * h,  0.75f * w, 0.80f * h,  0.65f * w, 0.85f * h)
                cubicTo(0.55f * w, 0.90f * h,  0.48f * w, 0.72f * h,  cx,       cy)
            }

            // Measure path to get trimmed sub-path
            val measure = PathMeasure()
            measure.setPath(path, false)
            val totalLen = measure.length

            val drawn = trackProgress.value
            if (drawn > 0f) {
                val trimmed = Path()
                measure.getSegment(0f, totalLen * drawn, trimmed, true)

                // Glow shadow (thicker, lower alpha)
                drawPath(
                    trimmed,
                    color = Teal.copy(alpha = 0.25f),
                    style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Main line
                drawPath(
                    trimmed,
                    color = Teal.copy(alpha = 0.85f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                // Moving "current location" dot at the tip
                if (drawn < 0.98f) {
                    val pos = FloatArray(2)
                    measure.getPosition(totalLen * drawn, pos)
                    drawCircle(
                        color = Coral,
                        radius = 8.dp.toPx(),
                        center = Offset(pos[0], pos[1]),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = 3.dp.toPx(),
                        center = Offset(pos[0], pos[1]),
                    )
                }
            }
        }

        // Roamly mark, springs in as the track reaches the center
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
