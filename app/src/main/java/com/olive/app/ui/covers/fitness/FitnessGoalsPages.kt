// Fitness cover users: configures activity and nutrition goals directly or through a guided questionnaire.
package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.oliveErrorShake
import com.olive.app.ui.shared.rememberFieldErrorShakeTrigger
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.FitnessAccent
import com.olive.app.ui.shared.FitnessSubtle
import com.olive.app.ui.shared.FitnessSurface
import com.olive.app.ui.shared.FitnessText
import com.olive.app.ui.shared.FitnessWarmCard
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveDivider
import com.olive.app.ui.shared.OliveOutline
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.oliveScrollbar
import kotlin.math.roundToInt

/**
 * Fitness cover shows this fitness goals page view so the user can view and edit goals manually or launch the questionnaire.
 *
 * @param profile Account profile details.
 * @param enabled Whether this feature or control is currently enabled.
 * @param padding Padding supplied to [FitnessGoalsPage].
 * @param onSave Callback invoked when save changes or occurs.
 * @param onOpenQuestionnaire Callback invoked to start or re-run the guided goal setup questionnaire.
 */
@Composable
internal fun FitnessGoalsPage(
    profile: FitnessProfile,
    enabled: Boolean,
    padding: PaddingValues,
    onSave: (FitnessProfile) -> Unit,
    onOpenQuestionnaire: () -> Unit = {},
) {
    val headingFocus = remember { FocusRequester() }
    var weight by remember(profile) { mutableStateOf(profile.weightKg.roundToInt().toString()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.roundToInt().toString()) }
    var steps by remember(profile) { mutableStateOf(profile.stepGoal.toString()) }
    var calories by remember(profile) { mutableStateOf(profile.calorieGoal.toString()) }
    var protein by remember(profile) { mutableStateOf(profile.proteinGoal.toString()) }
    var carbohydrates by remember(profile) { mutableStateOf(profile.carbohydrateGoal.toString()) }
    var fat by remember(profile) { mutableStateOf(profile.fatGoal.toString()) }
    var waterGoal by remember(profile) { mutableStateOf(profile.waterGoalMl.toString()) }
    // Move TalkBack to the goals heading after the submenu opens.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        headingFocus.requestFocus()
    }
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.fitness_goals),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.focusRequester(headingFocus).focusable().semantics { heading() },
            )
        }
        item {
            Card(
                onClick = onOpenQuestionnaire,
                enabled = enabled,
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = FitnessWarmCard, contentColor = FitnessText),
                modifier = Modifier.fillMaxWidth().testTag("open_goal_questionnaire"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = FitnessAccent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.fitness_questionnaire_recalculate_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.fitness_questionnaire_recalculate_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = FitnessSubtle,
                        )
                    }
                }
            }
        }
        item { GoalField(weight, { weight = it }, stringResource(R.string.weight_kg), enabled) }
        item { GoalField(height, { height = it }, stringResource(R.string.height_cm), enabled) }
        item { GoalField(steps, { steps = it }, stringResource(R.string.daily_step_goal), enabled) }
        item { HorizontalDivider() }
        item { GoalField(calories, { calories = it }, stringResource(R.string.calorie_goal), enabled) }
        item { GoalField(protein, { protein = it }, stringResource(R.string.protein_goal), enabled) }
        item {
            GoalField(
                carbohydrates,
                { carbohydrates = it },
                stringResource(R.string.carbohydrate_goal),
                enabled,
            )
        }
        item { GoalField(fat, { fat = it }, stringResource(R.string.fat_goal), enabled) }
        item { GoalField(waterGoal, { waterGoal = it }, stringResource(R.string.daily_water_goal_ml), enabled) }
        item {
            Button(
                onClick = {
                    onSave(
                        FitnessProfile(
                            weightKg = weight.toFloatOrNull() ?: profile.weightKg,
                            heightCm = height.toFloatOrNull() ?: profile.heightCm,
                            stepGoal = steps.toIntOrNull() ?: profile.stepGoal,
                            calorieGoal = calories.toIntOrNull() ?: profile.calorieGoal,
                            proteinGoal = protein.toIntOrNull() ?: profile.proteinGoal,
                            carbohydrateGoal = carbohydrates.toIntOrNull() ?: profile.carbohydrateGoal,
                            fatGoal = fat.toIntOrNull() ?: profile.fatGoal,
                            waterGoalMl = waterGoal.toIntOrNull() ?: profile.waterGoalMl,
                        ),
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("save_fitness_goals"),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

/**
 * Guides the user through a 4-step questionnaire to calculate personalized initial fitness and nutrition targets.
 *
 * @param initialInput Previously saved or default questionnaire values for initialization.
 * @param enabled Whether interactive controls on the page are enabled.
 * @param padding Outer scaffold or inset padding applied to this page.
 * @param onSaveGoals Callback invoked when the user confirms and saves the calculated goals.
 * @param onCustomizeGoals Callback invoked when the user saves the calculated goals and opens manual fine-tuning.
 * @param onCancel Callback invoked when the user exits the questionnaire without applying changes.
 */
@Composable
internal fun FitnessGoalQuestionnairePage(
    initialInput: FitnessGoalQuestionnaireInput,
    enabled: Boolean,
    padding: PaddingValues,
    onSaveGoals: (FitnessGoalQuestionnaireInput, FitnessProfile) -> Unit,
    onCustomizeGoals: (FitnessGoalQuestionnaireInput, FitnessProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var bodyShake by rememberSaveable { mutableIntStateOf(0) }
    var primaryGoalName by rememberSaveable(initialInput) { mutableStateOf(initialInput.primaryGoal.name) }
    var activityLevelName by rememberSaveable(initialInput) { mutableStateOf(initialInput.activityLevel.name) }
    var nutritionStyleName by rememberSaveable(initialInput) { mutableStateOf(initialInput.nutritionStyle.name) }
    var metabolicBasisName by rememberSaveable(initialInput) { mutableStateOf(initialInput.metabolicBasis.name) }
    var ageText by rememberSaveable(initialInput) { mutableStateOf(initialInput.ageYears.toString()) }
    var heightText by rememberSaveable(initialInput) { mutableStateOf(initialInput.heightCm.roundToInt().toString()) }
    var weightText by rememberSaveable(initialInput) { mutableStateOf(initialInput.weightKg.roundToInt().toString()) }

    val primaryGoal = FitnessPrimaryGoal.valueOf(primaryGoalName)
    val activityLevel = FitnessActivityLevel.valueOf(activityLevelName)
    val nutritionStyle = FitnessNutritionStyle.valueOf(nutritionStyleName)
    val metabolicBasis = FitnessMetabolicBasis.valueOf(metabolicBasisName)
    val parsedAge = ageText.toIntOrNull()
    val parsedHeight = heightText.toFloatOrNull()
    val parsedWeight = weightText.toFloatOrNull()

    val currentInput = FitnessGoalQuestionnaireInput(
        primaryGoal = primaryGoal,
        activityLevel = activityLevel,
        nutritionStyle = nutritionStyle,
        ageYears = parsedAge ?: -1,
        heightCm = parsedHeight ?: Float.NaN,
        weightKg = parsedWeight ?: Float.NaN,
        metabolicBasis = metabolicBasis,
    )
    val bodyValid = FitnessGoalCalculator.isValid(currentInput)
    val stepHeadingFocus = remember { FocusRequester() }
    LaunchedEffect(step) {
        withFrameNanos { }
        stepHeadingFocus.requestFocus()
    }

    val (stepTitle, stepSubtitle) = when (step) {
        0 -> stringResource(R.string.fitness_questionnaire_step_goal_title) to
            stringResource(R.string.fitness_questionnaire_step_goal_subtitle)
        1 -> stringResource(R.string.fitness_questionnaire_step_lifestyle_title) to
            stringResource(R.string.fitness_questionnaire_step_lifestyle_subtitle)
        2 -> stringResource(R.string.fitness_questionnaire_step_body_title) to
            stringResource(R.string.fitness_questionnaire_step_body_subtitle)
        else -> stringResource(R.string.fitness_questionnaire_step_review_title) to
            stringResource(R.string.fitness_questionnaire_step_review_subtitle)
    }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.fitness_questionnaire_step_progress, step + 1, 4),
                    style = MaterialTheme.typography.labelLarge,
                    color = FitnessAccent,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { (step + 1) / 4f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                )
            }
            IconButton(
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.testTag("questionnaire_close"),
            ) {
                Icon(Icons.Outlined.Close, stringResource(R.string.close))
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stepTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.focusRequester(stepHeadingFocus).focusable().semantics { heading() },
            )
            Text(stepSubtitle, style = MaterialTheme.typography.bodyMedium, color = FitnessSubtle)

            when (step) {
                0 -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FitnessPrimaryGoal.entries.forEach { goal ->
                        val (titleRes, descRes) = primaryGoalStrings(goal)
                        QuestionnaireOptionCard(
                            title = stringResource(titleRes),
                            description = stringResource(descRes),
                            selected = primaryGoal == goal,
                            enabled = enabled,
                            tag = "questionnaire_goal_${goal.name}",
                            onClick = { primaryGoalName = goal.name },
                        )
                    }
                }

                1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.fitness_questionnaire_activity_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FitnessActivityLevel.entries.forEach { level ->
                            val (titleRes, descRes) = activityLevelStrings(level)
                            QuestionnaireOptionCard(
                                title = stringResource(titleRes),
                                description = stringResource(descRes),
                                selected = activityLevel == level,
                                enabled = enabled,
                                tag = "questionnaire_activity_${level.name}",
                                onClick = { activityLevelName = level.name },
                            )
                        }
                    }
                    HorizontalDivider(color = OliveDivider)
                    Text(
                        stringResource(R.string.fitness_questionnaire_nutrition_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FitnessNutritionStyle.entries.forEach { style ->
                            val (titleRes, descRes) = nutritionStyleStrings(style)
                            QuestionnaireOptionCard(
                                title = stringResource(titleRes),
                                description = stringResource(descRes),
                                selected = nutritionStyle == style,
                                enabled = enabled,
                                tag = "questionnaire_nutrition_${style.name}",
                                onClick = { nutritionStyleName = style.name },
                            )
                        }
                    }
                }

                2 -> BodyDetailsStep(
                    ageText = ageText,
                    onAgeChange = { ageText = it },
                    ageError = parsedAge == null || parsedAge !in FitnessGoalCalculator.MIN_AGE..FitnessGoalCalculator.MAX_AGE,
                    heightText = heightText,
                    onHeightChange = { heightText = it },
                    heightError = parsedHeight == null || !parsedHeight.isFinite() ||
                        parsedHeight !in FitnessGoalCalculator.MIN_HEIGHT_CM..FitnessGoalCalculator.MAX_HEIGHT_CM,
                    weightText = weightText,
                    onWeightChange = { weightText = it },
                    weightError = parsedWeight == null || !parsedWeight.isFinite() ||
                        parsedWeight !in FitnessGoalCalculator.MIN_WEIGHT_KG..FitnessGoalCalculator.MAX_WEIGHT_KG,
                    metabolicBasis = metabolicBasis,
                    onBasisSelect = { metabolicBasisName = it.name },
                    enabled = enabled,
                    shakeTrigger = bodyShake,
                )

                else -> RecommendationReviewStep(
                    recommendation = FitnessGoalCalculator.calculate(currentInput),
                )
            }
        }

        if (step < 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { if (step == 0) onCancel() else step-- },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("questionnaire_back"),
                ) { Text(stringResource(R.string.fitness_questionnaire_back)) }
                Button(
                    onClick = {
                        if (step == 2 && !bodyValid) bodyShake += 1
                        else step++
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("questionnaire_next"),
                ) { Text(stringResource(R.string.fitness_questionnaire_next)) }
            }
        } else {
            val recommendation = FitnessGoalCalculator.calculate(currentInput)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSaveGoals(currentInput, recommendation.profile) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("questionnaire_save_goals"),
                ) { Text(stringResource(R.string.fitness_questionnaire_save_goals)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { step-- },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag("questionnaire_back"),
                    ) { Text(stringResource(R.string.fitness_questionnaire_back)) }
                    OutlinedButton(
                        onClick = { onCustomizeGoals(currentInput, recommendation.profile) },
                        enabled = enabled,
                        modifier = Modifier.weight(2f).testTag("questionnaire_customize_goals"),
                    ) { Text(stringResource(R.string.fitness_questionnaire_customize_goals)) }
                }
            }
        }
    }
}

