package com.olive.app.ui.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.olive.app.R

private val OliveButtonElevation: ButtonElevation
    @Composable get() = ButtonDefaults.buttonElevation(
        defaultElevation = 4.dp, pressedElevation = 1.dp, focusedElevation = 4.dp, hoveredElevation = 6.dp,
    )

private val OliveTonalElevation: ButtonElevation
    @Composable get() = ButtonDefaults.filledTonalButtonElevation(
        defaultElevation = 2.dp, pressedElevation = 0.dp, focusedElevation = 2.dp, hoveredElevation = 4.dp,
    )

/**
 * Renders an elevated Olive button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param border The optional stroke drawn around the component.
 * @param contentPadding The spacing between the component's edges and its content.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OlivePill,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = OliveButtonElevation,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.Button(
        onClick, modifier.olivePress(source, enabled), enabled, shape, colors, elevation, border,
        contentPadding, source, content,
    )
}

/**
 * Renders a filled tonal Olive button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param border The optional stroke drawn around the component.
 * @param contentPadding The spacing between the component's edges and its content.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OlivePill,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = OliveTonalElevation,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.FilledTonalButton(
        onClick, modifier.olivePress(source, enabled), enabled, shape, colors, elevation, border,
        contentPadding, source, content,
    )
}

/**
 * Renders an outlined Olive button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param border The optional stroke drawn around the component.
 * @param contentPadding The spacing between the component's edges and its content.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OlivePill,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.OutlinedButton(
        onClick, modifier.olivePress(source, enabled), enabled, shape, colors, elevation, border,
        contentPadding, source, content,
    )
}

/**
 * Renders a text-only Olive button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param border The optional stroke drawn around the component.
 * @param contentPadding The spacing between the component's edges and its content.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OlivePill,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.TextButton(
        onClick, modifier.olivePress(source, enabled), enabled, shape, colors, elevation, border,
        contentPadding, source, content,
    )
}

/**
 * Renders a standard icon button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.standardShape,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.IconButton(
        onClick, modifier.olivePress(source, enabled, depth = .08f), enabled,
        shape = shape, colors = colors, interactionSource = source, content = content,
    )
}

/**
 * Renders a filled icon button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.FilledIconButton(
        onClick, modifier.olivePress(source, enabled, depth = .08f), enabled,
        shape = shape, colors = colors, interactionSource = source, content = content,
    )
}

/**
 * Renders a filled tonal icon button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.FilledTonalIconButton(
        onClick, modifier.olivePress(source, enabled, depth = .08f), enabled,
        shape = shape, colors = colors, interactionSource = source, content = content,
    )
}

/**
 * Renders an outlined icon button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param enabled Whether the control or feature is enabled.
 * @param shape The shape used to draw the component.
 * @param colors The colors for the control's visual states.
 * @param border The optional stroke drawn around the component.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveOutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.outlinedShape,
    colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    border: BorderStroke? = IconButtonDefaults.outlinedIconButtonBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.OutlinedIconButton(
        onClick, modifier.olivePress(source, enabled, depth = .08f), enabled,
        shape = shape, colors = colors, border = border, interactionSource = source, content = content,
    )
}

/**
 * Renders a floating action button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param shape The shape used to draw the component.
 * @param containerColor The background color of the component.
 * @param contentColor The color inherited by text and icons in the content.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = OlivePill,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation =
        FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 1.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.FloatingActionButton(
        onClick, modifier.olivePress(source, depth = .06f), shape, containerColor, contentColor,
        elevation, source, content,
    )
}

/**
 * Renders a small floating action button with themed press animations.
 *
 * @param onClick Runs when the user activates the control.
 * @param modifier The Compose modifier applied to the component.
 * @param shape The shape used to draw the component.
 * @param containerColor The background color of the component.
 * @param contentColor The color inherited by text and icons in the content.
 * @param elevation The elevation used for the component's shadow or surface treatment.
 * @param interactionSource The source that reports press and focus interactions.
 * @param content The composable content to render inside the component.
 */
@Composable
internal fun OliveSmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = OlivePill,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation =
        FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 1.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.SmallFloatingActionButton(
        onClick, modifier.olivePress(source, depth = .06f), shape, containerColor, contentColor,
        elevation, source, content,
    )
}

internal enum class SkeletonLayout {
    Conversation, ChatList, TicketList, Contacts, Documents, Notes, Checklist, SharedPlans,
    History, Accounts, StaffWorkload, DashboardCounts, EmergencyAlerts, HotlineDirectory,
    SupportAssignment, NearbySupport, Directions, NewsFeed, NewsArticle, FitnessSummary,
    FitnessActivity, FitnessVitals, FitnessNutrition, FoodResults, Form, Action, Image, Video,
    DocumentPage, TextDocument,
}

/**
 * Draws the shape of the incoming content without showing sample data or controls.
 *
 * @param layout Layout supplied to [LoadingSkeleton].
 * @param modifier Compose modifier applied to the root layout.
 * @param rows Rows supplied to [LoadingSkeleton].
 */
