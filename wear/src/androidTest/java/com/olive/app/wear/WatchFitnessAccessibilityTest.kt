// Watch fitness accessibility tests: verifies readable labels without requiring live sensors.
package com.olive.app.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test

class WatchFitnessAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun keepWatchDisplayAwake() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Shows a representative step total and checks that TalkBack hears both value and goal together.
    @Test
    fun stepProgressHasOneCompleteSpokenDescription() {
        composeRule.setContent {
            OliveWatchFitnessApp(WatchFitnessState(watchSteps = 4_000, stepGoal = 8_000))
        }

        composeRule.onNodeWithContentDescription("4000 steps of 8000 total steps")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("4000 steps of 8000 total steps")
    }

    // Opens the screen with no sensors and checks that every main destination remains discoverable.
    @Test
    fun everyWatchPageHasAReadableNavigationLabel() {
        composeRule.setContent { OliveWatchFitnessApp(WatchFitnessState()) }

        listOf("Home", "Move", "Vitals", "Food").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    // Selects Vitals and checks that TalkBack hears the selected tab and the new page summary.
    @Test
    fun changingWatchPageUpdatesSelectionAndMovesAccessibilityFocus() {
        composeRule.setContent { OliveWatchFitnessApp(WatchFitnessState()) }

        composeRule.onNodeWithText("Vitals").performClick().assertIsSelected()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Vitals fitness summary").assertIsFocused()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.getExternalFilesDir(null), "watch-tabs-test.png")
        file.outputStream().use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test
    fun tabsRemainReachableOnNarrowScreensWithLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            val selected = remember { mutableStateOf(WatchPage.HOME) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                androidx.wear.compose.material3.MaterialTheme {
                    Box(Modifier.width(170.dp)) {
                        WatchTabs(selected.value) { selected.value = it }
                    }
                }
            }
        }
        for (label in listOf("Food", "Home", "Move", "Vitals")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(label).assertIsDisplayed().assertIsSelected()
        }
    }

    @Test
    fun foodShowsSyncedCaloriesAndMacrosOnTheWatch() {
        composeRule.setContent {
            OliveWatchFitnessApp(WatchFitnessState(calories = 600f, protein = 32f,
                carbohydrates = 80f, fat = 20f, phoneConnected = true))
        }
        composeRule.onNodeWithText("Food").assertIsDisplayed().performClick().assertIsSelected()
        composeRule.onNodeWithText("600 kcal").assertIsDisplayed()
        composeRule.onNodeWithText("Goal 2000").assertIsDisplayed()
        composeRule.onNodeWithText("Protein").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Synced").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun movementWorkoutStartsAndFinishesWithoutLosingItsStepTotal() {
        val snapshot = mutableStateOf(WatchFitnessState(watchSteps = 100, heartRate = 78))
        composeRule.setContent { OliveWatchFitnessApp(snapshot.value) }
        composeRule.onNodeWithText("Move").assertIsDisplayed().performClick().assertIsSelected()
        val summary = composeRule.onNodeWithContentDescription("Move fitness summary")
        summary.performTouchInput { swipeUp() }
        composeRule.onNodeWithText("Start").performScrollTo().performClick()
        composeRule.runOnIdle { snapshot.value = snapshot.value.copy(watchSteps = 125) }
        composeRule.onNodeWithText("25").assertExists()
        composeRule.onNodeWithText("Finish").performScrollTo().performClick()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("25").assertExists()
    }
}
