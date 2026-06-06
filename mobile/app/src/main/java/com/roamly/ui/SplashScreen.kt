package com.roamly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roamly.R
import com.roamly.ui.theme.Coral
import com.roamly.ui.theme.CoralDeep
import com.roamly.ui.theme.Sunshine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The animated opening screen. A black canvas (so the white Roamly mark reads
 * clearly) where the mark springs in over a softly pulsing coral halo and a slowly
 * rotating gradient ring, then the wordmark rises in. Calls [onFinished] once the
 * intro has played so the host can reveal the app.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // One-shot entrance animations.
    val markScale = remember { Animatable(0.6f) }
    val markAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textRise = remember { Animatable(40f) } // px, slides up to 0

    LaunchedEffect(Unit) {
        launch { markAlpha.animateTo(1f, tween(450)) }
        markScale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        )
        launch { textAlpha.animateTo(1f, tween(420)) }
        textRise.animateTo(0f, tween(420))
        // Short hold so the wordmark reads, then reveal — kept brief so cold-app
        // open feels snappy (the host also waits on auth state resolving).
        delay(250)
        onFinished()
    }

    // Continuous "alive" motion that keeps playing under the entrance.
    val infinite = rememberInfiniteTransition(label = "splash")
    val haloScale by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo",
    )
    val ringSpin by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    val breathe by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Pulsing coral glow behind the mark.
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            scaleX = haloScale
                            scaleY = haloScale
                            alpha = markAlpha.value * 0.9f
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(Coral.copy(alpha = 0.35f), CoralDeep.copy(alpha = 0.12f), Color.Transparent)
                            )
                        ),
                )
                // Slowly rotating gradient ring.
                Box(
                    modifier = Modifier
                        .size(184.dp)
                        .graphicsLayer {
                            rotationZ = ringSpin
                            alpha = markAlpha.value
                        }
                        .drawBehind {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    listOf(Coral, Sunshine, CoralDeep, Coral.copy(alpha = 0f), Coral)
                                ),
                                radius = size.minDimension / 2f,
                                style = Stroke(width = 5.dp.toPx()),
                            )
                        },
                )
                // The Roamly mark.
                Image(
                    painter = painterResource(R.drawable.roamly_favicon),
                    contentDescription = "Roamly",
                    modifier = Modifier
                        .size(130.dp)
                        .graphicsLayer {
                            val s = markScale.value * breathe
                            scaleX = s
                            scaleY = s
                            alpha = markAlpha.value
                        },
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "Roamly",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textRise.value
                },
            )
        }
    }
}
