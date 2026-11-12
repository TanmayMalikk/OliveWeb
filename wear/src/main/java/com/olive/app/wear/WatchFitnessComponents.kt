// Watch fitness users: provides the cards, tabs, and progress displays used by the watch screen.
package com.olive.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Shows today's heart-rate range and explains when the watch has no usable reading.
@Composable
internal fun WatchVitalsSummary(state: WatchFitnessState) {
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
                Text(current, color = WatchAccent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
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
internal fun CircularStepSummary(
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
                contentDescription = "${state.displayedSteps} steps of ${state.stepGoal} total steps"
            },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { stepProgress },
                modifier = Modifier.fillMaxSize(),
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = WatchAccent,
                    trackColor = WatchLightSage,
                ),
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
internal fun WatchTabs(selectedPage: WatchPage, onSelected: (WatchPage) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(CircleShape).background(WatchPaleOlive).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WatchPage.entries.forEach { page ->
            val selected = page == selectedPage
            Button(
                onClick = { onSelected(page) },
                modifier = Modifier.weight(1f).height(40.dp).semantics {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = "${page.label} tab"
                },
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

// Lets the crown scroll long pages while preventing sideways movement.
@Composable
internal fun ScrollableWatchPage(pageTitle: String, content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pageTitle) {
        scrollState.scrollTo(0)
        focusRequester.requestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize().clipToBounds()
            .onRotaryScrollEvent {
                scope.launch { scrollState.scrollBy(it.verticalScrollPixels) }
                true
            }
            .focusRequester(focusRequester).focusable().verticalScroll(scrollState)
            .semantics {
                paneTitle = pageTitle
                contentDescription = "$pageTitle fitness summary"
            }
            .padding(top = 6.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// Places related watch values inside one card so they are easy to scan.
@Composable
internal fun WatchSummaryCard(
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
            Text(
                title,
                color = WatchText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

// Shows one nutrition goal and how close the user is to reaching it.
@Composable
internal fun MacroProgressRow(label: String, value: Float, goal: Int) {
    val progress = (value / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label, ${value.roundToInt()} of $goal grams"
        },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = WatchText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("${value.roundToInt()} / $goal g", color = WatchSubtle, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(WatchLightSage)) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(WatchAccent))
        }
    }
}

// Displays one small value and label without crowding the round screen.
@Composable
internal fun TinyMetric(value: String, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label, $value"
        },
    ) {
        Text(value, color = WatchText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = WatchSubtle, fontSize = 11.sp)
    }
}
