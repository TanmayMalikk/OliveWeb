package com.olive.app.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val oliveShapes = Shapes(
    small = oliveCorners(12.dp),
    medium = oliveCorners(16.dp),
    large = oliveCorners(22.dp),
    extraLarge = oliveCorners(32.dp),
)

private val oliveLightColors = lightColorScheme(
    primary = OliveGreen, onPrimary = Paper,
    primaryContainer = PaleOlive, onPrimaryContainer = Ink,
    secondary = OliveGreen, onSecondary = Paper,
    secondaryContainer = PaleOlive, onSecondaryContainer = Ink,
    tertiary = DarkOlive, onTertiary = Paper,
    tertiaryContainer = LightSage, onTertiaryContainer = Ink,
    background = Cream, onBackground = Ink,
    surface = Cream, onSurface = Ink,
    surfaceVariant = WarmWhite, onSurfaceVariant = Muted,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = CardTintEnabled,
    surfaceContainer = WarmWhite,
    surfaceContainerHigh = LightSage,
    surfaceContainerHighest = PaleOlive,
    surfaceTint = OliveGreen,
    outline = OliveOutline, outlineVariant = OliveDivider,
    inverseSurface = Ink, inverseOnSurface = Cream, inversePrimary = SageGreen,
    error = OliveError, onError = Paper,
    errorContainer = OliveErrorContainer, onErrorContainer = OliveError,
)

/**
 * Composes the olive theme UI element.
 *
 * @param content Composable content lambda rendered inside this container.
 */
@Composable
internal fun OliveTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
        MaterialTheme(colorScheme = oliveLightColors, typography = OliveTypography, shapes = oliveShapes, content = content)
    }
}
