// Contains the onboarding code for this part of Olive.
package com.olive.app.ui.users.shared.onboarding

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*


import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.time.LocalDate
import java.time.Month
import java.util.Locale
import kotlin.random.Random

// Requirement bullets begin neutral, then turn green or red as the user types.
@Composable
internal fun RequirementBullet(text: String, met: Boolean, active: Boolean) {
    val uiText = rememberResourceText()
    val color = when {
        !active -> Color.Black
        met -> OliveGreen
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("•", color = color, fontSize = 14.sp, lineHeight = 14.sp)
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, lineHeight = 14.sp)
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = active && met,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100))
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = uiText(R.string.ui_requirement_met),
                tint = OliveGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// Every onboarding page ends with the same Back and Next arrow controls.
@Composable
internal fun OnboardingNavigation(
    back: () -> Unit, next: () -> Unit, last: Boolean = false, nextTag: String = "onboarding_next",
    enabled: Boolean = true, nextLabel: String? = null
) {
    val uiText = rememberResourceText()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = back,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag("onboarding_back")
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.back)
            )
        }
        Button(onClick = next, enabled = enabled, modifier = Modifier.weight(1f).testTag(nextTag)) {
            Text(
                nextLabel ?: if (last) uiText(R.string.ui_finish) else uiText(R.string.next)
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
    }
}

