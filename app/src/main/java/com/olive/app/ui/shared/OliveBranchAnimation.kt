// Safe-app users: draws the PIN-to-safe-app olive animation in Compose.
package com.olive.app.ui.shared

import android.graphics.PathMeasure
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.sin
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.core.os.LocaleListCompat
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import com.olive.app.R
import java.util.Locale


/**
 * Keeps the next screen covered while the branch grows, then reveals it as the olive falls.
 * The drawing itself is still handled by the supplied stateless animation below.
 */
@Composable
internal fun PinSafeOliveTransition(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    durationMs: Int = 2600,
    onAnimationEnd: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }

    // Start the reveal animation again after a new successful private entry.
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
        onAnimationEnd()
    }

    Box(
        modifier.fillMaxSize().background(
            backgroundColor.copy(alpha = pinSafeBackgroundAlpha(progress.value))
        )
    ) {
        OliveBranchAnimationContent(
            progress = progress.value,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** The old screen stays covered until the olive starts falling, then clears by the landing point. */
internal fun pinSafeBackgroundAlpha(progress: Float): Float = when {
    progress <= 0.52f -> 1f
    progress >= 0.88f -> 0f
    else -> 1f - ((progress - 0.52f) / 0.36f)
}

/**
 * Pure Botanical Olive Branch & Falling Olive Animation.
 *
 * Self-contained animation overlay with NO screen or layout dependencies.
 *
 * Progression:
 * 1. Branch grows from top-left to top-right (0.00..0.38)
 * 2. Leaves unfurl along branch nodes (0.15..0.45)
 * 3. Olive swells at node with organic bounce (0.32..0.52)
 * 4. Olive detaches and falls with gravity (0.52..0.88)
 * 5. Branch dissolves smoothly (0.50..0.76)
 * 6. Falling olive dissolves smoothly (0.58..0.86)
 * 7. Optional subtle ground ripple settles at base (0.86..1.00)
 */
@Composable
internal fun OliveBranchAnimation(
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    durationMs: Int = 2600,
    onAnimationEnd: () -> Unit = {},
    onOliveDropPosition: ((x: Float, y: Float, progress: Float) -> Unit)? = null
) {
    val progressAnimatable = remember { Animatable(0f) }

    // Restart or clear the branch animation when its active state changes.
    LaunchedEffect(isAnimating) {
        // Run this part only when is animating.
        if (isAnimating) {
            progressAnimatable.snapTo(0f)
            progressAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = LinearEasing
                )
            )
            onAnimationEnd()
        } else {
            progressAnimatable.snapTo(0f)
        }
    }

    OliveBranchAnimationContent(
        progress = progressAnimatable.value,
        modifier = modifier,
        onOliveDropPosition = onOliveDropPosition
    )
}

/**
 * Stateless animation canvas driven by an external progress float (0f..1f).
 * Ideal for manual scrubbing, transition coordinators, or Gesture/Scroll drivers.
 */
