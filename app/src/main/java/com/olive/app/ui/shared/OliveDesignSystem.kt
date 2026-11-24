package com.olive.app.ui.shared

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.BuildConfig
import com.olive.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/** Rounds card corners with a smooth join between the curve and each straight edge. */
internal fun oliveCorners(radius: Dp): CornerBasedShape {
    val corner = CornerSize(radius)
    return OliveCorners(corner, corner, corner, corner)
}

internal val OlivePill: CornerBasedShape = CornerSize(50).let { OliveCorners(it, it, it, it) }

private class OliveCorners(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    /**
     * Creates a copy of this shape with the specified corner sizes.
     */
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = OliveCorners(topStart, topEnd, bottomEnd, bottomStart)

    /**
     * Creates an [Outline] of this shape for the given size and corner radii.
     */
    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
        val limit = minOf(size.width, size.height) / 2f
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = (if (ltr) topStart else topEnd).coerceAtMost(limit)
        val tr = (if (ltr) topEnd else topStart).coerceAtMost(limit)
        val br = (if (ltr) bottomEnd else bottomStart).coerceAtMost(limit)
        val bl = (if (ltr) bottomStart else bottomEnd).coerceAtMost(limit)
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(tl, 0f)
            lineTo(w - tr, 0f)
            cubicTo(w - .64f * tr, 0f, w - .32f * tr, 0f, w - .16f * tr, .16f * tr)
            cubicTo(w, .32f * tr, w, .64f * tr, w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - .64f * br, w, h - .32f * br, w - .16f * br, h - .16f * br)
            cubicTo(w - .32f * br, h, w - .64f * br, h, w - br, h)
            lineTo(bl, h)
            cubicTo(.64f * bl, h, .32f * bl, h, .16f * bl, h - .16f * bl)
            cubicTo(0f, h - .32f * bl, 0f, h - .64f * bl, 0f, h - bl)
            lineTo(0f, tl)
            cubicTo(0f, .64f * tl, 0f, .32f * tl, .16f * tl, .16f * tl)
            cubicTo(.32f * tl, 0f, .64f * tl, 0f, tl, 0f)
            close()
        })
    }
}

// Core Olive brand and surface palette used across covers and the private Safe App.
internal val OliveGreen = Color(0xFF354E2C)
internal val Ink = Color(0xFF253322)
internal val Cream = Color(0xFFFAF9F5)
internal val Muted = Color(0xFF39492D)
internal val Paper = Color.White
internal val PaleOlive = Color(0xFFE3EEDB)
internal val WarmWhite = Color(0xFFEEF2E8)
internal val LightSage = Color(0xFFDBE8D4)
internal val SageGreen = Color(0xFFCDE0C3)
internal val SoftGray = Color(0xFFD6D9CD)
internal val OliveOutline = Color(0xFF5D6952)
internal val OliveDivider = Color(0xFF75816A)
internal val OliveDarkDivider = Color(0xFF869A7D)
internal val OliveError = Color(0xFF7A221E)
internal val OliveErrorContainer = Color(0xFFFFE8E5)
internal val DarkOlive = Color(0xFF314B28)
internal val DarkSageSurface = Color(0xFF40563A)
internal val MossGreen = Color(0xFF6C7A3D)
internal val OliveBrown = Color(0xFF7A5326)

// Scrollbar track and thumb colors for scrollable lists and grids.
internal val ScrollTrack = Color(0x24354E2C)
internal val ScrollThumb = Color(0xB3354E2C)

// Fitness Tracker cover and nutrition/activity chart palette.
internal val FitnessBackground = Cream
internal val FitnessSurface = Paper
internal val FitnessAccent = OliveGreen
internal val FitnessText = Ink
internal val FitnessSubtle = Muted
internal val FitnessHeroEnd = Color(0xFF425A36)
internal val FitnessWarmCard = Color(0xFFF1F3E9)
internal val FitnessDarkCard = Color(0xFF263722)
internal val FitnessStopRed = Color(0xFF8A453E)
internal val DistanceGreen = Color(0xFF4E7039)
internal val BurnedOrange = Color(0xFF9A4D23)
internal val EatenBrown = Color(0xFF765B3D)
internal val ProteinGreen = Color(0xFF45613C)
internal val ProteinLime = Color(0xFFA7CA65)
internal val CarbohydrateGold = Color(0xFF836217)
internal val CarbohydrateBlue = Color(0xFF53BBD8)
internal val FatPlum = Color(0xFF875E78)
internal val FatPlumLight = Color(0xFFDDA8C9)

