package com.olive.app.ui.covers.fitness

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.olive.app.R
import com.olive.app.ui.shared.OliveTheme
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class FitnessVitalsAndChartsDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun fitnessVitalsPageRendersPopulatedAndEmptyStatesAndHandlesSelection() {
        val today = LocalDate.now()
        val sampleHistory = (0..6).map { offset ->
            HeartRateVitals(
                date = today.minusDays((6 - offset).toLong()),
                peakBpm = 148 + offset * 2,
                lowestBpm = 56 + offset,
                restingBpm = if (offset % 2 == 0) 60 + offset else null,
                averageBpm = 84 + offset,
                sampleCount = 42,
                watchConnected = offset == 6,
                lastUpdatedAt = System.currentTimeMillis(),
                samples = listOf(HeartRateSample(System.currentTimeMillis() - 1000L, 72)),
            )
        }
        var snapshot by mutableStateOf(
            FitnessSnapshot(
                profile = FitnessProfile(heightCm = 172f, stepGoal = 8000),
                stepsToday = 4200,
                foodsToday = emptyList(),
                heartRateVitals = sampleHistory.last(),
                heartRateHistory = sampleHistory,
                watchConnected = true,
                watchSeenWithinFourteenDays = true,
                waterTodayMl = 1250,
            ),
        )

        compose.setContent {
            OliveTheme {
                FitnessVitalsPage(snapshot)
            }
        }
        compose.waitForIdle()

        // Tap canvas and trigger custom previous/next day accessibility actions
        val prevLabel = context.getString(R.string.fitness_previous_day)
        val graphMatcher = SemanticsMatcher("has custom previous day action") { node ->
            SemanticsActions.CustomActions in node.config &&
                node.config[SemanticsActions.CustomActions].any { it.label == prevLabel }
        }
        compose.onNode(graphMatcher).performTouchInput { click(Offset(centerX * 0.5f, centerY)) }
        compose.waitForIdle()
        val customActions = compose.onNode(graphMatcher).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            customActions.first { it.label == prevLabel }.action()
        }
        compose.waitForIdle()

        // Switch to empty/watch-required message branches
        snapshot = snapshot.copy(heartRateHistory = emptyList(), heartRateVitals = null, watchConnected = true)
        compose.waitForIdle()
        snapshot = snapshot.copy(watchConnected = false, watchSeenWithinFourteenDays = true)
        compose.waitForIdle()
        snapshot = snapshot.copy(watchSeenWithinFourteenDays = false)
        compose.waitForIdle()
    }

    @Test
    fun fitnessHistoryChartsDashboardAndWaterTrackerWorkCleanly() {
        compose.mainClock.autoAdvance = false
        val end = LocalDate.now()
        val days = FitnessHistory.datesEndingOn(end, 14).mapIndexed { idx, date ->
            FitnessHistoryDay(
                date = date,
                steps = if (idx % 3 == 0) 0 else 4000 + idx * 350,
                distanceKm = 2.5f + idx * 0.3f,
                caloriesBurned = 220f + idx * 15f,
                caloriesEaten = 1800f + idx * 40f,
                proteinGrams = 90f + idx * 2f,
                carbohydrateGrams = 210f + idx * 4f,
                fatGrams = 55f + idx * 1.5f,
            )
        }
        val food = NutritionFood(
            id = "f-1",
            name = "Oats",
            brand = "Quaker",
            servingDescription = "1 cup",
            calories = 300f,
            proteinGrams = 10f,
            carbohydrateGrams = 54f,
            fatGrams = 5f,
            source = FoodDataSource.MANUAL,
        )
        val snapshot = FitnessSnapshot(
            profile = FitnessProfile(heightCm = 170f, stepGoal = 10000),
            stepsToday = 6400,
            foodsToday = listOf(FoodLogEntry("log-1", food, 1.5f, MealType.BREAKFAST)),
            waterTodayMl = 1000,
        )
        var addedWater by mutableIntStateOf(0)
        var updatedGoal by mutableIntStateOf(0)

        compose.setContent {
            OliveTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WaterTracker(
                        totalMl = 1000,
                        goalMl = 2500,
                        enabled = true,
                        onAdd = { amt, goal -> addedWater = amt; updatedGoal = goal },
                    )
                    FitnessActivityDashboard(snapshot)
                    FitnessHistoryCharts(days)
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)

        // Open water sheet (rememberInfiniteTransition inside WaterGlass -> keep autoAdvance = false)
        val waterDesc = context.getString(R.string.nutrition_water_progress, 1000, 2500)
        compose.onNodeWithContentDescription(waterDesc).performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription(context.getString(R.string.fitness_diary_add_volume, 250)).performClick()
        compose.onNodeWithText(context.getString(R.string.fitness_diary_custom_water)).performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText(context.getString(R.string.nutrition_water_amount)).performTextReplacement("150")
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_add_water)).performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.mainClock.advanceTimeBy(200)
        assertTrue(addedWater == 400 && updatedGoal == 2500)

        // Launch FitnessHealthPrivacyActivity directly via Robolectric
        val activity = Robolectric.buildActivity(FitnessHealthPrivacyActivity::class.java, Intent(context, FitnessHealthPrivacyActivity::class.java))
            .setup()
            .get()
        assertTrue(!activity.isFinishing)
    }
}
