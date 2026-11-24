// Shared shake animation, haptic feedback, and error-trigger helpers for invalid or missing form inputs.
package com.olive.app.ui.shared

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

internal const val ERROR_SHAKE_DURATION_MS = 420

/**
 * Builds the damped horizontal shake keyframe sequence `(offsetDp, timeMillis)` for [ERROR_SHAKE_DURATION_MS].
 *
 * @param amplitudeDp Peak horizontal displacement in dp.
 * @return Ordered keyframe pairs starting and ending at `0f` dp.
 */
internal fun errorShakeKeyframes(amplitudeDp: Float = 14f): List<Pair<Float, Int>> {
    val peak = amplitudeDp.coerceAtLeast(0f)
    return listOf(
        0f to 0,
        -peak to 50,
        peak to 100,
        -peak * 0.78f to 155,
        peak * 0.78f to 210,
        -peak * 0.5f to 265,
        peak * 0.5f to 320,
        -peak * 0.22f to 370,
        0f to ERROR_SHAKE_DURATION_MS,
    )
}

/**
 * Returns `true` when an invalid field should fire a fresh shake: either an error newly appeared or
 * changed, or an explicit submit retry incremented [currentTrigger] while [currentError] remained set.
 *
 * @param previousError Previously observed field error text, or `null`.
 * @param currentError Current field error text, or `null`.
 * @param previousTrigger Previously observed explicit submit trigger counter.
 * @param currentTrigger Current explicit submit trigger counter.
 * @return `true` if a shake should be dispatched.
 */
internal fun shouldTriggerFieldErrorShake(
    previousError: String?,
    currentError: String?,
    previousTrigger: Int,
    currentTrigger: Int,
): Boolean = currentError != null && (currentError != previousError || currentTrigger > previousTrigger)

/**
 * Checks whether Android system animator motion is enabled on this device.
 *
 * @param context Android context used to query global animator duration scale.
 * @return `true` when animator scale is positive, or `true` by default if unavailable.
 */
private fun isMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)

/**
 * Tracks a field's [error] text and explicit [shakeTrigger] counter and returns a monotonic shake count
 * that never fires on initial composition and re-fires on every invalid re-submission.
 *
 * @param error Current validation error text for the field, or `null`.
 * @param shakeTrigger Caller-incremented counter for repeated invalid submit attempts.
 * @return Monotonic trigger count to pass to [oliveErrorShake].
 */
@Composable
internal fun rememberFieldErrorShakeTrigger(error: String?, shakeTrigger: Int): Int {
    val count = remember { mutableIntStateOf(0) }
    val lastError = remember { mutableStateOf(error) }
    val lastTrigger = remember { mutableIntStateOf(shakeTrigger) }
    LaunchedEffect(error, shakeTrigger) {
        if (shouldTriggerFieldErrorShake(lastError.value, error, lastTrigger.intValue, shakeTrigger)) {
            count.intValue += 1
        }
        lastError.value = error
        lastTrigger.intValue = shakeTrigger
    }
    return count.intValue
}

/**
 * Applies an exaggerated finite horizontal shake and rejection haptic when [trigger] advances above zero,
 * respecting OS animator-duration reduced-motion settings.
 *
 * @receiver The modifier chain to extend.
 * @param trigger Monotonic counter incremented on each invalid attempt (`0` skips initial mount motion).
 * @param amplitudeDp Peak horizontal excursion in dp.
 * @return Modified [Modifier] with horizontal translation applied during an active shake.
 */
internal fun Modifier.oliveErrorShake(trigger: Int, amplitudeDp: Float = 14f): Modifier = composed {
    val offsetDp = remember { Animatable(0f) }
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(haptic)
        if (isMotionEnabled(context)) {
            offsetDp.snapTo(0f)
            offsetDp.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = ERROR_SHAKE_DURATION_MS
                    for ((value, at) in errorShakeKeyframes(amplitudeDp)) {
                        value at at
                    }
                },
            )
        } else {
            offsetDp.snapTo(0f)
        }
    }
    this.graphicsLayer { translationX = offsetDp.value.dp.toPx() }
}
