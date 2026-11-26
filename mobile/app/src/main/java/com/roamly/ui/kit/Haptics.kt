package com.roamly.ui.kit

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * One vocabulary of touch feedback for the whole app, so every control "feels"
 * the same. Each call maps to the richest primitive the phone supports:
 * `View.performHapticFeedback` constants (which honour the system's own
 * "touch feedback" switch and need no permission), and for the two patterned
 * effects ([success], [thud]) a `VibrationEffect.Composition` where the
 * vibrator can play primitives, falling back to a view constant otherwise.
 *
 * The in-app switch (Settings → Haptic feedback) is [HapticSettings.enabled],
 * mirrored from UserPreferences by MainActivity.
 */
object HapticSettings {
    @Volatile var enabled: Boolean = true
}

class Haptics internal constructor(private val view: View) {

    private val vibrator: Vibrator? by lazy { vibratorOf(view.context) }

    private fun perform(constant: Int) {
        if (!HapticSettings.enabled) return
        // Feedback is garnish: an unsupported constant or a detached view must
        // never take a tap (and the app) down with it.
        runCatching { view.performHapticFeedback(constant) }
    }

    /** A detent: a slider step, a picker wheel, a segment change. The lightest. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /** A selection between discrete options: tab, chip, segmented control. */
    fun select() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /** An ordinary press on a button, row or card. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A switch flipping. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
    )

    /** Something went through: saved, sent, deleted. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
    )

    /** Something was refused or failed. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
    )

    /** A long press landed, or a drag picked something up. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A gesture began (pull-to-refresh crossed its threshold, a sheet snapped). */
    fun gesture() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /** A bright two-beat "done": a scan matched, a post published. */
    fun success() {
        if (!HapticSettings.enabled) return
        if (!compose(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 70)) confirm()
    }

    /** A weighty, deliberate event: recording started or stopped, tracking switched. */
    fun thud() {
        if (!HapticSettings.enabled) return
        // PRIMITIVE_THUD only exists from API 31; touching it on 30 throws NoSuchFieldError.
        if (Build.VERSION.SDK_INT < 31 || !compose(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 120)) longPress()
    }

    private fun compose(first: Int, firstScale: Float, second: Int, secondScale: Float, gapMs: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val v = vibrator ?: return false
        if (!v.hasVibrator() || !v.areAllPrimitivesSupported(first, second)) return false
        return runCatching {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(first, firstScale)
                    .addPrimitive(second, secondScale, gapMs)
                    .compose()
            )
        }.isSuccess
    }

    private companion object {
        fun vibratorOf(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
