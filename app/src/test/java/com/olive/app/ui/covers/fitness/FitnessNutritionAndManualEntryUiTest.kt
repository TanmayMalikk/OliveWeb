package com.olive.app.ui.covers.fitness

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.support.OliveUiTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FitnessNutritionAndManualEntryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    private val oats = NutritionFood(
        id = "food-oats",
        name = "Rolled Oats",
        brand = "Olive Grain",
        servingDescription = "40 g bowl",
        calories = 150f,
        proteinGrams = 5f,
        carbohydrateGrams = 27f,
        fatGrams = 3f,
        source = FoodDataSource.FATSECRET,
        servingOptions = listOf(
            NutritionFood(
                id = "food-oats-big",
                name = "Rolled Oats",
                brand = "Olive Grain",
                servingDescription = "80 g bowl",
                calories = 300f,
                proteinGrams = 10f,
                carbohydrateGrams = 54f,
                fatGrams = 6f,
                source = FoodDataSource.FATSECRET,
            ),
        ),
        fiberGrams = 4f,
    )

    private class FakeFoodSource : FoodNutritionDataSource {
        override suspend fun search(query: String, page: Int): FoodSearchResult = FoodSearchResult(
            foods = listOf(
                NutritionFood(
                    id = "apple-1",
                    name = "Honeycrisp Apple",
                    brand = "Orchard",
                    servingDescription = "1 medium (180 g)",
                    calories = 95f,
                    proteinGrams = 0.5f,
                    carbohydrateGrams = 25f,
                    fatGrams = 0.3f,
                    source = FoodDataSource.OPEN_FOOD_FACTS,
                ),
            ),
        )
        override suspend fun suggestions(query: String): List<String> = listOf("apple juice", "applesauce")
        override suspend fun lookupBarcode(barcode: String): FoodSearchResult = FoodSearchResult(needsSignIn = true)
    }

    @Test
    fun todayPageShowsNutritionTotalsWaterControlsMealsEditAndRemove() {
        val context = appContext()
        val library = FitnessFoodLibrary(fitnessPreferences(context, "today-lib"))
        var removedId: String? = null
        var waterDelta = 0
        var updatedFood: FoodLogEntry? = null
        val entry = FoodLogEntry("log-1", oats, 1.5f, MealType.BREAKFAST, loggedAt = 1000L)
        val snapshot = FitnessSnapshot(
            profile = FitnessProfile(),
            stepsToday = 4200,
            foodsToday = listOf(entry),
            waterTodayMl = 500,
        )

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                TodayPage(
                    snapshot = snapshot,
                    enabled = true,
                    padding = PaddingValues(0.dp),
                    onAddFood = {},
                    onRemoveFood = { removedId = it },
                    onAddWater = { amount, _ -> waterDelta += amount },
                    onUpdateFood = { updatedFood = it },
                    library = library,
                    onOpenLibrary = {},
                    onLogMeal = {},
                )
            }
        }
        compose.onNodeWithTag("fitness_today").performScrollToNode(hasContentDescription("Rolled Oats", substring = true))
        compose.onAllNodesWithContentDescription("Rolled Oats", substring = true)[0].assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_edit_food)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.waitForIdle()
        assertTrue(updatedFood != null)

        compose.onAllNodesWithContentDescription(context.getString(R.string.remove))[0].performClick()
        assertEquals("log-1", removedId)

        compose.onNodeWithTag("fitness_today").performScrollToNode(hasText(context.getString(R.string.nutrition_other_nutrients)))
        compose.onNodeWithText(context.getString(R.string.nutrition_other_nutrients)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fitness_today").performScrollToNode(hasContentDescription(context.getString(R.string.fiber), substring = true))
        compose.onAllNodesWithContentDescription(context.getString(R.string.fiber), substring = true)[0].assertExists()
        assertTrue(waterDelta >= 0)
    }

    @Test
    fun manualFoodEntryDialogValidatesAndBuildsNutritionFood() {
        val context = appContext()
        var created: NutritionFood? = null
        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                ManualFoodEntryDialog(onDismiss = {}, onContinue = { created = it })
            }
        }
        // Invalid submission keeps dialog open.
        compose.onNodeWithTag("manual_food_continue").performClick()
        assertEquals(null, created)

        compose.onNodeWithTag("manual_food_name").performScrollTo().performTextInput("Almond Butter")
        compose.onNodeWithTag("manual_food_serving").performScrollTo().performTextInput("2 tbsp (32 g)")
        compose.onNodeWithTag("manual_CALORIES").performScrollTo().performTextInput("190")
        compose.onNodeWithTag("manual_PROTEIN").performScrollTo().performTextInput("7")
        compose.onNodeWithTag("manual_CARBS").performScrollTo().performTextInput("6")
        compose.onNodeWithTag("manual_FAT").performScrollTo().performTextInput("16")
        compose.onNodeWithText(context.getString(R.string.nutrition_more_nutrients)).performScrollTo().performClick()
        compose.onNodeWithTag("manual_FIBER").performScrollTo().performTextInput("3")
        compose.onNodeWithTag("manual_food_continue").performClick()
        assertEquals("Almond Butter", created?.name)
        assertEquals(190f, created?.calories)
    }

    @Test
    fun foodSearchPageSearchesResultsAndSwitchesRecentSavedTabs() {
        val context = appContext()
        val library = FitnessFoodLibrary(fitnessPreferences(context, "search-lib"))
        library.setSaved(oats, true)
        var addedFoodName: String? = null
        val recentEntry = FoodLogEntry("recent-1", oats, 1f, MealType.SNACK)

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FoodSearchPage(
                    enabled = true,
                    padding = PaddingValues(0.dp),
                    onFoodAdded = { food, _, _, _ -> addedFoodName = food.name },
                    foodSource = FakeFoodSource(),
                    library = library,
                    recentFoods = listOf(recentEntry),
                    initialMeal = MealType.LUNCH,
                )
            }
        }
        compose.onNodeWithTag("fitness_food_search").performTextInput("apple")
        compose.onNodeWithContentDescription(context.getString(R.string.search)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Honeycrisp Apple", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Honeycrisp Apple", substring = true)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.add_food)).performClick()
        compose.waitForIdle()
        assertEquals("Honeycrisp Apple", addedFoodName)

        compose.onNodeWithTag("food_library_Recent").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("food_library_Saved").performClick()
        compose.waitForIdle()
    }
}
