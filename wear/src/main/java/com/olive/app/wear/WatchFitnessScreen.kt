// Watch fitness users: shows short, readable dashboards for activity and nutrition.
package com.olive.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val WatchBackground = Color(0xFFFAF9F5)
private val WatchSurface = Color.White
private val WatchAccent = Color(0xFF45613C)
private val WatchText = Color(0xFF253322)
private val WatchSubtle = Color(0xFF677A3B)
private val WatchPaleOlive = Color(0xFFE7EDDD)
private val WatchLightSage = Color(0xFFD8E3CF)
private val WatchRed = Color(0xFF9A4D45)

private enum class WatchPage(val label: String) {
    HOME("Home"),
    MOVE("Move"),
    VITALS("Vitals"),
    FOOD("Food"),
}

// Builds the light olive fitness theme used throughout the watch app.
@Composable
internal fun OliveWatchFitnessApp(state: WatchFitnessState) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = WatchAccent,
            onPrimary = WatchSurface,
            background = WatchBackground,
            onBackground = WatchText,
            surface = WatchSurface,
            onSurface = WatchText,
            surfaceVariant = WatchPaleOlive,
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

    // Update watch dashboard when information used by this screen changes.
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

    ScrollableWatchPage {
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

// Watch vitals: shows today's live, peak, lowest, resting, and average heart rates.
// Shows today's heart-rate range and explains when the watch has no usable reading.
@Composable
private fun WatchVitalsSummary(state: WatchFitnessState) {
    val summary = state.heartRateSummary
    val current = state.heartRate?.toString() ?: "--"
    val average = summary.averageBpm?.toString() ?: "--"
    val resting = summary.restingBpm?.toString() ?: "--"
    val description = buildString {
        append("Current heart rate $current beats per minute. ")
        append("Peak ${summary.peakBpm.takeIf { it > 0 } ?: "no data"}. ")
        append("Lowest ${summary.lowestBpm.takeIf { it > 0 } ?: "no data"}. ")
        append("Resting $resting. Average $average.")
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        WatchSummaryCard(title = "Heart rate") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    current,
                    color = WatchAccent,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("bpm now", color = WatchSubtle, fontSize = 11.sp)
                Text(state.heartRateStatus, color = WatchSubtle, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TinyMetric(summary.peakBpm.takeIf { it > 0 }?.toString() ?: "--", "peak")
                TinyMetric(summary.lowestBpm.takeIf { it > 0 }?.toString() ?: "--", "lowest")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TinyMetric(resting, "resting")
                TinyMetric(average, "average")
            }
        }
    }
}

// Draws today's steps inside one readable circular progress indicator.
@Composable
private fun CircularStepSummary(
    state: WatchFitnessState,
    stepProgress: Float,
    totalCaloriesBurned: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(100.dp).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(stepProgress, 0f..1f)
                contentDescription =
                    "${state.displayedSteps} steps of ${state.stepGoal} total steps"
            },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { stepProgress },
                modifier = Modifier.fillMaxSize(),
                color = WatchAccent,
                trackColor = WatchLightSage,
                strokeWidth = 9.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.displayedSteps.toString(),
                    color = WatchText,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("steps", color = WatchSubtle, fontSize = 11.sp)
            }
        }
        Text(
            "${state.stepGoal} total steps",
            color = WatchSubtle,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TinyMetric(state.heartRate?.toString() ?: "--", "bpm")
            TinyMetric(totalCaloriesBurned.toString(), "kcal burned")
        }
    }
}

// Lets watch users switch between Home, Move, Vitals, and Food summaries.
@Composable
private fun WatchTabs(
    selectedPage: WatchPage,
    onSelected: (WatchPage) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(CircleShape).background(WatchPaleOlive)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WatchPage.entries.forEach { page ->
            val selected = page == selectedPage
            Button(
                onClick = { onSelected(page) },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) WatchAccent else Color.Transparent,
                    contentColor = if (selected) WatchSurface else WatchAccent,
                ),
                contentPadding = PaddingValues(0.dp),
                shape = CircleShape,
            ) {
                Text(page.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Gives longer watch pages vertical scrolling without introducing horizontal movement.
@Composable
private fun ScrollableWatchPage(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize().clipToBounds()
            .onRotaryScrollEvent {
                scope.launch { scrollState.scrollBy(it.verticalScrollPixels) }
                true
            }
            .focusRequester(focusRequester).focusable().verticalScroll(scrollState)
            .padding(top = 6.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// Places one related group of watch metrics inside a consistent summary surface.
@Composable
private fun WatchSummaryCard(
    title: String?,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(WatchSurface)
            .border(1.dp, WatchLightSage, RoundedCornerShape(22.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(title, color = WatchText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

// Shows one macro's eaten amount, goal, and progress in the watch nutrition summary.
@Composable
private fun MacroProgressRow(label: String, value: Float, goal: Int) {
    val progress = (value / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label, ${value.roundToInt()} of $goal grams"
        },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = WatchText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${value.roundToInt()} / $goal g",
                color = WatchSubtle,
                fontSize = 11.sp,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(WatchLightSage)
        ) {
            Box(
                Modifier.fillMaxWidth(progress).height(6.dp).background(WatchAccent)
            )
        }
    }
}

// Displays one compact value and label without crowding the round watch screen.
@Composable
private fun TinyMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = WatchText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = WatchSubtle, fontSize = 11.sp)
    }
}