@Composable
internal fun LoadingSkeleton(
    layout: SkeletonLayout,
    modifier: Modifier = Modifier.fillMaxWidth(),
    rows: Int = 2,
) {
    val label = stringResource(R.string.ui_loading)
    Column(
        modifier.testTag("loading_skeleton").semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (layout) {
            SkeletonLayout.Conversation -> repeat(rows) { index ->
                Box(Modifier.fillMaxWidth()) {
                    val bubble = Modifier.fillMaxWidth(if (index % 2 == 0) .64f else .46f)
                        .align(if (index % 2 == 0) Alignment.CenterStart else Alignment.CenterEnd)
                    SkeletonCard(bubble) { SkeletonLines(.84f, .57f) }
                }
            }
            SkeletonLayout.ChatList, SkeletonLayout.TicketList, SkeletonLayout.Contacts -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val contact = layout == SkeletonLayout.Contacts
                        SkeletonBlock(Modifier.size(if (contact) 24.dp else 40.dp),
                            if (layout == SkeletonLayout.ChatList) CircleShape else MaterialTheme.shapes.small)
                        SkeletonLines(.58f, .78f, modifier = Modifier.weight(1f))
                        if (!contact) SkeletonBlock(Modifier.width(28.dp).height(10.dp))
                    }
                }
            }
            SkeletonLayout.Documents -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SkeletonBlock(Modifier.width(34.dp).height(44.dp))
                        SkeletonLines(.68f, .42f, modifier = Modifier.weight(1f))
                        SkeletonBlock(Modifier.size(24.dp), CircleShape)
                    }
                }
            }
            SkeletonLayout.Notes -> repeat(rows) {
                SkeletonCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SkeletonBlock(Modifier.width(46.dp).height(10.dp))
                        SkeletonBlock(Modifier.size(16.dp))
                    }
                    SkeletonLines(.52f, .91f, .70f)
                }
            }
            SkeletonLayout.Checklist -> SkeletonCard {
                SkeletonLines(.40f, height = 14.dp)
                SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                repeat(3) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.size(19.dp))
                        SkeletonLines(.75f, height = 10.dp)
                    }
                }
            }
            SkeletonLayout.SharedPlans -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.52f, .76f)
                    SkeletonBlock(Modifier.width(100.dp).height(27.dp), CircleShape)
                }
            }
            SkeletonLayout.History -> repeat(rows) { index ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SkeletonBlock(Modifier.size(12.dp), CircleShape)
                        if (index < rows - 1) SkeletonBlock(Modifier.width(2.dp).height(37.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonLines(.29f, height = 9.dp)
                        SkeletonLines(.66f, .84f)
                    }
                }
            }
            SkeletonLayout.Accounts, SkeletonLayout.StaffWorkload -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.size(32.dp), CircleShape)
                        SkeletonLines(.55f, .73f, modifier = Modifier.weight(1f))
                        SkeletonBlock(Modifier.width(47.dp).height(19.dp), CircleShape)
                    }
                    if (layout == SkeletonLayout.StaffWorkload) MetricRow(2)
                }
            }
            SkeletonLayout.DashboardCounts -> {
                repeat(2) { MetricRow(2, compact = true) }
                MetricRow(2)
            }
            SkeletonLayout.EmergencyAlerts -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.52f, .45f)
                    SkeletonActions(2)
                    SkeletonBlock(Modifier.align(Alignment.End).width(88.dp).height(28.dp), CircleShape)
                }
            }
            SkeletonLayout.HotlineDirectory -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.66f, .94f)
                    SkeletonBlock(Modifier.width(92.dp).height(22.dp), CircleShape)
                    SkeletonActions(2)
                }
            }
            SkeletonLayout.SupportAssignment -> SkeletonCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.size(46.dp), CircleShape)
                    SkeletonLines(.75f, .53f, modifier = Modifier.weight(1f))
                }
            }
            SkeletonLayout.NearbySupport -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBlock(Modifier.size(14.dp), CircleShape)
                SkeletonBlock(Modifier.width(112.dp).height(24.dp), CircleShape)
            }
            SkeletonLayout.Directions -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val foreground = LocalContentColor.current.copy(alpha = .32f)
                SkeletonBlock(Modifier.size(28.dp), CircleShape, foreground)
                SkeletonBlock(Modifier.fillMaxWidth(.62f).height(14.dp), color = foreground)
            }
            SkeletonLayout.NewsFeed, SkeletonLayout.NewsArticle -> repeat(rows) {
                SkeletonCard {
                    val article = layout == SkeletonLayout.NewsArticle
                    if (!article) SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2.1f))
                    SkeletonLines(.24f, height = 9.dp)
                    SkeletonLines(.89f, .70f, height = 17.dp)
                    if (article) {
                        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2.1f))
                        SkeletonLines(.96f, .85f, .68f)
                    } else SkeletonLines(.92f, .65f)
                }
            }
            SkeletonLayout.FitnessSummary -> {
                SkeletonCard {
                    SkeletonLines(.42f, height = 14.dp)
                    MetricRow(3)
                }
                SkeletonCard {
                    SkeletonLines(.30f, height = 20.dp)
                    SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                    MetricRow(3)
                }
            }
            SkeletonLayout.FitnessActivity -> {
                MetricRow(2)
                FitnessChartPlaceholder()
            }
            SkeletonLayout.FitnessVitals -> {
                SkeletonCard {
                    SkeletonLines(.48f, height = 15.dp)
                    Box(Modifier.fillMaxWidth().height(94.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            repeat(4) { SkeletonBlock(Modifier.fillMaxWidth().height(1.dp)) }
                        }
                    }
                    SkeletonLines(.35f, height = 8.dp)
                }
                MetricRow(2)
            }
            SkeletonLayout.FitnessNutrition -> {
                SkeletonCard {
                    SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                    MetricRow(3)
                }
                SkeletonCard {
                    SkeletonLines(.26f, height = 14.dp)
                    FoodResultPlaceholder()
                }
            }
            SkeletonLayout.FoodResults -> repeat(rows) { SkeletonCard { FoodResultPlaceholder() } }
            SkeletonLayout.Form -> repeat(rows) {
                SkeletonLines(.28f, height = 10.dp)
                SkeletonBlock(Modifier.fillMaxWidth().height(44.dp))
            }
            SkeletonLayout.Action -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = OliveGreen,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = .22f),
            )
            SkeletonLayout.Image, SkeletonLayout.Video -> {
                val image = layout == SkeletonLayout.Image
                SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(if (image) 1.25f else 16f / 9f))
                if (!image) SkeletonBlock(Modifier.fillMaxWidth().height(6.dp), CircleShape)
            }
            SkeletonLayout.DocumentPage -> SkeletonCard(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 270.dp)
                    .heightIn(max = 360.dp).fillMaxWidth().aspectRatio(.72f),
            ) {
                SkeletonLines(.70f, height = 15.dp)
                Spacer(Modifier.height(8.dp))
                repeat(3) { SkeletonLines(.94f, .87f, .61f) }
            }
            SkeletonLayout.TextDocument -> SkeletonCard {
                repeat(rows) { SkeletonLines(.97f, .86f, .78f, .59f) }
            }
        }
    }
}

