package com.roamly.ui.kit

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** Spring presets: everything in the app moves on one of these. */
object AtlasMotion {
    /** Presses, toggles, small controls snapping. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow * 1.6f)
    /** Panels, indicators sliding between positions. */
    fun <T> glide() = spring<T>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    /** Big things entering: sheets, hero numbers. */
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
}

enum class Feel { None, Tick, Select, Click, Confirm, Heavy }

internal fun Haptics.play(feel: Feel) = runCatching { playUnsafe(feel) }.let { }

private fun Haptics.playUnsafe(feel: Feel) = when (feel) {
    Feel.None -> Unit
    Feel.Tick -> tick()
    Feel.Select -> select()
    Feel.Click -> click()
    Feel.Confirm -> confirm()
    Feel.Heavy -> thud()
}

/**
 * The app's one way to make something tappable: a ripple, a springy shrink
 * while held, and a haptic on release. [pressedScale] 1f turns the shrink off
 * (long full-width rows read better without it).
 */
fun Modifier.tappable(
    enabled: Boolean = true,
    feel: Feel = Feel.Click,
    pressedScale: Float = 0.96f,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = AtlasMotion.snappy(),
        label = "press",
    )
    val base = this.graphicsLayer { scaleX = scale; scaleY = scale }
    if (onLongClick != null) {
        base.combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onLongClick = { haptics.longPress(); onLongClick() },
            onClick = { haptics.play(feel); onClick() },
        )
    } else {
        base.clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onClick = { haptics.play(feel); onClick() },
        )
    }
}

/** Wrap a callback so it plays [feel] first — for M3 components that take a lambda. */
@Composable
fun withFeel(feel: Feel = Feel.Click, action: () -> Unit): () -> Unit {
    val haptics = rememberHaptics()
    return { haptics.play(feel); action() }
}
