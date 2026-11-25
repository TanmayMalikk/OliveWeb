// All safe-app roles: guides new safe-app users through account setup.
package com.olive.app.ui.users.shared.onboarding

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlin.random.Random
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.data.account.AbuseType
import com.olive.app.data.account.DependantNeed
import com.olive.app.data.account.FinancialSituation
import com.olive.app.data.account.ImmigrationStatus
import com.olive.app.data.account.SafetyCircumstance
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.TransportSituation
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.WalkthroughInWindowDialog

/**
 * Requirement bullets begin neutral, then turn green or red as the user types.
 *
 * @param text Text string to display, parse, or send.
 * @param met Whether to apply met.
 * @param active Whether to apply active.
 */
@Composable
internal fun RequirementBullet(text: String, met: Boolean, active: Boolean) {
    val color = when {
        !active -> MaterialTheme.colorScheme.onSurface
        met -> OliveGreen
        else -> MaterialTheme.colorScheme.error
    }
    val status = stringResource(if (met) R.string.requirement_met_status else R.string.requirement_not_met_status)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = text
            role = Role.Checkbox
            toggleableState = if (active && met) ToggleableState.On else ToggleableState.Off
            if (active) {
                stateDescription = status
                liveRegion = LiveRegionMode.Polite
            }
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("•", color = color, fontSize = 14.sp, lineHeight = 14.sp)
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        AnimatedVisibility(
            visible = active && met,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100))
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = OliveGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Every onboarding page ends with the same Back and Next arrow controls.
 *
 * @param back Action or callback executed for back.
 * @param next Action or callback executed for next.
 * @param last Whether to apply last.
 * @param nextTag Next tag supplied to [OnboardingNavigation].
 * @param enabled Whether this feature or control is currently enabled.
 * @param nextLabel Optional next label supplied to [OnboardingNavigation].
 */
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

/**
 * Multi-page onboarding gets a width-filling branch with page-specific leaf layouts and fixed-size olives/leaves. TalkBack receives one progress summary instead of separate decorative drawing nodes.
 *
 * @param completed Completed supplied to [OnboardingProgress].
 * @param total Total supplied to [OnboardingProgress].
 * @param page Page supplied to [OnboardingProgress].
 * @param pages Pages supplied to [OnboardingProgress].
 * @param pageComplete Collection of page complete values to process.
 */
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

/**
 * Offshoots and leaf tips lean right, giving the branch a clear direction of growth.
 *
 * @receiver The Compose drawing scope and its coordinate system.
 * @param base Base supplied to [drawOnboardingSprig].
 * @param spacing Spacing supplied to [drawOnboardingSprig].
 * @param sprig Sprig supplied to [drawOnboardingSprig].
 * @param stemColor Stem color supplied to [drawOnboardingSprig].
 * @param leafColor Leaf color supplied to [drawOnboardingSprig].
 */
