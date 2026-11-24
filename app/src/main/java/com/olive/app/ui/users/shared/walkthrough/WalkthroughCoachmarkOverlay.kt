// Safe-app users and safe contacts: non-overlapping OliveGreen coachmark popup with red target highlight.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.shared.LocalWalkthroughTargets
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OlivePrivateLayout
import com.olive.app.ui.shared.OliveTextButton
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.ui.semantics.heading
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveIconButton
import com.olive.app.ui.shared.OliveOutlinedCard
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.SageGreen
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel

internal val LocalWalkthroughTour = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal val LocalWalkthroughSettingsTab = staticCompositionLocalOf<String?> { null }

private val WalkthroughHighlightRed = Color(0xFFDC2626)

/**
 * Computed top-left overlay coordinates (in pixels) keeping the walkthrough popup completely clear
 * of [avoidRect].
 *
 * @property leftPx Left offset relative to the overlay container.
 * @property topPx Top offset relative to the overlay container.
 */
internal data class CoachmarkPlacement(
    val leftPx: Float,
    val topPx: Float,
)

/**
 * Places a coachmark card of dimensions `[cardWidth] x [cardHeight]` either strictly below or strictly
 * above [avoidRect] so it never covers the highlighted UI feature, while staying within safe container bounds.
 *
 * @param avoidRect Bounds of the highlighted control or menu container to avoid covering.
 * @param containerWidth Overlay width in pixels.
 * @param containerHeight Overlay height in pixels.
 * @param cardWidth Measured coachmark card width in pixels.
 * @param cardHeight Measured coachmark card height in pixels.
 * @param safeTop Minimum top Y coordinate in pixels.
 * @param safeBottom Maximum bottom Y coordinate in pixels.
 * @param marginPx Minimum horizontal edge margin in pixels.
 * @param gapPx Minimum vertical separation between the card and [avoidRect] in pixels.
 * @return Non-overlapping [CoachmarkPlacement] inside the overlay container.
 */
internal fun computeCoachmarkPlacement(
    avoidRect: Rect,
    containerWidth: Float,
    containerHeight: Float,
    cardWidth: Float,
    cardHeight: Float,
    safeTop: Float,
    safeBottom: Float,
    marginPx: Float,
    gapPx: Float,
): CoachmarkPlacement {
    val minLeft = marginPx
    val maxLeft = (containerWidth - marginPx - cardWidth).coerceAtLeast(minLeft)
    val left = (avoidRect.center.x - cardWidth / 2f).coerceIn(minLeft, maxLeft)
    val maxTop = (safeBottom - cardHeight).coerceAtLeast(safeTop)
    val spaceBelow = safeBottom - avoidRect.bottom
    val spaceAbove = avoidRect.top - safeTop
    val preferBelow = spaceBelow >= cardHeight + gapPx &&
        (avoidRect.center.y < containerHeight * 0.55f || spaceBelow >= spaceAbove)
    val top = when {
        preferBelow -> (avoidRect.bottom + gapPx).coerceIn(safeTop, maxTop)
        spaceAbove >= cardHeight + gapPx -> (avoidRect.top - gapPx - cardHeight).coerceIn(safeTop, maxTop)
        spaceBelow >= spaceAbove -> (avoidRect.bottom + gapPx).coerceIn(safeTop, maxTop)
        else -> (avoidRect.top - gapPx - cardHeight).coerceIn(safeTop, maxTop)
    }
    return CoachmarkPlacement(leftPx = left, topPx = top)
}

