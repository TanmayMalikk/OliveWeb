package com.olive.app.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.covers.fitness.FitnessCover
import com.olive.app.ui.covers.fitness.FitnessStore
import com.olive.app.ui.covers.fitness.LabelNutrient
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FitnessCoverDiaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = "fitness-test-${UUID.randomUUID()}"
    private lateinit var session: SessionViewModel
    private lateinit var fitness: FitnessStore
    private fun label(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)

    private fun mount() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.grantRuntimePermission(instrument.targetContext.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        compose.runOnUiThread { session = SessionViewModel(compose.activity.application, localMode = true) }
        compose.waitUntil(10_000) { !session.busy }
        compose.runOnUiThread {
            session.runtime.enrollment = DeviceEnrollment(account, "")
            fitness = FitnessStore(compose.activity, account)
        }
        compose.setContent { FitnessCover(session) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.edit_fitness_goals)).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun clearFixture() {
        if (::session.isInitialized) session.viewModelScope.cancel()
        if (::fitness.isInitialized) fitness.preferences.edit().clear().commit()
    }

    @Test
    fun goalsPersistAndFitnessTabsNavigateFromTheCover() {
        mount()
        compose.onNodeWithText(label(R.string.edit_fitness_goals)).performClick()
        val search = compose.onNode(hasScrollToIndexAction())
        val steps = hasText(label(R.string.daily_step_goal)) and hasSetTextAction()
        search.performScrollToNode(steps)
        compose.onNode(steps).performTextReplacement("12345")
        Espresso.closeSoftKeyboard()
        search.performScrollToNode(hasTestTag("save_fitness_goals"))
        compose.onNodeWithTag("save_fitness_goals").performClick()
        assertEquals(12345, fitness.snapshot().profile.stepGoal)
        compose.onNodeWithContentDescription(label(R.string.activity)).performClick().assertIsSelected()
        compose.onNodeWithTag("fitness_activity").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.fitness_vitals)).performClick().assertIsSelected()
        compose.onNodeWithTag("fitness_vitals").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithContentDescription(label(R.string.home)).assertIsSelected()
    }

    @Test
    fun manualFoodCanBeLoggedEditedAndRemovedAndWaterChangesPersist() {
        mount()
        compose.onNodeWithContentDescription(label(R.string.nutrition)).performClick()
        compose.onNodeWithContentDescription(label(R.string.nutrition_water_progress, 0, 2000)).performClick()
        compose.onNodeWithContentDescription(label(R.string.nutrition_water_minus)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.fitness_diary_add_volume, 250)).performClick()
        compose.onNodeWithText(label(R.string.save)).performClick()
        compose.onNodeWithContentDescription(label(R.string.nutrition_water_progress, 250, 2000)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.nutrition_water_progress, 250, 2000)).performClick()
        compose.onNodeWithContentDescription(label(R.string.nutrition_water_minus)).performClick()
        compose.onNodeWithText(label(R.string.save)).performClick()
        assertEquals(0, fitness.snapshot().waterTodayMl)
        val lunch = hasTestTag("fitness_log_LUNCH")
        compose.onNodeWithTag("fitness_today").performScrollToNode(lunch)
        compose.onNode(lunch).performClick()
        compose.onNodeWithTag("nutrition_manual_entry").performClick()
        val proceed = compose.onNodeWithTag("manual_food_continue")
        proceed.assertIsNotEnabled()
        compose.onNodeWithTag("manual_food_name").performTextInput("Synthetic oat bar")
        compose.onNodeWithTag("manual_food_serving").performTextInput("1 bar (40 g)")
        LabelNutrient.entries.filter { it.required }.forEach { nutrient ->
            compose.onNodeWithTag("manual_${nutrient.name}").performTextInput(if (nutrient == LabelNutrient.CALORIES) "140" else "0")
        }
        Espresso.closeSoftKeyboard()
        proceed.assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.add_food)).performClick()
        val initial = fitness.snapshot().foodsToday.single()
        assertEquals(140f, initial.food.calories, 0f)
        assertEquals(com.olive.app.ui.covers.fitness.MealType.LUNCH, initial.meal)

        val diary = compose.onNodeWithTag("fitness_today")
        val edit = hasContentDescription(label(R.string.nutrition_edit_food))
        diary.performScrollToNode(edit)
        compose.onNode(edit).performClick()
        val servings = hasText(label(R.string.servings)) and hasSetTextAction()
        compose.onNode(servings).performTextReplacement("2")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText(label(R.string.save)).performClick()
        assertEquals(2f, fitness.snapshot().foodsToday.single().servings, 0f)
        val remove = hasContentDescription(label(R.string.remove))
        diary.performScrollToNode(remove)
        compose.onNode(remove).performClick()
        assertTrue(fitness.snapshot().foodsToday.isEmpty())
    }
}
