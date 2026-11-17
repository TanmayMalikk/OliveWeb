package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.ui.covers.fitness.FitnessProfile
import com.olive.app.ui.covers.fitness.FitnessSnapshot
import com.olive.app.ui.covers.fitness.FitnessVitalsPage
import com.olive.app.ui.covers.fitness.HeartRateSample
import com.olive.app.ui.covers.fitness.HeartRateVitals
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FitnessVitalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(resource: Int, vararg values: Any) = context.getString(resource, *values)

    @Test
    fun heartRateHistoryCanBeReadAndChangedUsingAccessibilityActions() {
        val today = LocalDate.now()
        fun readings(date: LocalDate, low: Int, peak: Int) = HeartRateVitals(
            date = date,
            peakBpm = peak,
            lowestBpm = low,
            restingBpm = low,
            averageBpm = (low + peak) / 2,
            sampleCount = 2,
            watchConnected = true,
            lastUpdatedAt = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            samples = listOf(
                HeartRateSample(date.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), low),
                HeartRateSample(date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), peak),
            ),
        )
        val snapshot = FitnessSnapshot(FitnessProfile(), 500, emptyList(),
            heartRateHistory = listOf(readings(today.minusDays(2), 58, 96), readings(today, 67, 108)),
            watchConnected = true)
        compose.setContent { OliveTheme { FitnessVitalsPage(snapshot) } }
        val graph = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and
            hasContentDescription(text(R.string.fitness_vitals_range, 67, 108), substring = true))
        graph.assertIsDisplayed()
        fun selectDay(label: Int) {
            val target = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            val action = target.fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .single { it.label == text(label) }
            compose.runOnIdle { assertTrue(action.action()) }
        }
        selectDay(R.string.fitness_previous_day)
        compose.onAllNodesWithContentDescription(text(R.string.fitness_vitals_no_data), substring = true)
            .onFirst().assertExists()
        selectDay(R.string.fitness_previous_day)
        compose.onAllNodesWithContentDescription(text(R.string.fitness_vitals_range, 58, 96), substring = true)
            .onFirst().assertExists()
        selectDay(R.string.fitness_next_day)
        compose.onAllNodesWithContentDescription(text(R.string.fitness_vitals_no_data), substring = true)
            .onFirst().assertExists()
    }

    @Test
    fun unpairedPhoneExplainsHowToConnectAWatchWithoutShowingFakeVitals() {
        compose.setContent { OliveTheme { FitnessVitalsPage(FitnessSnapshot(FitnessProfile(), 0, emptyList())) } }
        val hint = text(R.string.fitness_vitals_watch_required)
        compose.onNodeWithTag("fitness_vitals").performScrollToNode(hasText(hint))
        compose.onNodeWithText(hint).assertIsDisplayed()
    }
}