// Messaging, friend list, and voice call screen palette.
internal val ChatPageBackground = Color(0xFFF8F7F1)
internal val ChatIncomingBubble = Color(0xFFF0F2E8)
internal val ChatSentBubble = Color(0xFF354E2C)
internal val ChatBorder = OliveDivider
internal val ChatDangerRed = Color(0xFF9D3E37)
internal val OnlineIndicatorGreen = Color(0xFF38B56C)
internal val SoftCardSurface = Color(0xFFF3F6F0)
internal val WarmBannerYellow = Color(0xFFFFF7E1)
internal val CallBackground = Color(0xFF172219)
internal val CallControlBackground = Color(0xFF2B382D)
internal val CallGreen = Color(0xFF5C8A4E)
internal val CallRed = Color(0xFFA6322E)
internal val CameraControlScrim = CallBackground.copy(alpha = .88f)

// Dashboard cards, badges, and private note surfaces.
internal val CardTintEnabled = Color(0xFFE7EEE0)
internal val CardSoftMint = Color(0xFFF0F4EC)
internal val IconBadgeEnabled = Color(0xFFDDE7D6)
internal val NotePaperCream = Color(0xFFFFFDF4)
internal val IncidentPaper = Color(0xFFF8E7CE)
internal val IncidentAccent = Color(0xFF68451F)
internal val NoteTagMint = Color(0xFFE7EDE2)
internal val NoteMutedSurface = Color(0xFFF5F3EC)

// Olive branch splash and unlock canvas illustration palette.
internal val BranchBarkDark = Color(0xFF5D4037)
internal val BranchBarkMid = Color(0xFF6B583E)
internal val BranchBarkLight = Color(0xFF7A6B48)
internal val LeafRippleOlive = Color(0xFF6B8E23)
internal val LeafBodyDark = Color(0xFF556B2F)
internal val LeafBodyMid = Color(0xFF6E8B3D)
internal val LeafBodyLight = Color(0xFF8F9E6D)
internal val LeafShadowTint = Color(0x66334411)
internal val FruitHighlight = Color(0xFF7A933B)
internal val FruitMid = Color(0xFF4A6223)
internal val FruitShadow = Color(0xFF2C3E14)

private val materialType = Typography()

// Keep metadata legible and allow the system font scale to apply to every style.
internal val OliveTypography = materialType.copy(
    displayLarge = materialType.displayLarge.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    displayMedium = materialType.displayMedium.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    displaySmall = materialType.displaySmall.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineLarge = materialType.headlineLarge.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineMedium = materialType.headlineMedium.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    headlineSmall = materialType.headlineSmall.copy(lineBreak = LineBreak.Heading, fontFeatureSettings = "tnum"),
    titleLarge = materialType.titleLarge.copy(lineBreak = LineBreak.Heading),
    labelLarge = materialType.labelLarge.copy(fontFeatureSettings = "tnum"),
    bodySmall = materialType.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = materialType.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    labelSmall = materialType.labelSmall.copy(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
    ),
)

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

/** Moves the artwork on press while leaving the layout and touch bounds in place. */
@Composable
internal fun Modifier.olivePress(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    depth: Float = .035f,
): Modifier {
    if (!enabled) return this

    val touchSource = remember { MutableInteractionSource() }
    val touchPressed = rememberOlivePressFeedback(touchSource)
    val controlPressed = rememberOlivePressFeedback(source)
    val pressed = touchPressed.value || controlPressed.value
    val movement = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) snap() else tween(120),
        label = "Olive press",
    )

    // Listen on the initial pass so scrolling containers can still delay or cancel the actual click.
    return pointerInput(touchSource) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            touchSource.tryEmit(press)
            var released = false
            try {
                released = waitForUpOrCancellation(PointerEventPass.Initial) != null
            } finally {
                touchSource.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }.drawWithContent {
        val amount = if (touchPressed.value || controlPressed.value) 1f else movement.value
        if (amount == 0f) {
            drawContent()
        } else {
            translate(top = 1.dp.toPx() * amount) {
                val factor = 1f - depth * amount
                scale(factor, factor) { this@drawWithContent.drawContent() }
            }
        }
    }
}