/**
 * Renders the age, height, weight, and metabolic basis inputs step of the fitness goal wizard.
 *
 * @param ageText The age currently entered in the form.
 * @param onAgeChange Receives changes to the age text.
 * @param ageError The validation error for the age field, if any.
 * @param heightText The height currently entered in the form.
 * @param onHeightChange Receives changes to the height text.
 * @param heightError The validation error for the height field, if any.
 * @param weightText The weight currently entered in the form.
 * @param onWeightChange Receives changes to the weight text.
 * @param weightError The validation error for the weight field, if any.
 * @param metabolicBasis The selected equation basis for estimating energy needs.
 * @param onBasisSelect Receives the selected basis for the calorie estimate.
 * @param enabled Whether the control or feature is enabled.
 * @param shakeTrigger The counter whose change starts the field's error animation.
 */
@Composable
private fun BodyDetailsStep(
    ageText: String,
    onAgeChange: (String) -> Unit,
    ageError: Boolean,
    heightText: String,
    onHeightChange: (String) -> Unit,
    heightError: Boolean,
    weightText: String,
    onWeightChange: (String) -> Unit,
    weightError: Boolean,
    metabolicBasis: FitnessMetabolicBasis,
    onBasisSelect: (FitnessMetabolicBasis) -> Unit,
    enabled: Boolean,
    shakeTrigger: Int = 0,
) {
    GoalField(
        value = ageText,
        onChange = onAgeChange,
        label = stringResource(R.string.fitness_questionnaire_age),
        enabled = enabled,
        isError = ageError,
        supportingText = if (ageError) stringResource(R.string.fitness_questionnaire_age_error) else null,
        shakeTrigger = shakeTrigger,
        modifier = Modifier.fillMaxWidth().testTag("questionnaire_age"),
    )
    GoalField(
        value = heightText,
        onChange = onHeightChange,
        label = stringResource(R.string.height_cm),
        enabled = enabled,
        isError = heightError,
        supportingText = if (heightError) stringResource(R.string.fitness_questionnaire_height_error) else null,
        shakeTrigger = shakeTrigger,
        modifier = Modifier.fillMaxWidth().testTag("questionnaire_height"),
    )
    GoalField(
        value = weightText,
        onChange = onWeightChange,
        label = stringResource(R.string.weight_kg),
        enabled = enabled,
        isError = weightError,
        supportingText = if (weightError) stringResource(R.string.fitness_questionnaire_weight_error) else null,
        shakeTrigger = shakeTrigger,
        modifier = Modifier.fillMaxWidth().testTag("questionnaire_weight"),
    )
    Text(
        stringResource(R.string.fitness_questionnaire_formula_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FitnessMetabolicBasis.entries.forEach { basis ->
            val labelRes = when (basis) {
                FitnessMetabolicBasis.FEMALE -> R.string.fitness_questionnaire_formula_female
                FitnessMetabolicBasis.MALE -> R.string.fitness_questionnaire_formula_male
                FitnessMetabolicBasis.NEUTRAL -> R.string.fitness_questionnaire_formula_neutral
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).selectable(
                    selected = metabolicBasis == basis,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onBasisSelect(basis) },
                ).padding(horizontal = 8.dp, vertical = 6.dp).testTag("questionnaire_basis_${basis.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = metabolicBasis == basis, onClick = null, enabled = enabled)
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text(
        stringResource(R.string.fitness_questionnaire_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = FitnessSubtle,
    )
}

/**
 * Displays the calculated fitness and nutrition goal summary before saving.
 *
 * @param recommendation The calorie and activity recommendation prepared for the user.
 */
@Composable
private fun RecommendationReviewStep(recommendation: FitnessGoalRecommendation) {
    val profile = recommendation.profile
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = PaleOlive, contentColor = Ink),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.fitness_questionnaire_fitness_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ReviewMetricRow(
                label = stringResource(R.string.daily_step_goal),
                value = "${profile.stepGoal} ${stringResource(R.string.fitness_steps_unit)}",
            )
            HorizontalDivider(color = OliveDivider)
            ReviewMetricRow(
                label = stringResource(R.string.daily_water_goal_ml),
                value = stringResource(R.string.fitness_diary_volume, profile.waterGoalMl),
            )
        }
    }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface, contentColor = FitnessText),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.fitness_questionnaire_nutrition_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ReviewMetricRow(
                label = stringResource(R.string.calorie_goal),
                value = "${profile.calorieGoal} ${stringResource(R.string.fitness_kilocalories_unit)}",
            )
            HorizontalDivider(color = OliveDivider)
            ReviewMetricRow(
                label = stringResource(R.string.protein),
                value = "${profile.proteinGoal} ${stringResource(R.string.fitness_grams_unit)}",
            )
            HorizontalDivider(color = OliveDivider)
            ReviewMetricRow(
                label = stringResource(R.string.carbohydrates),
                value = "${profile.carbohydrateGoal} ${stringResource(R.string.fitness_grams_unit)}",
            )
            HorizontalDivider(color = OliveDivider)
            ReviewMetricRow(
                label = stringResource(R.string.fat),
                value = "${profile.fatGoal} ${stringResource(R.string.fitness_grams_unit)}",
            )
        }
    }
    Text(
        stringResource(R.string.fitness_questionnaire_maintenance_note, recommendation.estimatedMaintenanceCalories),
        style = MaterialTheme.typography.bodySmall,
        color = FitnessSubtle,
    )
    Text(
        stringResource(R.string.fitness_questionnaire_estimate_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = FitnessSubtle,
    )
}

