// Safe-app users: collects optional personal circumstances to tailor initial safety-plan checklists.
package com.olive.app.ui.users.shared.onboarding

import com.olive.app.ui.shared.OliveButton as Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AbuseType
import com.olive.app.data.account.DependantNeed
import com.olive.app.data.account.FinancialSituation
import com.olive.app.data.account.ImmigrationStatus
import com.olive.app.data.account.SafetyCircumstance
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.TransportSituation
import com.olive.app.ui.shared.Muted

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
 */
@Composable
internal fun SafetyPlanQuestionnaireDialog(
    initialIntake: SafetyPlanIntake,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (SafetyPlanIntake) -> Unit,
) {
    var draft by remember(initialIntake) { mutableStateOf(initialIntake) }
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = {
            Text(
                stringResource(R.string.onboarding_safety_questionnaire),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                SafetyPlanQuestionnaireForm(draft, enabled) { draft = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft.copy(completed = true)) },
                enabled = enabled,
                modifier = Modifier.testTag("safety_intake_dialog_save"),
            ) {
                Text(stringResource(R.string.safety_intake_dialog_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

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

private fun abuseTypeLabel(type: AbuseType): Int = when (type) {
    AbuseType.PHYSICAL -> R.string.safety_intake_abuse_physical
    AbuseType.EMOTIONAL_OR_PSYCHOLOGICAL -> R.string.safety_intake_abuse_emotional
    AbuseType.FINANCIAL -> R.string.safety_intake_abuse_financial
    AbuseType.DIGITAL_OR_STALKING -> R.string.safety_intake_abuse_digital
    AbuseType.SEXUAL -> R.string.safety_intake_abuse_sexual
    AbuseType.CULTURAL_OR_IMMIGRATION -> R.string.safety_intake_abuse_cultural
}

private fun immigrationLabel(status: ImmigrationStatus): Int = when (status) {
    ImmigrationStatus.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    ImmigrationStatus.CITIZEN -> R.string.safety_intake_immigration_citizen
    ImmigrationStatus.PERMANENT_RESIDENT -> R.string.safety_intake_immigration_pr
    ImmigrationStatus.TEMPORARY_OR_SPONSORED -> R.string.safety_intake_immigration_temporary
    ImmigrationStatus.REFUGEE_OR_CLAIMANT -> R.string.safety_intake_immigration_refugee
    ImmigrationStatus.UNDOCUMENTED_OR_UNCERTAIN -> R.string.safety_intake_immigration_uncertain
}

private fun dependantLabel(need: DependantNeed): Int = when (need) {
    DependantNeed.CHILDREN -> R.string.safety_intake_dependant_children
    DependantNeed.ELDER_OR_ADULT_DEPENDANT -> R.string.safety_intake_dependant_adult
    DependantNeed.PETS -> R.string.safety_intake_dependant_pets
}

private fun financialLabel(situation: FinancialSituation): Int = when (situation) {
    FinancialSituation.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    FinancialSituation.INDEPENDENT -> R.string.safety_intake_financial_independent
    FinancialSituation.SHARED_OR_MONITORED -> R.string.safety_intake_financial_shared
    FinancialSituation.NO_INDEPENDENT_ACCESS -> R.string.safety_intake_financial_none
}

private fun transportLabel(situation: TransportSituation): Int = when (situation) {
    TransportSituation.PREFER_NOT_TO_SAY -> R.string.safety_intake_prefer_not_to_say
    TransportSituation.OWN_VEHICLE -> R.string.safety_intake_transport_vehicle
    TransportSituation.PUBLIC_TRANSIT -> R.string.safety_intake_transport_transit
    TransportSituation.NEEDS_RIDE_SUPPORT -> R.string.safety_intake_transport_ride_support
}

private fun circumstanceLabel(circumstance: SafetyCircumstance): Int = when (circumstance) {
    SafetyCircumstance.LEGAL_OR_COURT_ORDERS -> R.string.safety_intake_circumstance_legal
    SafetyCircumstance.MARRIAGE_OR_PARENTING -> R.string.safety_intake_circumstance_marriage
    SafetyCircumstance.PRESCRIPTIONS_OR_MEDICAL -> R.string.safety_intake_circumstance_medical
    SafetyCircumstance.SHARED_LEASE_OR_MORTGAGE -> R.string.safety_intake_circumstance_housing
}