/** Keeps a quick tap visible for a few frames, even when down and up arrive together. */
@Composable
private fun rememberOlivePressFeedback(source: MutableInteractionSource): State<Boolean> {
    val pressed = remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.interactions.filterIsInstance<PressInteraction>().collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> pressed.value = true
                is PressInteraction.Release -> {
                    delay(55)
                    pressed.value = false
                }
                is PressInteraction.Cancel -> pressed.value = false
            }
        }
    }
    return pressed
}

/**
 * Remembers a mutable interaction source or falls back to an existing instance.
 *
 * @param source Optional supplied interaction source.
 * @return The resolved mutable interaction source.
 */
@Composable
internal fun rememberOliveInteraction(source: MutableInteractionSource? = null): MutableInteractionSource =
    source ?: remember { MutableInteractionSource() }

/** Tracks the current destination only. Canceling the gesture leaves its navigation state intact. */
@Composable
internal fun rememberOliveBack(
    enabled: Boolean = true,
    preview: Boolean = true,
    onBack: () -> Unit,
): State<Float> {
    val progress = remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { if (preview) progress.floatValue = it.progress }
            onBack()
        } finally {
            progress.floatValue = 0f
        }
    }
    return progress
}

/** Provides movement without exposing the content behind this destination. */
internal fun Modifier.oliveBackPreview(progress: State<Float>): Modifier = graphicsLayer {
    val scale = 1f - progress.value * 0.045f
    scaleX = scale
    scaleY = scale
}

/** Animates the arriving destination without retaining a second copy of outgoing content. */
@Composable
internal fun OliveRouteEntrance(route: Any, back: State<Float>, content: @Composable () -> Unit) {
    val opacity = remember(route) { Animatable(0f) }
    LaunchedEffect(opacity) { opacity.animateTo(1f, tween(160)) }
    Box(Modifier.fillMaxSize().oliveBackPreview(back).graphicsLayer { alpha = opacity.value }) {
        content()
    }
}

internal val LocalOliveUndo = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, _ -> } }

/** Offers one undo action for the current account and destination. */
@Composable
internal fun OliveUndoOverlay(
    scopeKey: Any?,
    enabled: Boolean,
    bottomClearance: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val host = remember(scopeKey) { SnackbarHostState() }
    val actionLabel = stringResource(R.string.undo_action)
    var pending by remember(scopeKey) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(scopeKey, pending, enabled) {
        val action = pending ?: return@LaunchedEffect
        if (enabled && host.showSnackbar(
                action.first, actionLabel, withDismissAction = true, duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        ) action.second()
        pending = null
    }
    CompositionLocalProvider(LocalOliveUndo provides { message, action ->
        if (enabled) pending = message to action
    }) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (enabled) SnackbarHost(
                host,
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomClearance),
            )
        }
    }
}

internal object OlivePrivateLayout {
    val ScreenPadding = 20.dp
    val SectionSpacing = 24.dp
    val GroupShape = oliveCorners(32.dp)
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
    val shape = OlivePill
    val source = rememberOliveInteraction()
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.olivePress(source).border(1.dp, SageGreen, shape)
            .walkthroughTarget("quick_exit")
            .testTag("quick_exit").semantics { contentDescription = label },
        expanded = !compact,
        shape = shape,
        interactionSource = source,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 1.dp),
        containerColor = OliveGreen,
        contentColor = Paper,
        icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
        text = { Text(stringResource(R.string.quick_exit), Modifier.clearAndSetSemantics {}) },
    )
}

/**
 * Renders the app version footer composable.
 */
@Composable
internal fun AppVersionFooter() {
    Text(
        stringResource(R.string.app_update_version, BuildConfig.VERSION_NAME),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