/**
 * Displays a single label-and-value row in the goal recommendation summary.
 *
 * @param label The label displayed for the control.
 * @param value The formatted result to display beside the metric label.
 */
@Composable
private fun ReviewMetricRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium.tabularDigits, fontWeight = FontWeight.Bold, color = DarkOlive)
    }
}

/**
 * Renders a selectable card option with title, description, and check indicator.
 *
 * @param title The heading displayed for the section or item.
 * @param description Supporting text displayed with the option.
 * @param selected Whether this item is currently selected.
 * @param enabled Whether the control or feature is enabled.
 * @param tag The support ticket category identifier.
 * @param onClick Runs when the user activates the control.
 */
@Composable
private fun QuestionnaireOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) PaleOlive else FitnessSurface,
            contentColor = FitnessText,
        ),
        border = if (selected) BorderStroke(1.5.dp, FitnessAccent) else BorderStroke(1.dp, OliveOutline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ).testTag(tag),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = FitnessSubtle)
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = FitnessAccent)
        }
    }
}

/**
 * Returns the title and description string resources for a primary fitness [goal].
 *
 * @param goal The selected primary fitness goal.
 * @return The title and description string resource IDs, in that order.
 */
private fun primaryGoalStrings(goal: FitnessPrimaryGoal): Pair<Int, Int> = when (goal) {
    FitnessPrimaryGoal.LOSE_WEIGHT ->
        R.string.fitness_questionnaire_goal_lose to R.string.fitness_questionnaire_goal_lose_desc
    FitnessPrimaryGoal.MAINTAIN ->
        R.string.fitness_questionnaire_goal_maintain to R.string.fitness_questionnaire_goal_maintain_desc
    FitnessPrimaryGoal.BUILD_MUSCLE ->
        R.string.fitness_questionnaire_goal_muscle to R.string.fitness_questionnaire_goal_muscle_desc
    FitnessPrimaryGoal.IMPROVE_FITNESS ->
        R.string.fitness_questionnaire_goal_fitness to R.string.fitness_questionnaire_goal_fitness_desc
}