/**
 * Renders the floating OliveGreen walkthrough coachmark popup positioned strictly above or below
 * the active UI feature and draws a non-blocking red highlight frame directly around that feature.
 *
 * @param role Account role used to resolve role-allowed tour steps.
 * @param activeStepId Active coachmark step ID, or `null` when no tour is running.
 * @param showBottomControls Whether floating corner controls (`Quick exit` / `+`) occupy the bottom edge.
 * @param onAdvance Callback invoked with the next step ID or `null` when finishing the tour.
 * @param onOpenContents Callback returning the user to the Walkthrough Table of Contents.
 * @param onSkip Callback dismissing the coachmark popup in place.
 * @param modifier Modifier applied to the overlay container.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WalkthroughCoachmarkOverlay(
    role: AccountRole,
    activeStepId: String?,
    showBottomControls: Boolean,
    onAdvance: (String?) -> Unit,
    onOpenContents: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeStepId == null) return
    val steps = remember(role) { walkthroughTourStepsForRole(role) }
    val activeIndex = steps.indexOfFirst { it.id == activeStepId }
    if (activeIndex < 0) return
    val step = steps[activeIndex]
    val prevId = nextWalkthroughTourStepId(steps, step.id, -1)
    val nextId = nextWalkthroughTourStepId(steps, step.id, 1)
    val registry = LocalWalkthroughTargets.current
    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var containerRootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    val rawTarget = step.targetId?.let { registry?.bounds?.get(it) }
    val rawAvoid = step.avoidId?.let { registry?.bounds?.get(it) } ?: rawTarget
    val localTarget = rawTarget
        ?.translate(-containerRootTopLeft.x, -containerRootTopLeft.y)
        ?.let { rect ->
            val h = containerSize.height.toFloat()
            if (h <= 0f) rect else Rect(
                left = rect.left,
                top = rect.top.coerceIn(0f, h),
                right = rect.right,
                bottom = rect.bottom.coerceIn(0f, h),
            )
        }
        ?.takeIf { it.width > 0f && it.height > 0f }
    val localAvoid = rawAvoid
        ?.translate(-containerRootTopLeft.x, -containerRootTopLeft.y)
        ?.let { rect ->
            val h = containerSize.height.toFloat()
            if (h <= 0f) rect else Rect(
                left = rect.left,
                top = rect.top.coerceIn(0f, h),
                right = rect.right,
                bottom = rect.bottom.coerceIn(0f, h),
            )
        }
        ?.takeIf { it.width > 0f && it.height > 0f }

    val marginPx = with(density) { 16.dp.toPx() }
    val gapPx = with(density) { 12.dp.toPx() }
    val safeTopPx = with(density) { 8.dp.toPx() }
    val bottomClearancePx = with(density) {
        if (showBottomControls && step.targetId != "quick_exit") {
            (OlivePrivateLayout.FloatingActionClearance + 8.dp).toPx()
        } else {
            16.dp.toPx()
        }
    }
    val placement = if (
        localAvoid != null &&
        containerSize.width > 0 &&
        containerSize.height > 0 &&
        cardSize.width > 0 &&
        cardSize.height > 0
    ) {
        computeCoachmarkPlacement(
            avoidRect = localAvoid,
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            cardWidth = cardSize.width.toFloat(),
            cardHeight = cardSize.height.toFloat(),
            safeTop = safeTopPx,
            safeBottom = (containerSize.height - bottomClearancePx).coerceAtLeast(safeTopPx),
            marginPx = marginPx,
            gapPx = gapPx,
        )
    } else null

    val pulseTransition = rememberInfiniteTransition(label = "walkthrough highlight pulse")
    val highlightAlpha by pulseTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "highlight alpha",
    )
    val titleText = stringResource(step.titleRes)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    containerSize = coords.size
                    containerRootTopLeft = coords.positionInRoot()
                }
            },
    ) {
        if (localTarget != null) {
            val padPx = with(density) { 4.dp.toPx() }
            val strokePx = with(density) { 3.dp.toPx() }
            val radiusPx = with(density) { 14.dp.toPx() }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("walkthrough_target_highlight"),
            ) {
                val left = (localTarget.left - padPx).coerceAtLeast(2f)
                val top = (localTarget.top - padPx).coerceAtLeast(2f)
                val right = (localTarget.right + padPx).coerceAtMost(size.width - 2f)
                val bottom = (localTarget.bottom + padPx).coerceAtMost(size.height - 2f)
                if (right > left && bottom > top) {
                    val boxOffset = Offset(left, top)
                    val boxSize = Size(right - left, bottom - top)
                    val corner = CornerRadius(radiusPx, radiusPx)
                    drawRoundRect(
                        color = WalkthroughHighlightRed.copy(alpha = 0.14f * highlightAlpha),
                        topLeft = boxOffset,
                        size = boxSize,
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        color = WalkthroughHighlightRed.copy(alpha = highlightAlpha),
                        topLeft = boxOffset,
                        size = boxSize,
                        cornerRadius = corner,
                        style = Stroke(width = strokePx),
                    )
                }
            }
        }

        val fallbackBottomDp = if (showBottomControls) OlivePrivateLayout.FloatingActionClearance + 8.dp else 16.dp
        val cardModifier = if (placement != null) {
            Modifier
                .offset { IntOffset(placement.leftPx.roundToInt(), placement.topPx.roundToInt()) }
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = fallbackBottomDp)
                .alpha(if (localAvoid == null || cardSize.height == 0) 1f else 0f)
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = OliveGreen,
            contentColor = Color.White,
            shadowElevation = 14.dp,
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
            modifier = cardModifier
                .widthIn(min = 276.dp, max = 356.dp)
                .onSizeChanged { cardSize = it }
                .testTag("walkthrough_active_feature")
                .semantics { paneTitle = titleText },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.walkthrough_tour_step_counter, activeIndex + 1, steps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    OliveTextButton(
                        onClick = onSkip,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier.testTag("walkthrough_tour_skip"),
                    ) {
                        Text(stringResource(R.string.walkthrough_tour_skip))
                    }
                }
                Text(
                    titleText,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    stringResource(step.bodyRes),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Color.White,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OliveTextButton(
                        onClick = onOpenContents,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier.testTag("walkthrough_tour_toc"),
                    ) {
                        Text(stringResource(R.string.walkthrough_tour_contents))
                    }
                    if (prevId != null) {
                        OliveTextButton(
                            onClick = { onAdvance(prevId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            modifier = Modifier.testTag("walkthrough_tour_prev"),
                        ) {
                            Text(stringResource(R.string.walkthrough_tour_back))
                        }
                    }
                    OliveButton(
                        onClick = { onAdvance(nextId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = OliveGreen,
                        ),
                        modifier = Modifier.testTag("walkthrough_tour_next"),
                    ) {
                        Text(
                            stringResource(
                                if (nextId == null) R.string.walkthrough_tour_done else R.string.walkthrough_tour_next,
                            ),
                        )
                        if (nextId != null) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the Walkthrough Table of Contents screen. Selecting a feature or tapping Start full
 * walkthrough opens that live feature screen with its multi-step floating [WalkthroughCoachmarkOverlay].
 *
 * @param session Shared session view model owning application state and navigation.
 * @param initialTopicId Unused legacy parameter retained for signature compatibility.
 */
