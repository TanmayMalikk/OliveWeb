package com.olive.app.ui.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

@Composable
internal fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = oliveLightColors, typography = OliveTypography, content = content)
}
