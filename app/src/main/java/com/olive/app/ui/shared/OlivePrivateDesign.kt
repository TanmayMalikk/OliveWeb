package com.olive.app.ui.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.olive.app.R

internal object OlivePrivateLayout {
    val ScreenPadding = 20.dp
    val SectionSpacing = 24.dp
    val GroupShape = RoundedCornerShape(28.dp)
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

/**
 * Opt in from private screens so covers and role portals retain their own appearance.
 *
 * @param content Composable content lambda rendered inside this container.
 */
@Composable
internal fun OlivePrivateDesign(content: @Composable () -> Unit) {
    val inheritedColors = MaterialTheme.colorScheme
    val colors = inheritedColors.copy(
        tertiary = inheritedColors.secondary,
        onTertiary = inheritedColors.onSecondary,
        tertiaryContainer = inheritedColors.secondaryContainer,
        onTertiaryContainer = inheritedColors.onSecondaryContainer,
        surfaceContainer = Paper,
        surfaceContainerHigh = CardTintEnabled,
        surfaceContainerHighest = PaleOlive,
        outlineVariant = OliveDivider,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = privateTypography,
        shapes = MaterialTheme.shapes.copy(extraLarge = OlivePrivateLayout.GroupShape),
        content = content,
    )
}

/**
 * Composes the floating quick exit UI element.
 *
 * @param onClick Callback invoked when the control is activated.
 * @param modifier Compose modifier applied to the root layout.
 * @param compact Whether the layout should render in compact phone sizing.
 */
@Composable
internal fun FloatingQuickExit(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = stringResource(R.string.a11y_quick_exit)
    val shape = MaterialTheme.shapes.large
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(1.dp, SageGreen, shape)
            .testTag("quick_exit").semantics { contentDescription = label },
        expanded = !compact,
        shape = shape,
        containerColor = OliveGreen,
        contentColor = Paper,
        icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
        text = { Text(stringResource(R.string.quick_exit), Modifier.clearAndSetSemantics {}) },
    )
}
