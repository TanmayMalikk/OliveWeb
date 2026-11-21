// Fitness cover users: shows today's summary, step progress, and the goals shortcut.
package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveCard as Card
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlin.math.roundToInt

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
