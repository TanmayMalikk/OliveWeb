package com.olive.app.ui.shared

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val OliveCardElevation: CardElevation
    @Composable get() = CardDefaults.cardElevation(
        defaultElevation = 2.dp, pressedElevation = 0.dp, focusedElevation = 2.dp, hoveredElevation = 3.dp,
    )

private val OliveOutlinedElevation: CardElevation
    @Composable get() = CardDefaults.outlinedCardElevation(
        defaultElevation = 1.dp, pressedElevation = 0.dp, focusedElevation = 1.dp, hoveredElevation = 2.dp,
    )

/**
 * Renders a static elevated card.
 */
@Composable
internal fun OliveCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = OliveCardElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(modifier, shape, colors, elevation, border, content)
}

/**
 * Renders a clickable elevated card with themed press animations.
 */
@Composable
internal fun OliveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = OliveCardElevation,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.Card(
        onClick, modifier.olivePress(source, enabled, depth = .018f), enabled, shape,
        colors, elevation, border, source, content,
    )
}

/**
 * Renders a static outlined card.
 */
@Composable
internal fun OliveOutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = OliveOutlinedElevation,
    border: BorderStroke = CardDefaults.outlinedCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedCard(modifier, shape, colors, elevation, border, content)
}

/**
 * Renders a clickable outlined card with themed press animations.
 */
@Composable
internal fun OliveOutlinedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = OliveOutlinedElevation,
    border: BorderStroke = CardDefaults.outlinedCardBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = rememberOliveInteraction(interactionSource)
    androidx.compose.material3.OutlinedCard(
        onClick, modifier.olivePress(source, enabled, depth = .018f), enabled, shape,
        colors, elevation, border, source, content,
    )
}

/** Adds the same press feedback to custom dashboard tiles. */
@Composable
internal fun OliveActionSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    border: BorderStroke? = null,
    tonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction()
    val pressed by source.collectIsPressedAsState()
    val shadow by animateDpAsState(
        if (enabled && !pressed) 3.dp else 0.dp,
        label = "Olive tile elevation",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.olivePress(source, enabled, depth = .018f),
        enabled = enabled,
        shape = shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadow,
        border = border,
        interactionSource = source,
        content = content,
    )
}
