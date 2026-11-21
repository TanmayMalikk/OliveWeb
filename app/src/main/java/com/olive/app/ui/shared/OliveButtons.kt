package com.olive.app.ui.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

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
