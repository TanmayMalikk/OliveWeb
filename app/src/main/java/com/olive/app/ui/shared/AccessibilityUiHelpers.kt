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
import androidx.compose.material3.*
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

// Marks a layout as the boundary where TalkBack applies the shared traversal order.
internal fun Modifier.accessibilityTraversalGroup(): Modifier = semantics {
    isTraversalGroup = true
}

// Assigns one part of an overlapping layout to its place in the shared TalkBack order.
internal fun Modifier.accessibilityTraversal(order: AccessibilityTraversalOrder): Modifier =
    semantics { traversalIndex = order.index }

/**
 * Exposes status changes through Compose semantics. Live regions let TalkBack
 * announce new content while preserving the user's current reading position.
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
    val semantics = modifier.semantics {
        if (visible) {
            liveRegion = LiveRegionMode.Polite
            if (!showText) contentDescription = text
        } else hideFromAccessibility()
    }
    if (showText) Text(text, color = color, style = style, modifier = semantics)
    else androidx.compose.foundation.layout.Box(semantics)
}

// Callers resolve action labels from Android resources; decorative icons keep a null description.
@Composable
internal fun LocalizedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector, contentDescription, modifier, tint)
}

// Identifies accessibility actions that should also reset the privacy timer.
internal fun isAccessibilityUserInteraction(eventType: Int): Boolean = eventType in setOf(
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
    AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
)

// Observe the existing outer content view, preserving its delegate and the Compose/WebView delegates below it.
internal fun observeAccessibilityInteractions(view: ViewGroup, onInteraction: () -> Unit) {
    val previous = ViewCompat.getAccessibilityDelegate(view)
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        // Resets the privacy timer before Android sends a TalkBack event.
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

        // Adds the whole field rule to one TalkBack event instead of reading separate pieces.
        override fun dispatchPopulateAccessibilityEvent(
            host: View,
            event: AccessibilityEvent
        ): Boolean =
            previous?.dispatchPopulateAccessibilityEvent(host, event)
                ?: super.dispatchPopulateAccessibilityEvent(host, event)

        // Finds the Android accessibility node provider used by TalkBack.
        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? =
            previous?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)

        // Adds the control description to the TalkBack event.
        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onInitializeAccessibilityEvent(host, event)
            else super.onInitializeAccessibilityEvent(host, event)
        }

        // Adds the control role and description to the TalkBack node.
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            if (previous != null) previous.onInitializeAccessibilityNodeInfo(host, info)
            else super.onInitializeAccessibilityNodeInfo(host, info)
        }

        // Adds readable text when TalkBack announces this custom control.
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onPopulateAccessibilityEvent(host, event)
            else super.onPopulateAccessibilityEvent(host, event)
        }

        // Accessibility ui helpers: Performs accessibility action.
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            previous?.performAccessibilityAction(host, action, args)
                ?: super.performAccessibilityAction(host, action, args)

        // Sends accessibility event.
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (previous != null) previous.sendAccessibilityEvent(host, eventType)
            else super.sendAccessibilityEvent(host, eventType)
        }

        // Sends accessibility event unchecked.
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.sendAccessibilityEventUnchecked(host, event)
            else super.sendAccessibilityEventUnchecked(host, event)
        }
    })
}

// Recheck after focus changes and throughout keyboard resizing. Scrolling reveals the field
// without requesting focus, so it cannot steal input or TalkBack focus from another control.
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

// UI color: the first alpha controls the faint track and the second controls the darker moving thumb.
private val ScrollTrack = Color(0x2445613C)
private val ScrollThumb = Color(0xB345613C)

// UI sizing: every scrollbar is 4dp wide, sits 3dp from the edge, and keeps at least a 28dp thumb.
// Shared Olive scrollbar for forms and other regular vertically scrolling content.
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

// Fixed tables use this matching bottom scrollbar for sideways column movement.
internal fun Modifier.oliveHorizontalScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val height = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackWidth = size.width - edge * 2
    val viewportShare = size.width / (size.width + state.maxValue)
    val thumbWidth = max(28.dp.toPx(), trackWidth * viewportShare).coerceAtMost(trackWidth)
    val progress = state.value.toFloat() / state.maxValue.toFloat()
    val thumbLeft = edge + (trackWidth - thumbWidth) * progress
    val y = size.height - height - edge

    drawRoundRect(ScrollTrack, Offset(edge, y), Size(trackWidth, height), CornerRadius(height))
    drawRoundRect(ScrollThumb, Offset(thumbLeft, y), Size(thumbWidth, height), CornerRadius(height))
}

// Include partial rows so the thumb follows each scroll pixel instead of jumping between item indices.
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

// Measure partial grid rows together; full-width headings and incomplete rows retain their actual item counts.
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

// Draws lazy scrollbar.
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

// UI colors: change these named vars to recolor every screen that uses the shared theme instead of editing individual composables.
internal val OliveGreen = Color(0xFF45613C)
internal val Ink = Color(0xFF253322)
internal val Cream = Color(0xFFFAF9F5)
internal val Muted = Color(0xFF677A3B)
internal val Paper = Color.White
internal val PaleOlive = Color(0xFFE7EDDD)
internal val WarmWhite = Color(0xFFF0F0E8)
internal val LightSage = Color(0xFFE1E8D7)
internal val SageGreen = Color(0xFFD2DDC6)
internal val SoftGray = Color(0xFFD6D9CD)
internal val DarkOlive = Color(0xFF314B28)
internal val MossGreen = Color(0xFF6C7A3D)
internal val OliveBrown = Color(0xFF7A5326)

// Every cover and the private app use this one shared color system.
// Text sizing: Material's default typography is intentionally used for normal copy; explicit sp values are documented beside special display text.
// Language sizing note: use MaterialTheme typography for ordinary translated copy; keep explicit sp values only for brand, keypad, or editorial display text.
// If the whole app needs larger default text later, define typography here instead of changing every screen separately.
@Composable
internal fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = OliveGreen, onPrimary = Paper,
            primaryContainer = PaleOlive, onPrimaryContainer = Ink,
            secondary = OliveGreen, onSecondary = Paper,
            secondaryContainer = PaleOlive, onSecondaryContainer = Ink,
            background = Cream, onBackground = Ink,
            surface = Cream, onSurface = Ink,
            surfaceVariant = WarmWhite, onSurfaceVariant = Muted,
            outline = SoftGray,
        ), content = content
    )
}
