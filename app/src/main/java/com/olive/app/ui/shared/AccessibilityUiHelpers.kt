// All app roles: sets TalkBack names and focus for shared controls.
package com.olive.app.ui.shared

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import kotlin.math.max

internal val LocalAccessibilityMessagesVisible = staticCompositionLocalOf { true }

// TalkBack reads normal screen content first, temporary overlay content next, and persistent
// floating actions last. New overlapping layouts should use these values instead of raw numbers.
internal enum class AccessibilityTraversalOrder(val index: Float) {
    ScreenContent(0f),
    OverlayContent(1f),
    PersistentActions(2f),
}

/**
 * Marks a layout as the boundary where TalkBack applies the shared traversal order.
 *
 * @return The resulting [Modifier] for accessibility traversal group.
 */
internal fun Modifier.accessibilityTraversalGroup(): Modifier = semantics {
    isTraversalGroup = true
}

/**
 * Assigns one part of an overlapping layout to its place in the shared TalkBack order.
 *
 * @param order Order supplied to [accessibilityTraversal].
 * @return The resulting [Modifier] for accessibility traversal.
 */
internal fun Modifier.accessibilityTraversal(order: AccessibilityTraversalOrder): Modifier =
    semantics { traversalIndex = order.index }

/**
 * Exposes status changes through Compose semantics. Live regions let TalkBack
 * announce new content while preserving the user's current reading position.
 *
 * @param text Text string to display, parse, or send.
 * @param modifier Compose modifier applied to the root layout.
 * @param color Color supplied to [AccessibleMessage].
 * @param style Style supplied to [AccessibleMessage].
 * @param showText Whether to show text.
 */
@Composable
internal fun AccessibleMessage(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = LocalTextStyle.current,
    showText: Boolean = true,
) {
    val visible = LocalAccessibilityMessagesVisible.current
    if (!showText) {
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(text, visible, view) {
            if (visible) {
                @Suppress("DEPRECATION") // Announce once without leaving an invisible focusable node.
                view.announceForAccessibility(text)
            }
        }
        return
    }
    val semantics = modifier.semantics {
        if (visible) {
            liveRegion = LiveRegionMode.Polite
        } else hideFromAccessibility()
    }
    Text(text, color = color, style = style, modifier = semantics)
}

/**
 * Callers resolve action labels from Android resources; decorative icons keep a null description.
 *
 * @param imageVector Image vector supplied to [LocalizedIcon].
 * @param contentDescription Optional content description supplied to [LocalizedIcon].
 * @param modifier Compose modifier applied to the root layout.
 * @param tint Tint supplied to [LocalizedIcon].
 */
@Composable
internal fun LocalizedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector, contentDescription, modifier, tint)
}

/**
 * Identifies accessibility actions that should also reset the privacy timer.
 *
 * @param eventType Event type supplied to [isAccessibilityUserInteraction].
 * @return `true` if is accessibility user interaction, or `false` otherwise.
 */
internal fun isAccessibilityUserInteraction(eventType: Int): Boolean = eventType in setOf(
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
    AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
)

/**
 * Observe the existing outer content view, preserving its delegate and the Compose/WebView delegates below it.
 *
 * @param view View supplied to [observeAccessibilityInteractions].
 * @param onInteraction Callback invoked when interaction changes or occurs.
 */
internal fun observeAccessibilityInteractions(view: ViewGroup, onInteraction: () -> Unit) {
    val previous = ViewCompat.getAccessibilityDelegate(view)
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        /**
         * Resets the privacy timer before Android sends a TalkBack event.
         *
         * @param host Host supplied to [onRequestSendAccessibilityEvent].
         * @param child Child supplied to [onRequestSendAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         * @return `true` if on request send accessibility event, or `false` otherwise.
         */
        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup,
            child: View,
            event: AccessibilityEvent,
        ): Boolean {
            val accepted = previous?.onRequestSendAccessibilityEvent(host, child, event)
                ?: super.onRequestSendAccessibilityEvent(host, child, event)
            if (accepted && isAccessibilityUserInteraction(event.eventType)) onInteraction()
            return accepted
        }

        /**
         * Adds the whole field rule to one TalkBack event instead of reading separate pieces.
         *
         * @param host Host supplied to [dispatchPopulateAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         * @return `true` if dispatch populate accessibility event, or `false` otherwise.
         */
        override fun dispatchPopulateAccessibilityEvent(
            host: View,
            event: AccessibilityEvent
        ): Boolean =
            previous?.dispatchPopulateAccessibilityEvent(host, event)
                ?: super.dispatchPopulateAccessibilityEvent(host, event)

        /**
         * Finds the Android accessibility node provider used by TalkBack.
         *
         * @param host Host supplied to [getAccessibilityNodeProvider].
         * @return The resulting [AccessibilityNodeProviderCompat], or `null` if unavailable.
         */
        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? =
            previous?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)

        /**
         * Adds the control description to the TalkBack event.
         *
         * @param host Host supplied to [onInitializeAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onInitializeAccessibilityEvent(host, event)
            else super.onInitializeAccessibilityEvent(host, event)
        }

        /**
         * Adds the control role and description to the TalkBack node.
         *
         * @param host Host supplied to [onInitializeAccessibilityNodeInfo].
         * @param info Info supplied to [onInitializeAccessibilityNodeInfo].
         */
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            if (previous != null) previous.onInitializeAccessibilityNodeInfo(host, info)
            else super.onInitializeAccessibilityNodeInfo(host, info)
        }

        /**
         * Adds readable text when TalkBack announces this custom control.
         *
         * @param host Host supplied to [onPopulateAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onPopulateAccessibilityEvent(host, event)
            else super.onPopulateAccessibilityEvent(host, event)
        }

        /**
         * Accessibility ui helpers: Performs accessibility action.
         *
         * @param host Host supplied to [performAccessibilityAction].
         * @param action Action supplied to [performAccessibilityAction].
         * @param args Optional args supplied to [performAccessibilityAction].
         * @return `true` if perform accessibility action, or `false` otherwise.
         */
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            previous?.performAccessibilityAction(host, action, args)
                ?: super.performAccessibilityAction(host, action, args)

        /**
         * Sends accessibility event.
         *
         * @param host Host supplied to [sendAccessibilityEvent].
         * @param eventType Event type supplied to [sendAccessibilityEvent].
         */
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (previous != null) previous.sendAccessibilityEvent(host, eventType)
            else super.sendAccessibilityEvent(host, eventType)
        }

        /**
         * Sends accessibility event unchecked.
         *
         * @param host Host supplied to [sendAccessibilityEventUnchecked].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.sendAccessibilityEventUnchecked(host, event)
            else super.sendAccessibilityEventUnchecked(host, event)
        }
    })
}

/**
 * Recheck after focus changes and throughout keyboard resizing. Scrolling reveals the field without requesting focus, so it cannot steal input or TalkBack focus from another control.
 *
 * @return The resulting [Modifier] for keep visible while typing.
 */
