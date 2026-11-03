// Fitness cover users: shows today's summary, step progress, and the goals shortcut.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.oliveScrollbar
import kotlin.math.roundToInt

// Shows a quick summary of steps, calories, macros, and goals.
@Composable
internal fun FitnessHomePage(snapshot: FitnessSnapshot, onGoals: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
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
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(FitnessAccent, FitnessHeroEnd)))
                    .padding(22.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.today_summary),
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper.copy(alpha = 0.82f),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeroMetric(snapshot.stepsToday.toString(), stringResource(R.string.steps_today))
                        HeroMetric(snapshot.nutrition.calories.displayAmount(), stringResource(R.string.calories))
                        HeroMetric("${snapshot.nutrition.proteinGrams.displayAmount()} g", stringResource(R.string.protein))
                    }
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
                modifier = Modifier.fillMaxWidth().clickable(onClick = onGoals),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.goals), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.edit_fitness_goals), color = FitnessSubtle)
                    }
                    Icon(Icons.Outlined.Settings, null, tint = FitnessAccent)
                }
            }
        }
    }
}
// Draws one large summary value on the fitness home page.
@Composable
private fun RowScope.HeroMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
            .background(Paper.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Paper.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
    }
}

// fitness cover shows this step summary card view so the user can view its details and use its actions.
@Composable
private fun StepSummaryCard(snapshot: FitnessSnapshot) {
    val progress = (snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal).coerceIn(0f, 1f)
    Card(
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityMiniMetric(snapshot.profile.stepGoal.toString(), stringResource(R.string.daily_step_goal))
                ActivityMiniMetric(
                    "${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km",
                    stringResource(R.string.distance),
                )
                ActivityMiniMetric(
                    "${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal",
                    stringResource(R.string.active_calories),
                )
            }
        }
    }
}

// fitness cover shows this activity mini metric view so the user can view its details and use its actions.
@Composable
private fun RowScope.ActivityMiniMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(FitnessWarmCard)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $value" }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, color = FitnessText, fontWeight = FontWeight.Bold)
        Text(label, color = FitnessSubtle, style = MaterialTheme.typography.labelSmall)
    }
}
