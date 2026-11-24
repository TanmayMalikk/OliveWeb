// Shared UI target bounds registry, auto-scroll, and in-window popup containers for the live walkthrough.
package com.olive.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Active live-walkthrough directive broadcast to screens so they can switch tabs, auto-scroll,
 * and open popups automatically during the guided tour.
 *
 * @property stepId Active [com.olive.app.ui.users.shared.walkthrough.WalkthroughTourStep.id].
 * @property targetId Registered control identifier currently highlighted in red.
 * @property action Optional screen-specific action identifier (e.g. `"docs_open_upload"`).
 * @property settingsTabTag Optional `SettingsCategory.tag` to select automatically.
 */
internal data class WalkthroughDirective(
    val stepId: String,
    val targetId: String? = null,
    val action: String? = null,
    val settingsTabTag: String? = null,
)

internal val LocalWalkthroughDirective = staticCompositionLocalOf<WalkthroughDirective?> { null }

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
 * Registers this composable's root bounds in [LocalWalkthroughTargets] under [id] while composed
 * and automatically scrolls it into view whenever it becomes the active walkthrough target.
 *
 * @param id Unique walkthrough target identifier matching `WalkthroughTourStep.targetId` or `avoidId`.
 * @return Modified [Modifier] publishing live bounds and bring-into-view behavior for the tour.
 */
internal fun Modifier.walkthroughTarget(id: String): Modifier = composed {
    val registry = LocalWalkthroughTargets.current
    val directive = LocalWalkthroughDirective.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(directive?.stepId, directive?.targetId) {
        if (directive?.targetId == id) {
            withFrameNanos { }
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }
    DisposableEffect(registry, id) {
        onDispose { registry?.remove(id) }
    }
    val trackingModifier = if (registry == null) {
        Modifier
    } else {
        Modifier.onGloballyPositioned { coords ->
            if (coords.isAttached) {
                registry.update(id, coords.boundsInRoot())
            }
        }
    }
    this.bringIntoViewRequester(bringIntoViewRequester).then(trackingModifier)
}

/**
 * Renders an in-window Material3-styled bottom sheet inside the app root so [WalkthroughCoachmarkOverlay]
 * sits strictly on top, remains interactive, and measures exact root coordinates.
 *
 * @param sheetTargetId Walkthrough target ID bound to the sheet surface.
 * @param onDismiss Callback invoked when the scrim is tapped.
 * @param content Sheet body content.
 */
@Composable
internal fun WalkthroughInWindowBottomSheet(
    sheetTargetId: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = 84.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .heightIn(max = 390.dp)
                .walkthroughTarget(sheetTargetId)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * Renders an in-window Material3-styled dialog inside the app root so [WalkthroughCoachmarkOverlay]
 * renders strictly above it without separate-window focus or z-order occlusion.
 *
 * @param dialogTargetId Walkthrough target ID bound to the dialog card.
 * @param title Dialog heading composable.
 * @param onDismiss Callback invoked when the scrim is tapped.
 * @param confirmButton Primary action button composable.
 * @param dismissButton Optional dismiss/secondary button composable.
 * @param text Scrollable dialog body composable.
 */
@Composable
internal fun WalkthroughInWindowDialog(
    dialogTargetId: String,
    title: @Composable () -> Unit,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = 84.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .heightIn(max = 380.dp)
                .walkthroughTarget(dialogTargetId)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        title()
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    text()
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
