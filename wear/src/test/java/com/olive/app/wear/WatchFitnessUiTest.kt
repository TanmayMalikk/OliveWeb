package com.olive.app.wear

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class WatchFitnessUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allPagesRenderPopulatedAndEmptyStatesAndWorkoutTimerControls() {
        val state = mutableStateOf(
            WatchFitnessState(
                watchSteps = 4_000,
                phoneSteps = 3_000,
                stepGoal = 8_000,
                heartRate = 78,
                heartRateStatus = "Live",
                heartRateSummary = WatchHeartRateSummary(
                    peakBpm = 110,
                    lowestBpm = 58,
                    totalBpm = 225,
                    sampleCount = 3,
                    restingTotalBpm = 120,
                    restingSampleCount = 2,
                ),
                calories = 1_250f,
                calorieGoal = 2_000,
                protein = 75f,
                proteinGoal = 120,
                carbohydrates = 140f,
                carbohydrateGoal = 250,
                fat = 35f,
                fatGoal = 65,
                phoneConnected = true,
            )
        )

        compose.setContent { OliveWatchFitnessApp(state.value) }

        compose.onNodeWithText("Olive Fitness").assertIsDisplayed()
        compose.onNodeWithContentDescription("4000 steps of 8000 total steps").assertIsDisplayed()

        // Switch to Move and exercise workout start/finish + timer loop.
        compose.onNodeWithContentDescription("Move tab").performClick().assertIsSelected()
        compose.onNodeWithText("Workout").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("00:00:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_200L)
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onNodeWithText("Start").assertIsDisplayed()

        // Switch to Vitals and check populated summary (WatchVitalsSummary merges semantics into contentDescription).
        compose.onNodeWithContentDescription("Vitals tab").performClick().assertIsSelected()
        compose.onNodeWithContentDescription(
            "Current heart rate 78 beats per minute. Peak 110. Lowest 58. Resting 60. Average 75."
        ).performScrollTo().assertIsDisplayed()

        // Switch to Food and check synced nutrition goals.
        compose.onNodeWithContentDescription("Food tab").performClick().assertIsSelected()
        compose.onNodeWithText("Nutrition").assertIsDisplayed()
        compose.onNodeWithText("1250 kcal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Goal 2000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Protein").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synced").performScrollTo().assertIsDisplayed()

        // Switch to empty/disconnected state across Vitals, Food, and Home.
        compose.runOnIdle { state.value = WatchFitnessState() }
        compose.onNodeWithText("Not synced").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Vitals tab").performClick()
        compose.onNodeWithContentDescription(
            "Current heart rate -- beats per minute. Peak no data. Lowest no data. Resting --. Average --."
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Home tab").performClick().assertIsSelected()
    }

    @Test
    fun narrowScrollingTabsRotaryPageAndUntitledCardRenderCleanly() {
        compose.setContent {
            val density = LocalDensity.current
            val selected = remember { mutableStateOf(WatchPage.HOME) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(140.dp)) {
                        WatchTabs(selectedPage = selected.value, onSelected = { selected.value = it })
                    }
                    ScrollableWatchPage(pageTitle = "Custom") {
                        WatchSummaryCard(title = null) { Text("Untitled item") }
                        WatchSummaryCard(title = "Titled") { Text("Body") }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Food tab").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Custom fitness summary").performRotaryScrollInput {
            rotateToScrollVertically(40f)
        }
        compose.onNodeWithText("Titled").assertExists()
        compose.onNodeWithText("Untitled item").assertExists()
    }
}