private fun DrawScope.drawOnboardingSprig(
    base: Offset,
    spacing: Float,
    sprig: OnboardingSprig,
    stemColor: Color,
    leafColor: Color
) {
    val reach = spacing * sprig.reach
    val rise = sprig.rise.dp.toPx()
    /**
     * Converts an address map point into the account's stored coordinate format.
     *
     * @param x X supplied to [point].
     * @param y Y supplied to [point].
     * @return The resulting point.
     */
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

/**
 * Two curved edges form a narrow olive leaf; its vein points back to the stem.
 *
 * @receiver The Compose drawing scope and its coordinate system.
 * @param base Base supplied to [drawOnboardingLeaf].
 * @param tip Tip supplied to [drawOnboardingLeaf].
 * @param color Color supplied to [drawOnboardingLeaf].
 */
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
        // Draws each onboarding leaf on alternating sides of the branch.
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

/**
 * One numbered olive owns its animation, orientation and test identity.
 *
 * @param index Zero-based item index.
 * @param done Whether to apply done.
 * @param active Whether to apply active.
 * @param stepOffset Step offset supplied to [OnboardingOlive].
 */
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

/**
 * Renders the onboarding and safety-plan tailoring questionnaire inside a vertical onboarding column or scrollable dialog.
 *
 * @param intake Current questionnaire answers.
 * @param enabled Whether the controls are interactive.
 * @param onChange Callback invoked whenever any selection changes.
 */
@Composable
internal fun SafetyPlanQuestionnaireForm(
    intake: SafetyPlanIntake,
    enabled: Boolean,
    onChange: (SafetyPlanIntake) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.safety_intake_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_abuse_title),
            subtitle = stringResource(R.string.safety_intake_abuse_subtitle),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AbuseType.entries.forEach { option ->
                    val checked = option in intake.abuseTypes
                    QuestionnaireCheckboxRow(
                        label = stringResource(abuseTypeLabel(option)),
                        checked = checked,
                        enabled = enabled,
                        tag = "intake_abuse_${option.name}",
                        onToggle = { next ->
                            val updated = if (next) intake.abuseTypes + option else intake.abuseTypes - option
                            onChange(intake.copy(abuseTypes = updated))
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_immigration_title),
            subtitle = stringResource(R.string.safety_intake_immigration_subtitle),
        ) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ImmigrationStatus.entries.forEach { option ->
                    QuestionnaireRadioRow(
                        label = stringResource(immigrationLabel(option)),
                        selected = intake.immigrationStatus == option,
                        enabled = enabled,
                        tag = "intake_immigration_${option.name}",
                        onSelect = { onChange(intake.copy(immigrationStatus = option)) },
                    )
                }
            }
        }

        HorizontalDivider()

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_dependants_title),
            subtitle = stringResource(R.string.safety_intake_dependants_subtitle),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DependantNeed.entries.forEach { option ->
                    val checked = option in intake.dependants
                    QuestionnaireCheckboxRow(
                        label = stringResource(dependantLabel(option)),
                        checked = checked,
                        enabled = enabled,
                        tag = "intake_dependant_${option.name}",
                        onToggle = { next ->
                            val updated = if (next) intake.dependants + option else intake.dependants - option
                            onChange(intake.copy(dependants = updated))
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_financial_title),
            subtitle = stringResource(R.string.safety_intake_financial_subtitle),
        ) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FinancialSituation.entries.forEach { option ->
                    QuestionnaireRadioRow(
                        label = stringResource(financialLabel(option)),
                        selected = intake.financialSituation == option,
                        enabled = enabled,
                        tag = "intake_financial_${option.name}",
                        onSelect = { onChange(intake.copy(financialSituation = option)) },
                    )
                }
            }
        }

        HorizontalDivider()

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_transport_title),
            subtitle = stringResource(R.string.safety_intake_transport_subtitle),
        ) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportSituation.entries.forEach { option ->
                    QuestionnaireRadioRow(
                        label = stringResource(transportLabel(option)),
                        selected = intake.transportSituation == option,
                        enabled = enabled,
                        tag = "intake_transport_${option.name}",
                        onSelect = { onChange(intake.copy(transportSituation = option)) },
                    )
                }
            }
        }

        HorizontalDivider()

        QuestionnaireQuestionSection(
            title = stringResource(R.string.safety_intake_circumstances_title),
            subtitle = stringResource(R.string.safety_intake_circumstances_subtitle),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SafetyCircumstance.entries.forEach { option ->
                    val checked = option in intake.circumstances
                    QuestionnaireCheckboxRow(
                        label = stringResource(circumstanceLabel(option)),
                        checked = checked,
                        enabled = enabled,
                        tag = "intake_circumstance_${option.name}",
                        onToggle = { next ->
                            val updated = if (next) intake.circumstances + option else intake.circumstances - option
                            onChange(intake.copy(circumstances = updated))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Opens the safety-plan questionnaire in a dialog so an existing user can update preferences and add matching checklist items.
 *
 * @param initialIntake Existing saved questionnaire answers.
 * @param enabled Whether the controls are currently enabled.
 * @param onDismiss Callback invoked when the user closes without saving.
 * @param onSave Callback invoked with the updated completed [SafetyPlanIntake].
 * @param inWindow Whether the dialog is hosted in the current window.
 */
@Composable
internal fun SafetyPlanQuestionnaireDialog(
    initialIntake: SafetyPlanIntake,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (SafetyPlanIntake) -> Unit,
    inWindow: Boolean = false,
) {
    var draft by remember(initialIntake) { mutableStateOf(initialIntake) }
    val titleContent: @Composable () -> Unit = {
        Text(
            stringResource(R.string.onboarding_safety_questionnaire),
            modifier = Modifier.semantics { heading() },
        )
    }
    val bodyContent: @Composable () -> Unit = {
        Column(
            Modifier.heightIn(max = if (inWindow) 240.dp else 420.dp).verticalScroll(rememberScrollState()),
        ) {
            SafetyPlanQuestionnaireForm(draft, enabled) { draft = it }
        }
    }
    val confirmContent: @Composable () -> Unit = {
        Button(
            onClick = { onSave(draft.copy(completed = true)) },
            enabled = enabled,
            modifier = Modifier.testTag("safety_intake_dialog_save"),
        ) {
            Text(stringResource(R.string.safety_intake_dialog_apply))
        }
    }
    val dismissContent: @Composable () -> Unit = {
        TextButton(onClick = onDismiss, enabled = enabled) {
            Text(stringResource(R.string.cancel))
        }
    }
    if (inWindow) {
        WalkthroughInWindowDialog(
            dialogTargetId = "safety_questionnaire_dialog",
            title = titleContent,
            onDismiss = { if (enabled) onDismiss() },
            confirmButton = confirmContent,
            dismissButton = dismissContent,
            text = bodyContent,
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (enabled) onDismiss() },
            title = titleContent,
            text = bodyContent,
            confirmButton = confirmContent,
            dismissButton = dismissContent,
        )
    }
}

/**
 * Renders a titled safety-plan onboarding question block with subtitle and option content.
 *
 * @param title The heading displayed for the section or item.
 * @param subtitle The supporting text displayed below the title.
 * @param content The composable content to render inside the component.
 */
@Composable
private fun QuestionnaireQuestionSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        content()
    }
}

/**
 * Renders a single radio selection row inside an outlined card.
 *
 * @param label The label displayed for the control.
 * @param selected Whether this item is currently selected.
 * @param enabled Whether the control or feature is enabled.
 * @param tag The support ticket category identifier.
 * @param onSelect Runs when the user chooses an option.
 */
@Composable
private fun QuestionnaireRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Renders a toggleable checkbox option row inside an outlined card.
 *
 * @param label The label displayed for the control.
 * @param checked Whether the switch is currently on.
 * @param enabled Whether the control or feature is enabled.
 * @param tag The support ticket category identifier.
 * @param onToggle Runs when the user changes the switch state.
 */
@Composable
private fun QuestionnaireCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onToggle: (Boolean) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onToggle,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Maps an [AbuseType] option to its localized label resource ID.
 *
 * @param type The selected option or category.
 * @return The localized string resource ID for the selected option.
 */
private fun abuseTypeLabel(type: AbuseType): Int = when (type) {
    AbuseType.PHYSICAL -> R.string.safety_intake_abuse_physical
    AbuseType.EMOTIONAL_OR_PSYCHOLOGICAL -> R.string.safety_intake_abuse_emotional
    AbuseType.FINANCIAL -> R.string.safety_intake_abuse_financial
    AbuseType.DIGITAL_OR_STALKING -> R.string.safety_intake_abuse_digital
    AbuseType.SEXUAL -> R.string.safety_intake_abuse_sexual
    AbuseType.CULTURAL_OR_IMMIGRATION -> R.string.safety_intake_abuse_cultural
}

/**
 * Maps an [ImmigrationStatus] option to its localized label resource ID.
 *
 * @param status The option representing the user's immigration status.
 * @return The localized string resource ID for the selected option.
 */
private fun immigrationLabel(status: ImmigrationStatus): Int = when (status) {
    ImmigrationStatus.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    ImmigrationStatus.CITIZEN -> R.string.safety_intake_immigration_citizen
    ImmigrationStatus.PERMANENT_RESIDENT -> R.string.safety_intake_immigration_pr
    ImmigrationStatus.TEMPORARY_OR_SPONSORED -> R.string.safety_intake_immigration_temporary
    ImmigrationStatus.REFUGEE_OR_CLAIMANT -> R.string.safety_intake_immigration_refugee
    ImmigrationStatus.UNDOCUMENTED_OR_UNCERTAIN -> R.string.safety_intake_immigration_uncertain
}

/**
 * Maps a [DependantNeed] option to its localized label resource ID.
 *
 * @param need The selected dependant-care need.
 * @return The localized string resource ID for the selected option.
 */
private fun dependantLabel(need: DependantNeed): Int = when (need) {
    DependantNeed.CHILDREN -> R.string.safety_intake_dependant_children
    DependantNeed.ELDER_OR_ADULT_DEPENDANT -> R.string.safety_intake_dependant_adult
    DependantNeed.PETS -> R.string.safety_intake_dependant_pets
}

/**
 * Maps a [FinancialSituation] option to its localized label resource ID.
 *
 * @param situation The recorded situation described by the user.
 * @return The localized string resource ID for the selected option.
 */
private fun financialLabel(situation: FinancialSituation): Int = when (situation) {
    FinancialSituation.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    FinancialSituation.INDEPENDENT -> R.string.safety_intake_financial_independent
    FinancialSituation.SHARED_OR_MONITORED -> R.string.safety_intake_financial_shared
    FinancialSituation.NO_INDEPENDENT_ACCESS -> R.string.safety_intake_financial_none
}

/**
 * Maps a [TransportSituation] option to its localized label resource ID.
 *
 * @param situation The recorded situation described by the user.
 * @return The localized string resource ID for the selected option.
 */
private fun transportLabel(situation: TransportSituation): Int = when (situation) {
    TransportSituation.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    TransportSituation.OWN_VEHICLE -> R.string.safety_intake_transport_vehicle
    TransportSituation.PUBLIC_TRANSIT -> R.string.safety_intake_transport_transit
    TransportSituation.NEEDS_RIDE_SUPPORT -> R.string.safety_intake_transport_ride_support
}

/**
 * Maps a [SafetyCircumstance] option to its localized label resource ID.
 *
 * @param circumstance The selected safety circumstance.
 * @return The localized string resource ID for the selected option.
 */
private fun circumstanceLabel(circumstance: SafetyCircumstance): Int = when (circumstance) {
    SafetyCircumstance.LEGAL_OR_COURT_ORDERS -> R.string.safety_intake_circumstance_legal
    SafetyCircumstance.MARRIAGE_OR_PARENTING -> R.string.safety_intake_circumstance_marriage
    SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL -> R.string.safety_intake_circumstance_medical
    SafetyCircumstance.SHARED_LEASE_OR_MORTGAGE -> R.string.safety_intake_circumstance_housing
}
