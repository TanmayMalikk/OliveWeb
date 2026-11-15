package com.olive.app.ui.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object OlivePrivateLayout {
    val ScreenPadding = 20.dp
    val SectionSpacing = 24.dp
    val RowSpacing = 20.dp
    val GroupShape = RoundedCornerShape(28.dp)
    val RowMinHeight = 64.dp
    val HeaderActionSize = 50.dp
    val FloatingActionClearance = 88.dp
}

private val defaultTypography = OliveTypography
private val privateTypography = defaultTypography.copy(
    displaySmall = defaultTypography.displaySmall.copy(
        fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold
    ),
    headlineSmall = defaultTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = defaultTypography.titleLarge.copy(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold
    ),
    titleMedium = defaultTypography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
)

private val privateDarkColors = darkColorScheme(
    primary = SageGreen,
    onPrimary = Ink,
    primaryContainer = DarkOlive,
    onPrimaryContainer = PaleOlive,
    secondary = LightSage,
    onSecondary = Ink,
    secondaryContainer = DarkOlive,
    onSecondaryContainer = PaleOlive,
    tertiary = LightSage,
    onTertiary = Ink,
    tertiaryContainer = DarkOlive,
    onTertiaryContainer = PaleOlive,
    background = CallBackground,
    onBackground = Cream,
    surface = CallBackground,
    onSurface = Cream,
    surfaceVariant = CallControlBackground,
    onSurfaceVariant = PaleOlive,
    surfaceContainer = FitnessDarkCard,
    surfaceContainerHigh = CallControlBackground,
    surfaceContainerHighest = DarkOlive,
    outline = SageGreen,
    outlineVariant = DarkOlive,
)

// Opt in from private screens so covers and role portals retain their own appearance.
@Composable
internal fun OlivePrivateDesign(content: @Composable () -> Unit) {
    val inheritedColors = MaterialTheme.colorScheme
    val colors = if (isSystemInDarkTheme()) privateDarkColors else inheritedColors.copy(
        tertiary = inheritedColors.secondary,
        onTertiary = inheritedColors.onSecondary,
        tertiaryContainer = inheritedColors.secondaryContainer,
        onTertiaryContainer = inheritedColors.onSecondaryContainer,
        surfaceContainer = Paper,
        surfaceContainerHigh = CardTintEnabled,
        surfaceContainerHighest = PaleOlive,
        outlineVariant = SoftGray,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = privateTypography,
        shapes = MaterialTheme.shapes.copy(extraLarge = OlivePrivateLayout.GroupShape),
        content = content,
    )
}
