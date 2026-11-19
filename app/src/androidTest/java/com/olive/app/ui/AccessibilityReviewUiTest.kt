package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.ui.covers.fitness.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.auth.SystemPinPrompt
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.safetyplan.SafetyChecklistProgress
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class AccessibilityReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun yearsAreSpokenAsWholeNumbersRatherThanSeparateDigits() {
        compose.setContent { OliveTheme { BirthdayPicker("", "birthday", true, false) {} } }
        compose.onNodeWithTag("birthday_open").performClick()
        val range = birthdayYears(LocalDate.now()).first { 2017 in it }
        compose.onNodeWithTag("birthday_range_${range.last}").performScrollTo().performClick()
        compose.onNodeWithTag("birthday_year_2017").assertContentDescriptionEquals("2017")
        compose.onNodeWithContentDescription("2 0 1 7").assertDoesNotExist()
    }

    @Test
    fun checklistCountAndProgressShareOneAnnouncement() {
        compose.setContent { OliveTheme { SafetyChecklistProgress(4, 10, summary = true) } }
        compose.onNodeWithTag("safety_checklist_progress")
            .assertContentDescriptionEquals(context.getString(R.string.a11y_safety_progress, 4, 10))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "40%"))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
    }

    @Test
    fun waterAmountBetweenTheTwoButtonsIsDecorative() {
        compose.setContent { OliveTheme { WaterTracker(500, 2000, true) { _, _ -> } } }
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_minus)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_progress, 500, 2000)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_minus)).assertHasClickAction()
        compose.onNodeWithContentDescription(context.getString(R.string.fitness_diary_add_volume, 250)).assertHasClickAction()
        compose.onNodeWithText("250 ml").assertDoesNotExist()
    }

    @Test
    fun returnAnnouncementLeavesNoInvisibleFocusTarget() {
        compose.setContent { OliveTheme { AccessibleMessage("Returned to Fitness Tracker", showText = false) } }
        compose.onNodeWithContentDescription("Returned to Fitness Tracker").assertDoesNotExist()
    }

    @Test
    fun foodLibraryStartsOnAVisibleSearchControl() {
        val store = FitnessStore(context, "a11y-food-${java.util.UUID.randomUUID()}")
        val source = object : FoodNutritionDataSource {
            override suspend fun suggestions(query: String) = emptyList<String>()
            override suspend fun search(query: String, page: Int) = FoodSearchResult()
            override suspend fun lookupBarcode(barcode: String) = FoodSearchResult()
        }
        compose.setContent { OliveTheme {
            FoodSearchPage(true, PaddingValues(0.dp), { _, _, _ -> }, source, FitnessFoodLibrary(store.preferences))
        } }
        compose.onNodeWithTag("food_library_Search").assertIsDisplayed().assertIsFocused().assertHasClickAction()
    }

    @Test
    fun customPinOffersAPasswordFieldForTheSystemNumericKeyboard() {
        lateinit var session: SessionViewModel
        compose.runOnUiThread { session = ViewModelProvider(compose.activity)[SessionViewModel::class.java] }
        check(session.localMode)
        compose.waitUntil(15_000) { !session.busy }
        compose.runOnIdle { session.runtime.state.screen = SessionScreen.Pin }
        compose.setContent { OliveTheme { SystemPinPrompt(session, session.pinChallengeVersion) } }
        val field = compose.onNodeWithTag("unlock_pin")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("unlock_submit").assertIsNotEnabled()
        field.performTextReplacement("0012")
        compose.onNodeWithTag("unlock_submit").assertIsEnabled()
        compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
    }
    @Test
    fun loadedConversationStartsOnItsPersistentHeader() {
        lateinit var session: SessionViewModel
        compose.runOnUiThread { session = ViewModelProvider(compose.activity)[SessionViewModel::class.java] }
        check(session.localMode)
        compose.waitUntil(15_000) { !session.busy }
        compose.runOnIdle {
            session.runtime.state.activeConversation = com.olive.app.data.messaging.ChatConversation(
                "a11y-conversation", "pair", listOf("owner", "friend"), emptyMap())
        }
        compose.setContent { OliveTheme {
            com.olive.app.ui.users.shared.messaging.ChatPane(session, "owner", true, null)
        } }
        compose.onNodeWithTag("open_chat_info").assertIsDisplayed().assertIsFocused()
    }
}
