// Watch fitness users: shows short, readable dashboards for activity and nutrition.
package com.olive.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val WatchBackground = Color(0xFFFAF9F5)
internal val WatchSurface = Color.White
internal val WatchAccent = Color(0xFF45613C)
internal val WatchText = Color(0xFF253322)
internal val WatchSubtle = Color(0xFF677A3B)
internal val WatchPaleOlive = Color(0xFFE7EDDD)
internal val WatchLightSage = Color(0xFFD8E3CF)
internal val WatchRed = Color(0xFF9A4D45)

internal enum class WatchPage(val label: String) {
    HOME("Home"),
    MOVE("Move"),
    VITALS("Vitals"),
    FOOD("Food"),
}
// Builds the light olive fitness theme used throughout the watch app.
@Composable
internal fun OliveWatchFitnessApp(state: WatchFitnessState) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = WatchAccent,
            onPrimary = WatchSurface,
            background = WatchBackground,
            onBackground = WatchText,
            surfaceContainer = WatchSurface,
            onSurface = WatchText,
            onSurfaceVariant = WatchText,
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(WatchBackground)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Olive Fitness",
                color = WatchAccent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            WatchDashboard(state)
        }
    }
}
// Shows the selected watch summary while keeping navigation usable on a round display.
@Composable
private fun WatchDashboard(state: WatchFitnessState) {
    var selectedPageName by rememberSaveable { mutableStateOf(WatchPage.HOME.name) }
    val selectedPage = WatchPage.entries.firstOrNull { it.name == selectedPageName } ?: WatchPage.HOME
    var workoutRunning by rememberSaveable { mutableStateOf(false) }
    var workoutStartMillis by rememberSaveable { mutableLongStateOf(0L) }
    var workoutStartSteps by rememberSaveable { mutableIntStateOf(state.displayedSteps) }
    var elapsedSeconds by rememberSaveable { mutableLongStateOf(0L) }

    // Reload phone and sensor summaries whenever the watch app becomes active.
    LaunchedEffect(workoutRunning, workoutStartMillis) {
        while (workoutRunning) {
            elapsedSeconds = ((System.currentTimeMillis() - workoutStartMillis) / 1_000L)
                .coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val workoutSteps = (state.displayedSteps - workoutStartSteps).coerceAtLeast(0)
    val hours = elapsedSeconds / 3_600L
    val minutes = (elapsedSeconds % 3_600L) / 60L
    val seconds = elapsedSeconds % 60L
    val timer = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    val stepProgress = (
        state.displayedSteps.toFloat() / state.stepGoal.coerceAtLeast(1).toFloat()
    ).coerceIn(0f, 1f)
    val totalCaloriesBurned = (state.displayedSteps * 0.04f).roundToInt()

    WatchTabs(selectedPage = selectedPage, onSelected = { selectedPageName = it.name })
    Spacer(Modifier.height(5.dp))

    ScrollableWatchPage(selectedPage.label) {
        if (selectedPage == WatchPage.HOME) {
            WatchSummaryCard(title = null) {
                CircularStepSummary(state, stepProgress, totalCaloriesBurned)
            }
        }

        // Shows detailed movement totals and outdoor-workout controls on the Move page.
        if (selectedPage == WatchPage.MOVE) {
            WatchSummaryCard(title = null) {
                CircularStepSummary(state, stepProgress, totalCaloriesBurned)
            }
            WatchSummaryCard(title = "Workout") {
            Text(
                timer,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = WatchText,
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TinyMetric(workoutSteps.toString(), "steps")
                TinyMetric(state.heartRate?.toString() ?: "--", "bpm")
                TinyMetric((workoutSteps * 0.04f).roundToInt().toString(), "kcal")
            }
            Button(
                onClick = {
                    // Starts a new timer or stops the outdoor workout already in progress.
                    if (workoutRunning) {
                        workoutRunning = false
                    } else {
                        workoutStartSteps = state.displayedSteps
                        workoutStartMillis = System.currentTimeMillis()
                        elapsedSeconds = 0L
                        workoutRunning = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (workoutRunning) WatchRed else WatchAccent,
                    contentColor = WatchSurface,
                ),
                shape = CircleShape,
            ) {
                Text(
                    if (workoutRunning) "Finish" else "Start",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            }
        }

        if (selectedPage == WatchPage.VITALS) {
            WatchVitalsSummary(state)
        }

        // Shows phone-synchronized calorie and macro progress on the Food page.
        if (selectedPage == WatchPage.FOOD) {
            WatchSummaryCard(title = "Nutrition") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "${state.calories.roundToInt()} kcal",
                    color = WatchText,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Goal ${state.calorieGoal}", color = WatchSubtle, fontSize = 11.sp)
            }
            MacroProgressRow("Protein", state.protein, state.proteinGoal)
            MacroProgressRow("Carbs", state.carbohydrates, state.carbohydrateGoal)
            MacroProgressRow("Fat", state.fat, state.fatGoal)
            Text(
                if (state.phoneConnected) "Synced" else "Not synced",
                color = WatchSubtle,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End),
            )
            }
        }
    }
}
