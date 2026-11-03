// Watch fitness accessibility tests: verifies readable labels without requiring live sensors.
package com.olive.app.wear

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class WatchFitnessAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

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
}