/**
 * Renders the skeleton card composable.
 *
 * @param modifier Compose modifier applied to the root layout.
 * @param content Composable content lambda rendered inside this container.
 */
@Composable
private fun SkeletonCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * Composes the skeleton lines UI element.
 *
 * @param widths Variable number of widths arguments.
 * @param modifier Compose modifier applied to the root layout.
 * @param height Target or source height in pixels or layout units.
 */
@Composable
private fun SkeletonLines(
    vararg widths: Float,
    modifier: Modifier = Modifier,
    height: Dp = 11.dp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        widths.forEach { width -> SkeletonBlock(Modifier.fillMaxWidth(width).height(height)) }
    }
}

/**
 * Renders the metric row composable.
 *
 * @param columns Columns supplied to [MetricRow].
 * @param compact Whether the layout should render in compact phone sizing.
 */
@Composable
private fun MetricRow(columns: Int, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(columns) {
            SkeletonCard(Modifier.weight(1f)) {
                if (compact) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(Modifier.size(width = 30.dp, height = 20.dp))
                    SkeletonLines(.85f, modifier = Modifier.weight(1f), height = 9.dp)
                } else {
                    SkeletonLines(.52f, height = 20.dp)
                    SkeletonLines(.87f, height = 9.dp)
                }
            }
        }
    }
}

/**
 * Composes the skeleton actions UI element.
 *
 * @param count Count supplied to [SkeletonActions].
 */
@Composable
private fun SkeletonActions(count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { SkeletonBlock(Modifier.weight(1f).height(32.dp), CircleShape) }
    }
}

/**
 * Composes the food result placeholder UI element.
 */
@Composable
private fun FoodResultPlaceholder() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLines(.72f, .46f, modifier = Modifier.weight(1f))
        SkeletonBlock(Modifier.width(37.dp).height(16.dp))
        SkeletonBlock(Modifier.size(27.dp), CircleShape)
    }
}

/**
 * Composes the fitness chart placeholder UI element.
 */
@Composable
private fun FitnessChartPlaceholder() {
    SkeletonCard {
        SkeletonLines(.40f, height = 15.dp)
        Row(
            Modifier.fillMaxWidth().height(88.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(7) { SkeletonBlock(Modifier.weight(1f).fillMaxHeight(.54f)) }
        }
    }
}

/**
 * Composes the skeleton block UI element.
 *
 * @param modifier Compose modifier applied to the root layout.
 * @param shape Shape supplied to [SkeletonBlock].
 * @param color Color supplied to [SkeletonBlock].
 */
@Composable
internal fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.outline.copy(alpha = .22f),
) {
    Spacer(modifier.background(color, shape))
}

/**
 * Uses the app's existing polite live-region pattern for an in-progress secure file open.
 *
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun SecureFileOpeningIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.documents_opening_securely), style = MaterialTheme.typography.labelLarge)
    }
}