// USER and SAFE CONTACT: birthday is required and saved as YYYY-MM-DD.
// UI sizing: the three birthday buttons share one row equally with 8dp gaps and 8dp horizontal button padding.
@Composable
internal fun BirthdayPicker(
    value: String,
    tag: String,
    enabled: Boolean,
    error: String? = null,
    onChange: (String) -> Unit
) {
    val uiText = rememberResourceText()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val initial = remember { runCatching { LocalDate.parse(value) }.getOrNull() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    var day by remember { mutableStateOf(initial?.dayOfMonth) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // Sets date.
    fun setDate(y: Int?, m: Int?, d: Int?) {
        if (y != null && m != null && d != null) onChange(
            "%04d-%02d-%02d".format(
                Locale.ROOT,
                y,
                m,
                d
            )
        )
    }
    Text(
        stringResource(R.string.date_of_birth_required),
        style = MaterialTheme.typography.labelLarge
    )
    Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Choice(
            label: String,
            selected: String,
            options: List<Pair<String, String>>,
            choiceTag: String,
            choose: (String) -> Unit
        ) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = {
                        keyboard?.hide()
                        focus.clearFocus(force = true)
                        open = true
                    },
                    enabled = enabled,
                    border = if (error != null) BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error
                    ) else ButtonDefaults.outlinedButtonBorder(enabled),
                    modifier = Modifier.fillMaxWidth().testTag(choiceTag),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(selected.ifBlank { label }, maxLines = 1)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { (id, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                choose(id)
                                open = false
                            },
                            modifier = Modifier.testTag("${choiceTag}_$id")
                        )
                    }
                }
            }
        }
        Choice(
            uiText(R.string.year),
            year?.toString().orEmpty(),
            (1950..LocalDate.now().year).reversed().map { it.toString() to it.toString() },
            "${tag}_year"
        ) {
            year = it.toInt()
            setDate(year, month, day)
        }
        Choice(
            uiText(R.string.month),
            month?.let { Month.of(it).getDisplayName(java.time.format.TextStyle.FULL, locale) }
                .orEmpty(),
            (1..12).map {
                it.toString() to Month.of(it)
                    .getDisplayName(java.time.format.TextStyle.FULL, locale)
            },
            "${tag}_month"
        ) {
            month = it.toInt()
            setDate(year, month, day)
        }
        Choice(
            uiText(R.string.day),
            day?.toString().orEmpty(),
            (1..31).map { it.toString() to it.toString() },
            "${tag}_day"
        ) {
            day = it.toInt()
            setDate(year, month, day)
        }
    }
    error?.let {
        AccessibleMessage(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Multi-page onboarding gets a width-filling branch with page-specific leaf layouts and fixed-size olives/leaves.
// TalkBack receives one progress summary instead of separate decorative drawing nodes.
@Composable
internal fun OnboardingProgress(
    completed: Int,
    total: Int,
    page: Int,
    pages: Int,
    pageComplete: List<Boolean>
) {
    val uiText = rememberResourceText()
    if (pages <= 1) return
    val layout = remember(pages) { OnboardingBranchLayout(pages) }
    val naturalStepOffsets = layout.naturalStepOffsets
    val sprigs = layout.sprigs
    val stepLabel = uiText(R.string.a11y_step)
    val completedLabel = uiText(R.string.a11y_completed_requirements)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().testTag("onboarding_progress").clearAndSetSemantics {
            contentDescription = "$stepLabel ${page + 1} / $pages"
            stateDescription = "$completedLabel: $completed / $total"
            progressBarRangeInfo =
                ProgressBarRangeInfo(completed.toFloat(), 0f..total.coerceAtLeast(1).toFloat())
        }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Stretch only the stem and distribute fruit along it; artwork stays at its original size.
            val availableSpan = (maxWidth.value - 72f).coerceAtLeast(0f)
            val stepOffsets =
                naturalStepOffsets.map { it / naturalStepOffsets.last() * availableSpan }
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset = 24.dp.toPx()
                    val middle = size.height / 2
                    drawLine(
                        OliveBrown.copy(alpha = .7f), Offset(5.dp.toPx(), middle),
                        Offset(inset, middle), 2.dp.toPx(), StrokeCap.Round
                    )
                    repeat(pages - 1) { index ->
                        val start = inset + stepOffsets[index].dp.toPx()
                        val spacing = (stepOffsets[index + 1] - stepOffsets[index]).dp.toPx()
                        val end = start + spacing
                        val grown = index < page
                        val stemColor = if (grown) OliveBrown else OliveBrown.copy(alpha = .7f)
                        val leafColor = if (grown) MossGreen else SageGreen
                        val stem = Path().apply {
                            moveTo(start, middle)
                            cubicTo(
                                start + spacing * .3f, middle - 7.dp.toPx(),
                                start + spacing * .7f, middle + 7.dp.toPx(), end, middle
                            )
                        }
                        drawPath(
                            stem,
                            stemColor,
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        sprigs[index].forEach { sprig ->
                            val fraction = sprig.start
                            val bend =
                                3 * fraction * (1 - fraction) * (1 - 2 * fraction) * (-7.dp.toPx())
                            drawOnboardingSprig(
                                Offset(start + spacing * fraction, middle + bend),
                                (naturalStepOffsets[index + 1] - naturalStepOffsets[index]).dp.toPx(),
                                sprig,
                                stemColor,
                                leafColor
                            )
                        }
                    }
                    val tipStart = inset + stepOffsets.last().dp.toPx()
                    val tip = Path().apply {
                        moveTo(tipStart, middle)
                        cubicTo(
                            tipStart + 12.dp.toPx(),
                            middle + 2.dp.toPx(),
                            size.width - 12.dp.toPx(),
                            middle - 6.dp.toPx(),
                            size.width - 3.dp.toPx(),
                            middle - 12.dp.toPx()
                        )
                    }
                    drawPath(
                        tip,
                        OliveBrown.copy(alpha = .7f),
                        style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawOnboardingLeaf(
                        Offset(size.width - 20.dp.toPx(), middle - 4.dp.toPx()),
                        Offset(size.width - 5.dp.toPx(), middle - 26.dp.toPx()), SageGreen
                    )
                    // Short curved stalks attach each numbered olive to the main branch.
                    repeat(pages) { index ->
                        val center = inset + stepOffsets[index].dp.toPx()
                        val side = if (index % 2 == 0) 1f else -1f
                        val stalk = Path().apply {
                            moveTo(center + 10.dp.toPx(), middle)
                            cubicTo(
                                center + 4.dp.toPx(), middle + side * 5.dp.toPx(),
                                center - 6.dp.toPx(), middle + side * 10.dp.toPx(),
                                center - 6.dp.toPx(), middle + side * 16.dp.toPx()
                            )
                        }
                        drawPath(
                            stalk,
                            OliveBrown,
                            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                repeat(pages) { index ->
                    val done =
                        index < page || (index == page && pageComplete.getOrElse(index) { false })
                    val active = index == page
                    OnboardingOlive(index, done, active, stepOffsets[index])
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                uiText(R.string.ui_step_value_of_value, page + 1, pages),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                uiText(R.string.ui_value_of_value_required, completed, total),
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
        }
    }
}

// Offshoots and leaf tips lean right, giving the branch a clear direction of growth.
private fun DrawScope.drawOnboardingSprig(
    base: Offset,
    spacing: Float,
    sprig: OnboardingSprig,
    stemColor: Color,
    leafColor: Color
) {
    val reach = spacing * sprig.reach
    val rise = sprig.rise.dp.toPx()
    fun point(x: Float, y: Float) = base + Offset(reach * x, rise * y)
    val tip = point(1f, 1f)
    val sprigPath = Path().apply {
        moveTo(base.x, base.y)
        val first = point(.38f, .08f)
        val second = point(.55f, 1.05f)
        cubicTo(first.x, first.y, second.x, second.y, tip.x, tip.y)
    }
    drawPath(sprigPath, stemColor, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    drawOnboardingLeaf(point(.3f, .42f), point(.55f + sprig.lean, 1.3f), leafColor)
    drawOnboardingLeaf(point(.58f, .83f), point(1.03f, .96f + sprig.lean), leafColor)
    if (sprig.leafCount == 3) drawOnboardingLeaf(
        point(.74f, .95f),
        point(.94f + sprig.lean, .35f),
        leafColor
    )
}

// Two curved edges form a narrow olive leaf; its vein points back to the stem.
private fun DrawScope.drawOnboardingLeaf(base: Offset, tip: Offset, color: Color) {
    val direction = tip - base
    val side = Offset(-direction.y, direction.x) / direction.getDistance() * 4.dp.toPx()
    val leaf = Path().apply {
        moveTo(base.x, base.y)
        val first = base + direction * .35f + side
        val second = base + direction * .75f + side
        cubicTo(first.x, first.y, second.x, second.y, tip.x, tip.y)
        val third = base + direction * .75f - side
        val fourth = base + direction * .35f - side
        cubicTo(third.x, third.y, fourth.x, fourth.y, base.x, base.y)
        close()
    }
    drawPath(leaf, color)
    drawLine(
        OliveGreen.copy(alpha = .35f),
        base,
        base + direction * .8f,
        .6.dp.toPx(),
        StrokeCap.Round
    )
}

// Groups the data and actions for onboarding sprig.
internal data class OnboardingSprig(
    val start: Float,
    val reach: Float,
    val rise: Float,
    val leafCount: Int,
    val lean: Float
)

// Seeded geometry stays stable while text fields and progress state recompose.
internal class OnboardingBranchLayout(pages: Int) {
    val naturalStepOffsets =
        when (pages) {
            2 -> listOf(0f, 90f)
            3 -> listOf(0f, 92f, 178f)
            4 -> listOf(0f, 78f, 154f, 232f)
            else -> List(pages) { it * 78f }
        }
    val sprigs =
        List(pages - 1) { gap ->
            val random = Random(pages * 104729 + gap * 7919)
            val firstSide = if (random.nextBoolean()) -1f else 1f
            List(2) { index ->
                OnboardingSprig(
                    start = (if (index == 0) .18f else .48f) + random.nextFloat() * .08f,
                    reach = (if (index == 0) .42f else .3f) + random.nextFloat() * .1f,
                    rise = (18f + random.nextFloat() * 6f) * firstSide * (if (index == 0) 1f else -1f),
                    leafCount = random.nextInt(2, 4),
                    lean = random.nextFloat() * .2f - .1f,
                )
            }
        }
}

// One numbered olive owns its animation, orientation and test identity.
@Composable
private fun OnboardingOlive(index: Int, done: Boolean, active: Boolean, stepOffset: Float) {
    val oliveColor by animateColorAsState(
        if (done) OliveGreen else PaleOlive,
        tween(300),
        label = "step_fill_$index"
    )
    val borderColor by animateColorAsState(
        if (done || active) OliveGreen else SoftGray,
        tween(300),
        label = "step_border_$index"
    )
    val numberColor by animateColorAsState(
        if (done) Paper else if (active) OliveGreen else Muted,
        tween(300),
        label = "step_number_$index"
    )
    val oliveWidth by animateDpAsState(
        if (active) 34.dp else 30.dp,
        tween(250),
        label = "step_width_$index"
    )
    val oliveHeight by animateDpAsState(
        if (active) 44.dp else 40.dp,
        tween(250),
        label = "step_height_$index"
    )
    val borderWidth by animateDpAsState(
        if (active) 2.dp else 1.dp,
        tween(250),
        label = "step_border_width_$index"
    )
    val oliveOffset = if (index % 2 == 0) 24.dp else (-24).dp
    val oliveAngle = if (index % 2 == 0) -18f else 18f
    Box(
        Modifier.offset(x = stepOffset.dp, y = oliveOffset).width(48.dp).fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50), color = oliveColor,
            border = BorderStroke(borderWidth, borderColor),
            modifier = Modifier.size(oliveWidth, oliveHeight).rotate(oliveAngle)
                .testTag("onboarding_step_${index + 1}")
        ) {
            Box(Modifier.rotate(-oliveAngle), contentAlignment = Alignment.Center) {
                Text(
                    "${index + 1}", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = numberColor
                )
            }
        }
    }
}
