package com.olive.app.ui.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/** Moves the artwork on press while leaving the layout and touch bounds in place. */
@Composable
internal fun Modifier.olivePress(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    depth: Float = .035f,
): Modifier {
    if (!enabled) return this

    val touchSource = remember { MutableInteractionSource() }
    val touchPressed = rememberOlivePressFeedback(touchSource)
    val controlPressed = rememberOlivePressFeedback(source)
    val pressed = touchPressed.value || controlPressed.value
    val movement = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) snap() else tween(120),
        label = "Olive press",
    )

    // Listen on the initial pass so scrolling containers can still delay or cancel the actual click.
    return pointerInput(touchSource) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            touchSource.tryEmit(press)
            var released = false
            try {
                released = waitForUpOrCancellation(PointerEventPass.Initial) != null
            } finally {
                touchSource.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }.drawWithContent {
        val amount = if (touchPressed.value || controlPressed.value) 1f else movement.value
        if (amount == 0f) {
            drawContent()
        } else {
            translate(top = 1.dp.toPx() * amount) {
                val factor = 1f - depth * amount
                scale(factor, factor) { this@drawWithContent.drawContent() }
            }
        }
    }
}

/** Keeps a quick tap visible for a few frames, even when down and up arrive together. */
@Composable
private fun rememberOlivePressFeedback(source: MutableInteractionSource): State<Boolean> {
    val pressed = remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.interactions.filterIsInstance<PressInteraction>().collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> pressed.value = true
                is PressInteraction.Release -> {
                    delay(55)
                    pressed.value = false
                }
                is PressInteraction.Cancel -> pressed.value = false
            }
        }
    }
    return pressed
}

/**
 * Remembers a mutable interaction source or falls back to an existing instance.
 *
 * @param source Optional supplied interaction source.
 * @return The resolved mutable interaction source.
 */
@Composable
internal fun rememberOliveInteraction(source: MutableInteractionSource? = null): MutableInteractionSource =
    source ?: remember { MutableInteractionSource() }
