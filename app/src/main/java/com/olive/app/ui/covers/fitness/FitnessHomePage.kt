// Fitness cover users: shows today's summary, step progress, and the goals shortcut.
package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveFilledTonalButton as FilledTonalButton
import com.olive.app.ui.shared.OliveFilledTonalIconButton as FilledTonalIconButton
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shows a quick summary of steps, calories, macros, and goals.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 * @param onGoals Callback invoked when goals changes or occurs.
 */
@Composable
internal fun FitnessHomePage(snapshot: FitnessSnapshot, onGoals: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val summaryDescription = buildString {
        append(stringResource(R.string.today_summary))
        append(". ${snapshot.stepsToday} ${stringResource(R.string.steps_today)}. ")
        append("${snapshot.nutrition.calories.displayAmount()} ${stringResource(R.string.calories)}. ")
        append("${snapshot.nutrition.proteinGrams.displayAmount()} ${stringResource(R.string.protein)}.")
    }
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("fitness_home"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.fitness_home_message),
                color = FitnessSubtle,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                    .clearAndSetSemantics { contentDescription = summaryDescription },
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = PaleOlive, contentColor = Ink),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.today_summary),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    SummaryMetric(snapshot.stepsToday.toString(), stringResource(R.string.steps_today), Icons.AutoMirrored.Outlined.DirectionsWalk)
                    HorizontalDivider(color = OliveDivider)
                    SummaryMetric(snapshot.nutrition.calories.displayAmount(), stringResource(R.string.calories), Icons.Outlined.LocalFireDepartment)
                    HorizontalDivider(color = OliveDivider)
                    SummaryMetric("${snapshot.nutrition.proteinGrams.displayAmount()} g", stringResource(R.string.protein), Icons.Outlined.Restaurant)
                }
            }
        }
        item { StepSummaryCard(snapshot) }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = FitnessWarmCard,
                    contentColor = FitnessText,
                ),
                modifier = Modifier.fillMaxWidth(),
                onClick = onGoals,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.goals), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.edit_fitness_goals), color = FitnessSubtle)
                    }
                    Icon(Icons.Outlined.Settings, null, tint = FitnessAccent)
                }
            }
        }
    }
}
/**
 * Draws one large summary value on the fitness home page.
 *
 * @param value Input value to store, parse, or format.
 * @param label Accessible label or display caption.
 * @param icon Icon supplied to [SummaryMetric].
 */
@Composable
private fun SummaryMetric(value: String, label: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Paper),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = OliveGreen) }
        Text(label, color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value, color = DarkOlive, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

/**
 * Fitness cover shows this step summary card view so the user can view its details and use its actions.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 */
@Composable
private fun StepSummaryCard(snapshot: FitnessSnapshot) {
    val progress = (snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal).coerceIn(0f, 1f)
    val description = buildString {
        append("${stringResource(R.string.steps_today)}, ${snapshot.stepsToday}. ")
        append("${stringResource(R.string.daily_step_goal)}, ${snapshot.profile.stepGoal}. ")
        append("${stringResource(R.string.distance)}, ")
        append("${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km. ")
        append("${stringResource(R.string.active_calories)}, ")
        append("${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal.")
    }
    Card(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(PaleOlive),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, tint = FitnessAccent) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.steps_today), color = FitnessSubtle)
                        Text(
                            snapshot.stepsToday.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.goals), color = FitnessSubtle)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(snapshot.profile.stepGoal.toString(), stringResource(R.string.daily_step_goal), Icons.AutoMirrored.Outlined.DirectionsWalk)
                SummaryMetric(
                    "${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km",
                    stringResource(R.string.distance),
                    Icons.Outlined.Route,
                )
                SummaryMetric(
                    "${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal",
                    stringResource(R.string.active_calories),
                    Icons.Outlined.LocalFireDepartment,
                )
            }
        }
    }
}

internal val TextStyle.tabularDigits: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

internal val TextStyle.balancedHeadline: TextStyle
    get() = copy(lineBreak = LineBreak.Heading)

// Two cubic segments per corner keep curvature flat where each corner joins a straight edge.
internal val FitnessContinuousCorners = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val r = minOf(w, h) * 0.19f
    val middle = r * 0.16f
    val near = r * 0.32f
    val far = r * 0.64f
    moveTo(r, 0f)
    lineTo(w - r, 0f)
    cubicTo(w - far, 0f, w - near, 0f, w - middle, middle)
    cubicTo(w, near, w, far, w, r)
    lineTo(w, h - r)
    cubicTo(w, h - far, w, h - near, w - middle, h - middle)
    cubicTo(w - near, h, w - far, h, w - r, h)
    lineTo(r, h)
    cubicTo(far, h, near, h, middle, h - middle)
    cubicTo(0f, h - near, 0f, h - far, 0f, h - r)
    lineTo(0f, r)
    cubicTo(0f, far, 0f, near, middle, middle)
    cubicTo(near, 0f, far, 0f, r, 0f)
    close()
}

/**
 * Composes the water tracker UI element.
 *
 * @param totalMl Total ml supplied to [WaterTracker].
 * @param goalMl Goal ml supplied to [WaterTracker].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onAdd Callback invoked when add changes or occurs.
 */
