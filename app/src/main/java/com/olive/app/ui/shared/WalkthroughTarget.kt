// Shared UI target bounds registry for positioning non-overlapping walkthrough popups and red highlights.
package com.olive.app.ui.shared

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs

/**
 * Tracks root-window layout rectangles for tagged UI controls so the live Walkthrough can draw
 * a red highlight frame directly around each control and place its popup without covering it.
 */
internal class WalkthroughTargetRegistry {
    val bounds = mutableStateMapOf<String, Rect>()

    /**
     * Stores [rect] under [id], ignoring sub-pixel jitter smaller than `1px` to avoid recomposition loops.
     *
     * @param id Stable target identifier referenced by walkthrough tour steps.
     * @param rect Measured root-coordinate bounds of the composable.
     */
    fun update(id: String, rect: Rect) {
        val current = bounds[id]
        if (current != null &&
            abs(current.left - rect.left) < 1f &&
            abs(current.top - rect.top) < 1f &&
            abs(current.right - rect.right) < 1f &&
            abs(current.bottom - rect.bottom) < 1f
        ) {
            return
        }
        bounds[id] = rect
    }

    /**
     * Unregisters [id] when the target composable leaves the composition tree.
     *
     * @param id Target identifier to clear.
     */
    fun remove(id: String) {
        bounds.remove(id)
    }
}

internal val LocalWalkthroughTargets = staticCompositionLocalOf<WalkthroughTargetRegistry?> { null }

/**
 * Registers this composable's root bounds in [LocalWalkthroughTargets] under [id] while composed.
 *
 * @param id Unique walkthrough target identifier matching `WalkthroughTourStep.targetId` or `avoidId`.
 * @return Modified [Modifier] publishing live bounds for walkthrough highlighting and placement.
 */
internal fun Modifier.walkthroughTarget(id: String): Modifier = composed {
    val registry = LocalWalkthroughTargets.current
    DisposableEffect(registry, id) {
        onDispose { registry?.remove(id) }
    }
    if (registry == null) {
        this
    } else {
        this.onGloballyPositioned { coords ->
            if (coords.isAttached) {
                registry.update(id, coords.boundsInRoot())
            }
        }
    }
}
