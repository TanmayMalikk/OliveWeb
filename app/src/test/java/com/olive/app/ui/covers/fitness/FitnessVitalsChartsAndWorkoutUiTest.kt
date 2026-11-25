package com.olive.app.ui.covers.fitness

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.support.OliveUiTestHost
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FitnessVitalsChartsAndWorkoutUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun homePageVitalsAndHistoryChartsRenderPopulatedAndEmptyStates() {
        val today = LocalDate.now()
        val vitals = (0..4).map { offset ->
            HeartRateVitals(
                date = today.minusDays(offset.toLong()),
                peakBpm = 148 - offset * 4,
                lowestBpm = 56 + offset,
                restingBpm = 60,
                averageBpm = 76,
                sampleCount = 6,
                watchConnected = offset == 0,
                lastUpdatedAt = System.currentTimeMillis(),
                samples = listOf(
                    HeartRateSample(System.currentTimeMillis() - 60_000L, 64),
                    HeartRateSample(System.currentTimeMillis(), 112),
                ),
            )
        }
        val historyDays = (13 downTo 0).map { offset ->
            FitnessHistoryDay(
                date = today.minusDays(offset.toLong()),
                steps = 4000 + offset * 350,
                distanceKm = 3.1f + offset * 0.25f,
                caloriesBurned = 220f + offset * 15f,
                caloriesEaten = 1850f + offset * 30f,
                proteinGrams = 95f + offset,
                carbohydrateGrams = 210f,
                fatGrams = 60f,
            )
        }
        val snapshot = FitnessSnapshot(
            profile = FitnessProfile(),
            stepsToday = 6400,
            foodsToday = emptyList(),
            heartRateVitals = vitals.first(),
            heartRateHistory = vitals,
            watchConnected = true,
            watchSeenWithinFourteenDays = true,
            waterTodayMl = 1200,
        )
        var promptActions = 0
        var viewStage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                when (viewStage) {
                    0 -> FitnessHomePage(
                        snapshot = snapshot,
                        onGoals = { promptActions++ },
                        showGoalQuestionnairePrompt = true,
                        onStartQuestionnaire = { promptActions++ },
                        onDismissQuestionnaire = { promptActions++ },
                    )
                    1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        FitnessHistoryCharts(days = historyDays)
                    }
                    2 -> FitnessVitalsPage(snapshot = snapshot)
                    else -> FitnessVitalsPage(snapshot = snapshot.copy(heartRateVitals = null, heartRateHistory = emptyList()))
                }
            }
        }
        compose.onNodeWithTag("fitness_home").assertExists()
        assertTrue(promptActions == 0)

        viewStage = 1
        compose.waitForIdle()
        compose.onNodeWithText(appContext().getString(R.string.fitness_history_title)).assertIsDisplayed()

        viewStage = 2
        compose.waitForIdle()
        compose.onNodeWithTag("fitness_vitals").assertIsDisplayed()

        viewStage = 3
        compose.waitForIdle()
        compose.onNodeWithTag("fitness_vitals").assertIsDisplayed()
    }

    @Test
    fun outdoorWorkoutCardRendersSelectorsAndPollsWithManualClock() {
        compose.mainClock.autoAdvance = false
        val context = appContext()
        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FitnessOutdoorWorkoutCard(accountId = "workout-ui-test", enabled = true)
            }
        }
        compose.mainClock.advanceTimeBy(150)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.fitness_running)).performClick()
        compose.onNodeWithText(context.getString(R.string.fitness_cycling)).performClick()
        compose.onNodeWithText(context.getString(R.string.fitness_hiking)).performClick()
        compose.onNodeWithText(context.getString(R.string.fitness_start_outdoor_workout)).assertIsDisplayed()
    }
}