@Composable
internal fun WaterTracker(totalMl: Int, goalMl: Int, enabled: Boolean, onAdd: (Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val fraction = (totalMl.toFloat() / goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
    val description = stringResource(R.string.nutrition_water_progress, totalMl, goalMl)
    TextButton(
        onClick = { open = true },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        WaterGlass(fraction, Modifier.size(42.dp, 56.dp))
        Column(Modifier.padding(start = 4.dp).clearAndSetSemantics { }) {
            Text(stringResource(R.string.nutrition_water), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.fitness_diary_volume, totalMl), style = MaterialTheme.typography.bodySmall.tabularDigits)
        }
    }
    if (open) WaterLogDialog(totalMl, goalMl, enabled, onDismiss = { open = false }) { amount, goal ->
        onAdd(amount, goal)
        open = false
    }
}

/**
 * Displays the water log dialog overlay.
 *
 * @param totalMl Total ml supplied to [WaterLogDialog].
 * @param goalMl Goal ml supplied to [WaterLogDialog].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onSave Callback invoked when save changes or occurs.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WaterLogDialog(
    totalMl: Int,
    goalMl: Int,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var adjustment by rememberSaveable { mutableIntStateOf(0) }
    var customVisible by rememberSaveable { mutableStateOf(false) }
    var customAmount by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf(goalMl.toString()) }
    val updatedGoal = goal.toIntOrNull()
    val total = (totalMl.toLong() + adjustment).coerceAtLeast(0)
    /**
     * Computes adjust.
     *
     * @param amount Amount supplied to [adjust].
     */
    fun adjust(amount: Int) {
        adjustment = (adjustment + amount).coerceIn(-minOf(totalMl, 10_000), 10_000)
    }
    val sheet = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        modifier = Modifier.testTag("fitness_water_sheet"),
    ) {
        Column(Modifier.fillMaxHeight(.86f).imePadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                Text(stringResource(R.string.nutrition_add_water), style = MaterialTheme.typography.titleLarge.balancedHeadline)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(
                    shape = FitnessContinuousCorners,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.fitness_diary_water_total), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.fitness_diary_volume, total), style = MaterialTheme.typography.headlineMedium.tabularDigits)
                        WaterGlass(total.toFloat() / (updatedGoal?.coerceAtLeast(1) ?: goalMl), Modifier.size(60.dp, 80.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = { adjust(-250) }, enabled = enabled && total > 0 && adjustment > -10_000) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.nutrition_water_minus))
                    }
                    listOf(250, 500, 1000).forEach { amount ->
                        val actionLabel = stringResource(R.string.fitness_diary_add_volume, amount)
                        FilledTonalButton(
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = actionLabel
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            onClick = { adjust(amount) },
                            enabled = enabled && adjustment <= 10_000 - amount,
                        ) {
                            Text(stringResource(R.string.fitness_diary_volume, amount), Modifier.clearAndSetSemantics { })
                        }
                    }
                }
                OutlinedButton(onClick = { customVisible = !customVisible }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fitness_diary_custom_water))
                }
                if (customVisible) {
                    val amount = customAmount.toIntOrNull()
                    OutlinedTextField(
                        customAmount, { customAmount = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.nutrition_water_amount)) },
                        suffix = { Text(stringResource(R.string.nutrition_volume_unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            IconButton(
                                onClick = { amount?.let { adjust(it); customAmount = ""; customVisible = false } },
                                enabled = enabled && amount != null && amount in 1..minOf(10_000, 10_000 - adjustment),
                            ) { Icon(Icons.Outlined.Add, stringResource(R.string.nutrition_add_water)) }
                        },
                    )
                }
                OutlinedTextField(
                    goal, { goal = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.nutrition_water_goal)) },
                    suffix = { Text(stringResource(R.string.nutrition_volume_unit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = updatedGoal == null || updatedGoal !in 250..10_000,
                )
            }
            Button(
                onClick = { updatedGoal?.let { onSave(adjustment, it) } },
                enabled = enabled && adjustment != 0 && updatedGoal != null && updatedGoal in 250..10_000,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

/**
 * Composes the water glass UI element.
 *
 * @param fraction Fraction supplied to [WaterGlass].
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
private fun WaterGlass(fraction: Float, modifier: Modifier) {
    val target = (fraction).coerceIn(0f, 1f)
    val fill by animateFloatAsState(target, animationSpec = tween(1000), label = "water_fill")
    val outline = Color(0xFF176B9B)
    val water = Color(0xFF258ED2)
    val ripples = rememberInfiniteTransition(label = "water_ripples")
    val phase by ripples.animateFloat(0f, (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "water_wave_phase")
    Canvas(modifier) {
        val glass = Path().apply {
            moveTo(size.width * .12f, size.height * .08f); lineTo(size.width * .88f, size.height * .08f)
            lineTo(size.width * .76f, size.height * .92f); lineTo(size.width * .24f, size.height * .92f); close()
        }
        drawPath(glass, Color(0xFFE4F3FC))
        clipPath(glass) {
            if (fill > 0f) {
                val level = size.height * (.92f - .84f * fill)
                val amplitude = minOf(1.8.dp.toPx(), size.height * .84f * fill / 3f)
                /**
                 * Computes wave.
                 *
                 * @param offset Offset supplied to [wave].
                 * @return The resulting [Path] instance.
                 */
                fun wave(offset: Float): Path = Path().apply {
                    moveTo(0f, size.height)
                    for (step in 0..32) {
                        val x = size.width * step / 32f
                        val y = level + sin(x / size.width * 2f * PI.toFloat() + phase + offset) * amplitude
                        lineTo(x, y)
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(wave(PI.toFloat()), water.copy(alpha = .35f))
                drawPath(wave(0f), water.copy(alpha = .85f))
            }
            drawLine(Color.White.copy(alpha = .8f), Offset(size.width * .24f, size.height * .2f),
                Offset(size.width * .29f, size.height * .62f), strokeWidth = 1.5.dp.toPx())
        }
        drawPath(glass, outline, style = Stroke(2.dp.toPx()))
    }
}