/**
 * Returns the title and description string resources for an activity [level].
 *
 * @param level The selected activity level.
 * @return The title and description string resource IDs, in that order.
 */
private fun activityLevelStrings(level: FitnessActivityLevel): Pair<Int, Int> = when (level) {
    FitnessActivityLevel.SEDENTARY ->
        R.string.fitness_questionnaire_activity_sedentary to R.string.fitness_questionnaire_activity_sedentary_desc
    FitnessActivityLevel.LIGHTLY_ACTIVE ->
        R.string.fitness_questionnaire_activity_light to R.string.fitness_questionnaire_activity_light_desc
    FitnessActivityLevel.MODERATELY_ACTIVE ->
        R.string.fitness_questionnaire_activity_moderate to R.string.fitness_questionnaire_activity_moderate_desc
    FitnessActivityLevel.VERY_ACTIVE ->
        R.string.fitness_questionnaire_activity_very to R.string.fitness_questionnaire_activity_very_desc
}

/**
 * Returns the title and description string resources for a nutrition [style].
 *
 * @param style The selected nutrition style.
 * @return The title and description string resource IDs, in that order.
 */
private fun nutritionStyleStrings(style: FitnessNutritionStyle): Pair<Int, Int> = when (style) {
    FitnessNutritionStyle.BALANCED ->
        R.string.fitness_questionnaire_nutrition_balanced to R.string.fitness_questionnaire_nutrition_balanced_desc
    FitnessNutritionStyle.HIGH_PROTEIN ->
        R.string.fitness_questionnaire_nutrition_high_protein to R.string.fitness_questionnaire_nutrition_high_protein_desc
    FitnessNutritionStyle.LOWER_CARB ->
        R.string.fitness_questionnaire_nutrition_lower_carb to R.string.fitness_questionnaire_nutrition_lower_carb_desc
}

/**
 * Draws a numeric goal field for the fitness cover.
 *
 * @param value Input value to store, parse, or format.
 * @param onChange Callback invoked when the value or observed state changes.
 * @param label Accessible label or display caption.
 * @param enabled Whether this feature or control is currently enabled.
 * @param isError Whether validation flagged this value as invalid.
 * @param supportingText Optional helper or error caption shown beneath the field.
 * @param modifier Compose modifier applied to the field.
 * @param shakeTrigger The counter whose change starts the field's error animation.
 */
@Composable
private fun GoalField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean = false,
    supportingText: String? = null,
    shakeTrigger: Int = 0,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val effectiveShake = rememberFieldErrorShakeTrigger(
        if (isError) (supportingText ?: label) else null,
        shakeTrigger,
    )
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { message -> { Text(message) } },
        modifier = modifier.oliveErrorShake(effectiveShake),
    )
}