@Composable
internal fun SafeWalkthroughScreen(
    session: SessionViewModel,
    @Suppress("UNUSED_PARAMETER") initialTopicId: String? = null,
) {
    val topics = remember(session.accountRole) { walkthroughTopicsForRole(session.accountRole) }
    val steps = remember(session.accountRole) { walkthroughTourStepsForRole(session.accountRole) }
    val groups = remember(topics) { walkthroughTopicGroups(topics) }
    val startTour = LocalWalkthroughTour.current

    FormColumn(scrollResetKey = "walkthrough_toc") {
        Row(
            Modifier.fillMaxWidth().testTag("walkthrough_screen"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OliveIconButton(
                onClick = session.navigation::back,
                modifier = Modifier.testTag("walkthrough_back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
            }
            Text(
                stringResource(R.string.support_walkthrough),
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }

        Heading(
            stringResource(R.string.walkthrough_toc_title),
            stringResource(R.string.walkthrough_subtitle),
        )

        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.walkthrough_toc_subtitle, groups.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                )
                OliveButton(
                    onClick = { steps.firstOrNull()?.let { startTour?.invoke(it.id) } },
                    modifier = Modifier.fillMaxWidth().testTag("walkthrough_start_tour"),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.walkthrough_start_tour), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().testTag("walkthrough_toc"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            var displayNumber = 0
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(group.category.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkOlive,
                        modifier = Modifier.semantics { heading() },
                    )
                    group.topics.forEach { topic ->
                        displayNumber += 1
                        WalkthroughTocCard(
                            index = displayNumber,
                            topic = topic,
                            stepCount = walkthroughStepCountForTopic(session.accountRole, topic.id),
                            onSelect = { startTour?.invoke(topic.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders an individual clickable feature card in the Walkthrough Table of Contents.
 *
 * @param index One-based display order index.
 * @param topic Feature metadata.
 * @param stepCount Number of live coachmark popups in [topic].
 * @param onSelect Callback launching the live guided walkthrough at [topic].
 */
@Composable
private fun WalkthroughTocCard(
    index: Int,
    topic: WalkthroughTopic,
    stepCount: Int,
    onSelect: () -> Unit,
) {
    OliveOutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().testTag("walkthrough_toc_${topic.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SageGreen,
                contentColor = DarkOlive,
            ) {
                Icon(
                    topic.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "$index. ${stringResource(topic.titleRes)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    stringResource(topic.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                if (stepCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = PaleOlive,
                        border = BorderStroke(1.dp, SageGreen),
                    ) {
                        Text(
                            stringResource(R.string.walkthrough_toc_step_count, stepCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = DarkOlive,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = OliveGreen,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