@Composable
internal fun OliveBranchAnimationContent(
    progress: Float,
    modifier: Modifier = Modifier,
    onOliveDropPosition: ((x: Float, y: Float, progress: Float) -> Unit)? = null
) {
    if (progress <= 0f || progress >= 1f) return

    // 1. Branch Growth (0f..1f over first 38% of timeline)
    val branchGrowth = (progress / 0.38f).coerceIn(0f, 1f)

    // 2. Olive Bud Growth (0f..1f between 32% and 52%)
    val oliveGrowth = if (progress < 0.32f) 0f
    else ((progress - 0.32f) / 0.20f).coerceIn(0f, 1f)

    // 3. Olive Drop Acceleration under Gravity (0f..1f between 52% and 88%)
    val oliveDrop = if (progress < 0.52f) 0f
    else {
        val dropLinear = ((progress - 0.52f) / 0.36f).coerceIn(0f, 1f)
        Math.pow(dropLinear.toDouble(), 1.75).toFloat()
    }

    // 4. Branch Fade Away as new screen emerges (1f..0f between 50% and 76%)
    val branchAlpha = if (progress < 0.50f) 1f
    else (1f - (progress - 0.50f) / 0.26f).coerceIn(0f, 1f)

    // 5. Olive Fade Away as it descends (1f..0f between 58% and 86%)
    val oliveAlpha = if (progress < 0.58f) 1f
    else (1f - (progress - 0.58f) / 0.28f).coerceIn(0f, 1f)

    // 6. Base Ripple (0f..1f between 86% and 100%)
    val rippleProgress = if (progress < 0.86f) 0f
    else ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // --- A. Draw Branch & Leaves ---
        if (branchAlpha > 0.005f && branchGrowth > 0f) {
            val branchPath = Path().apply {
                moveTo(0f, h * 0.12f)
                cubicTo(
                    w * 0.28f, h * 0.08f,
                    w * 0.65f, h * 0.24f,
                    w * 1.05f, h * 0.18f
                )
            }

            val androidPath = branchPath.asAndroidPath()
            val pathMeasure = PathMeasure(androidPath, false)
            val totalLength = pathMeasure.length
            val currentLength = totalLength * FastOutSlowInEasing.transform(branchGrowth)

            val subAndroidPath = android.graphics.Path()
            pathMeasure.getSegment(0f, currentLength, subAndroidPath, true)

            // Draw branch stem
            drawPath(
                path = subAndroidPath.asComposePath(),
                brush = Brush.linearGradient(
                    colors = listOf(
                        BranchBarkDark.copy(alpha = branchAlpha),
                        BranchBarkMid.copy(alpha = branchAlpha),
                        BranchBarkLight.copy(alpha = branchAlpha)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h * 0.25f)
                ),
                style = Stroke(
                    width = 4.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw leaves along branch nodes
            val leafFractions = floatArrayOf(0.18f, 0.32f, 0.46f, 0.60f, 0.74f, 0.88f)
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            for ((i, frac) in leafFractions.withIndex()) {
                if (branchGrowth >= frac * 0.82f) {
                    val leafGrowth = ((branchGrowth - frac * 0.82f) / 0.22f).coerceIn(0f, 1f)
                    // Only continue when path measure get pos tan total length * frac, pos, tan.
                    if (pathMeasure.getPosTan(totalLength * frac, pos, tan)) {
                        val baseAngle = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
                        val side = if (i % 2 == 0) -1f else 1f
                        val leafAngle = baseAngle + (side * 42f)

                        drawOliveLeaf(
                            center = Offset(pos[0], pos[1]),
                            angle = leafAngle,
                            scale = leafGrowth,
                            length = 26.dp.toPx(),
                            width = 11.dp.toPx(),
                            alpha = branchAlpha
                        )
                    }
                }
            }
        }

        // --- B. Draw Olive (Attached or Falling) ---
        if (oliveGrowth > 0f && oliveAlpha > 0.005f) {
            val branchPath = Path().apply {
                moveTo(0f, h * 0.12f)
                cubicTo(
                    w * 0.28f, h * 0.08f,
                    w * 0.65f, h * 0.24f,
                    w * 1.05f, h * 0.18f
                )
            }
            val pathMeasure = PathMeasure(branchPath.asAndroidPath(), false)
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            if (pathMeasure.getPosTan(pathMeasure.length * 0.68f, pos, tan)) {
                val anchorX = pos[0]
                val anchorY = pos[1]
                val oliveRadius = 14.dp.toPx()

                // Bud swell with subtle elastic overshoot
                val budScale = if (oliveGrowth < 0.8f) {
                    oliveGrowth / 0.8f
                } else {
                    1f + sin((oliveGrowth - 0.8f) / 0.2f * Math.PI.toFloat()) * 0.08f
                }

                val currentOliveX: Float
                val currentOliveY: Float
                val oliveRotation: Float

                // Keep the olive attached to its stem until the falling stage begins.
                if (oliveDrop == 0f) {
                    currentOliveX = anchorX + 2.dp.toPx()
                    currentOliveY = anchorY + (16.dp.toPx() * budScale)
                    oliveRotation = 12f

                    // Thin connecting stem
                    if (branchAlpha > 0.01f) {
                        drawLine(
                            color = BranchBarkMid.copy(alpha = branchAlpha),
                            start = Offset(anchorX, anchorY),
                            end = Offset(currentOliveX, currentOliveY - (oliveRadius * 0.7f * budScale)),
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                } else {
                    val dropStartY = anchorY + 16.dp.toPx()
                    val floorY = h * 0.92f
                    val dropDistance = floorY - dropStartY

                    currentOliveX = anchorX + sin(oliveDrop * Math.PI.toFloat() * 1.5f) * 8.dp.toPx()
                    currentOliveY = dropStartY + (dropDistance * oliveDrop)
                    oliveRotation = 12f + (oliveDrop * 160f)
                }

                onOliveDropPosition?.invoke(currentOliveX, currentOliveY, progress)

                // Draw Olive Fruit (fades away as oliveAlpha decreases)
                drawOliveFruit(
                    center = Offset(currentOliveX, currentOliveY),
                    radius = oliveRadius * budScale,
                    rotationDegrees = oliveRotation,
                    alpha = oliveAlpha
                )

                // Draw Ground Ripple
                if (rippleProgress > 0f) {
                    val maxRadius = 45.dp.toPx()
                    val currentRadius = maxRadius * FastOutSlowInEasing.transform(rippleProgress)
                    val rAlpha = (1f - rippleProgress).coerceIn(0f, 1f) * oliveAlpha

                    // Skip the ripple once it is too transparent to see.
                    if (rAlpha > 0.01f) {
                        drawCircle(
                            color = LeafRippleOlive.copy(alpha = rAlpha * 0.45f),
                            radius = currentRadius,
                            center = Offset(currentOliveX, h * 0.92f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws an olive leaf with silvery-green gradient and central vein
 */
private fun DrawScope.drawOliveLeaf(
    center: Offset,
    angle: Float,
    scale: Float,
    length: Float,
    width: Float,
    alpha: Float
) {
    if (scale <= 0.01f || alpha <= 0.01f) return

    rotate(degrees = angle, pivot = center) {
        val sLength = length * scale
        val sWidth = width * scale

        val leafPath = Path().apply {
            moveTo(center.x, center.y)
            cubicTo(
                center.x + sLength * 0.4f, center.y - sWidth,
                center.x + sLength * 0.85f, center.y - sWidth * 0.6f,
                center.x + sLength, center.y
            )
            cubicTo(
                center.x + sLength * 0.85f, center.y + sWidth * 0.6f,
                center.x + sLength * 0.4f, center.y + sWidth,
                center.x, center.y
            )
            close()
        }

        drawPath(
            path = leafPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    LeafBodyDark.copy(alpha = alpha),
                    LeafBodyMid.copy(alpha = alpha),
                    LeafBodyLight.copy(alpha = alpha)
                ),
                start = center,
                end = Offset(center.x + sLength, center.y)
            )
        )

        drawLine(
            color = LeafShadowTint.copy(alpha = 0.4f * alpha),
            start = center,
            end = Offset(center.x + sLength * 0.82f, center.y),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Draws a plump botanical olive with 3D radial shading and specular gloss
 */
private fun DrawScope.drawOliveFruit(
    center: Offset,
    radius: Float,
    rotationDegrees: Float,
    alpha: Float
) {
    if (alpha <= 0.01f) return

    rotate(degrees = rotationDegrees, pivot = center) {
        val rx = radius * 0.82f
        val ry = radius * 1.14f

        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    FruitHighlight.copy(alpha = alpha),
                    FruitMid.copy(alpha = alpha),
                    FruitShadow.copy(alpha = alpha)
                ),
                center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
                radius = radius * 1.4f
            ),
            topLeft = Offset(center.x - rx, center.y - ry),
            size = Size(rx * 2f, ry * 2f)
        )

        // Specular highlight gloss
        drawOval(
            color = Color.White.copy(alpha = 0.55f * alpha),
            topLeft = Offset(center.x - rx * 0.45f, center.y - ry * 0.55f),
            size = Size(rx * 0.45f, ry * 0.65f)
        )
    }
}

// All app roles: chooses translated text for Android screens.

// Capture native resources during composition so event callbacks can format translated messages too.
internal class ResourceText(private val resolve: (Int, Array<out Any?>) -> String) {
    constructor(resources: Resources) : this({ id, arguments ->
        if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
    })

    // Lets screens request translated text by calling this object like a function.
    operator fun invoke(@StringRes id: Int, vararg arguments: Any?): String =
        resolve(id, arguments)
}

// Returns a helper that reads translated Android text inside Compose.
@Composable
internal fun rememberResourceText(): ResourceText {
    val resources = LocalResources.current
    return remember(resources) { ResourceText(resources) }
}

// Pure text helpers carry a message ID; the UI resolves it using Android's current locale.
internal class ResourceFailure(@StringRes val textId: Int, vararg val arguments: Any?) :
    IllegalArgumentException()

// Reads translated Android text outside a composable function.
internal inline fun requireText(condition: Boolean, message: () -> ResourceFailure) {
    if (!condition) throw message()
}

// Returns translated fallback text when an operation fails.
internal fun ResourceText.failure(error: Throwable, @StringRes fallback: Int): String =
    if (error is ResourceFailure) this(error.textId, *error.arguments) else this(fallback)

// Returns the translated title for an account role.
internal fun AccountRole.localizedTitle(text: ResourceText) = text(
    // Chooses the translated title for the active screen or cover.
    when (this) {
        AccountRole.USER -> R.string.user
        AccountRole.SAFE_CONTACT -> R.string.safe_contact
        AccountRole.STAFF -> R.string.staff
        AccountRole.ADMIN -> R.string.admin
    }
)

// Returns the translated description for an account role.
internal fun AccountRole.localizedDescription(text: ResourceText) = text(
    // Chooses the translated description for the active cover.
    when (this) {
        AccountRole.USER -> R.string.the_person_using_the_private_cover_and_safe_app
        AccountRole.SAFE_CONTACT -> R.string.a_trusted_person_helping_someone
        AccountRole.STAFF -> R.string.an_organization_staff_member
        AccountRole.ADMIN -> R.string.an_organization_leader_who_oversees_staff
    }
)

// Returns the translated title for a cover app.
internal fun CoverType.localizedTitle(text: ResourceText) = text(
    // Chooses the translated title for the active screen or cover.
    when (this) {
        CoverType.NEWS -> R.string.ui_daily_digest
        CoverType.FITNESS -> R.string.fitness_tracker
        CoverType.GAME -> R.string.ui_game
    }
)

internal object AppLocaleManager {

    val supportedLanguages = listOf("English", "French", "Spanish", "Arabic", "Chinese")

    // Reads Android's current language tag for app formatting.
    fun getLanguageTag(language: String): String {
        return when (language.trim().lowercase()) {
            "french", "fr", "français" -> "fr"
            "spanish", "es", "español" -> "es"
            "arabic", "ar", "العربية" -> "ar"
            "chinese", "zh", "zh-cn", "zh-rcn", "中文" -> "zh-rCN"
            else -> "en"
        }
    }

    // Returns the readable name of Android's current language.
    fun getDisplayLanguage(language: String): String {
        return when (getLanguageTag(language)) {
            "fr" -> "Français"
            "es" -> "Español"
            "ar" -> "العربية"
            "zh-rCN" -> "中文"
            else -> "English"
        }
    }

    // Reads the phone's language tag, such as en-CA or fr-CA.
    fun resolveSystemDefaultLanguageTag(): String {
        val systemLocale = Locale.getDefault()
        val lang = systemLocale.language.lowercase()
        return when (lang) {
            "fr" -> "fr"
            "es" -> "es"
            "ar" -> "ar"
            "zh" -> "zh-rCN"
            "en" -> "en"
            else -> "en"
        }
    }

    // Reads the phone's language name for the language picker.
    fun resolveSystemDefaultLanguageName(): String {
        return when (resolveSystemDefaultLanguageTag()) {
            "fr" -> "French"
            "es" -> "Spanish"
            "ar" -> "Arabic"
            "zh-rCN" -> "Chinese"
            else -> "English"
        }
    }

    // Saves the selected language and asks Android to recreate UI resources in that locale.
    fun applyLocale(language: String) {
        val tag = getLanguageTag(language)
        val locales = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // Uses Android's current language when the app starts.
    fun initSystemDefaultLocale() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            val defaultTag = resolveSystemDefaultLanguageTag()
            applyLocale(defaultTag)
        }
    }
}