internal fun Modifier.keepVisibleWhileTyping(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Keeps the private screen awake while an editable field has focus.
    LaunchedEffect(focused, keyboardBottom) {
        if (focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    this.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}

/**
 * UI sizing: every scrollbar is 4dp wide, sits 3dp from the edge, and keeps at least a 28dp thumb. Shared Olive scrollbar for forms and other regular vertically scrolling content.
 *
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    val viewportShare = size.height / (size.height + state.maxValue)
    val thumbHeight = max(28.dp.toPx(), trackHeight * viewportShare).coerceAtMost(trackHeight)
    val progress = state.value.toFloat() / state.maxValue.toFloat()
    val thumbTop = edge + (trackHeight - thumbHeight) * progress
    val x = size.width - width - edge

    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(ScrollThumb, Offset(x, thumbTop), Size(width, thumbHeight), CornerRadius(width))
}

/**
 * Include partial rows so the thumb follows each scroll pixel instead of jumping between item indices.
 *
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val last = info.visibleItemsInfo.lastOrNull() ?: return@drawWithContent
    val start = first.index + (info.viewportStartOffset - first.offset).toFloat()
        .div((first.size + info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f, 1f)
    val end = last.index + (info.viewportEndOffset - last.offset).toFloat()
        .div((last.size + info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f, 1f)
    drawLazyScrollbar(
        start,
        end,
        info.totalItemsCount,
        state.canScrollBackward,
        state.canScrollForward
    )
}

/**
 * Measure partial grid rows together; full-width headings and incomplete rows retain their actual item counts.
 *
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: LazyGridState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val rows = info.visibleItemsInfo.filter { it.row >= 0 }.groupBy { it.row }.toSortedMap()
    val first = rows.entries.firstOrNull() ?: return@drawWithContent
    val last = rows.entries.lastOrNull() ?: return@drawWithContent
    val start =
        first.value.minOf { it.index } + first.value.size * (info.viewportStartOffset - first.value.minOf { it.offset.y }).toFloat()
            .div((first.value.maxOf { it.size.height } + info.mainAxisItemSpacing).coerceAtLeast(1))
            .coerceIn(0f, 1f)
    val end =
        last.value.minOf { it.index } + last.value.size * (info.viewportEndOffset - last.value.minOf { it.offset.y }).toFloat()
            .div((last.value.maxOf { it.size.height } + info.mainAxisItemSpacing).coerceAtLeast(1))
            .coerceIn(0f, 1f)
    drawLazyScrollbar(
        start,
        end,
        info.totalItemsCount,
        state.canScrollBackward,
        state.canScrollForward
    )
}

/**
 * Draws lazy scrollbar.
 *
 * @param start Start supplied to [drawLazyScrollbar].
 * @param end End supplied to [drawLazyScrollbar].
 * @param total Total supplied to [drawLazyScrollbar].
 * @param canBack Whether can back.
 * @param canForward Whether can forward.
 */
private fun DrawScope.drawLazyScrollbar(
    start: Float,
    end: Float,
    total: Int,
    canBack: Boolean,
    canForward: Boolean
) {
    if (total <= 0 || (!canBack && !canForward)) return
    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    if (trackHeight <= 0f) return
    val visible = (end - start).coerceIn(0f, total.toFloat())
    val thumbHeight = max(28.dp.toPx(), trackHeight * visible / total).coerceAtMost(trackHeight)
    val range = (total - visible).coerceAtLeast(.001f)
    val progress = when {
        !canBack -> 0f
        !canForward -> 1f
        else -> (start / range).coerceIn(0f, 1f)
    }
    val x = size.width - width - edge
    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(
        ScrollThumb,
        Offset(x, edge + (trackHeight - thumbHeight) * progress),
        Size(width, thumbHeight),
        CornerRadius(width)
    )
}

/**
 * Focus a visible control once its window and content are ready, without refocusing on later updates.
 *
 * @param key Lookup key or cryptographic key, or null when not provided.
 * @param ready Whether to apply ready.
 * @return The resulting [FocusRequester] for remember entry focus.
 */
@Composable
internal fun rememberEntryFocus(key: Any?, ready: Boolean = true): androidx.compose.ui.focus.FocusRequester {
    val requester = remember(key) { androidx.compose.ui.focus.FocusRequester() }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var requested by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready, windowFocused) {
        if (ready && windowFocused && !requested) {
            withFrameNanos { }
            requested = requester.requestFocus()
        }
    }
    return requester
}
