package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.ui.covers.fitness.*
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NutritionSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val food = NutritionFood("first", "Synthetic oats", "Fixture foods", "100 g",
        380f, 13f, 68f, 7f, FoodDataSource.FATSECRET)

    @Test
    fun autocompleteSearchPaginatesWithoutDuplicatingResultsAndAddsTheChosenFood() {
        val pages = mutableListOf<Int>()
        val source = object : FoodNutritionDataSource {
            override suspend fun suggestions(query: String) = listOf("Oats autocomplete")
            override suspend fun lookupBarcode(barcode: String) = FoodSearchResult()
            override suspend fun search(query: String, page: Int): FoodSearchResult {
                assertEquals("Oats autocomplete", query)
                pages += page
                return if (page == 0) FoodSearchResult(listOf(food), hasMore = true)
                else FoodSearchResult(listOf(food, food.copy(id = "second", name = "Synthetic porridge")), providerFailures = 1)
            }
        }
        var selected: NutritionFood? = null
        compose.setContent { OliveTheme {
            FoodSearchPage(true, PaddingValues(0.dp), { choice, _, _ -> selected = choice }, source)
        } }
        compose.onNodeWithTag("fitness_food_search").performTextInput("oat")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Oats autocomplete").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Oats autocomplete").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.nutrition_more)))
        compose.onNodeWithText(text(R.string.nutrition_more)).performClick()
        compose.onNodeWithText(text(R.string.some_food_sources_unavailable)).assertExists()
        val oatmeal = hasContentDescription("Synthetic oats", substring = true)
        val porridge = hasContentDescription("Synthetic porridge", substring = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(oatmeal)
        assertEquals(1, compose.onAllNodes(oatmeal).fetchSemanticsNodes().size)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(porridge)
        compose.onNode(porridge).performClick()
        compose.onNodeWithText(text(R.string.add_food)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(0, 1), pages)
            assertEquals("second", selected?.id)
        }
    }

    @Test
    fun barcodeEntryIgnoresNonDigitsAndDisplaysProviderApprovalFailure() {
        var queried = ""
        val source = object : FoodNutritionDataSource {
            override suspend fun suggestions(query: String) = emptyList<String>()
            override suspend fun search(query: String, page: Int) = FoodSearchResult()
            override suspend fun lookupBarcode(barcode: String): FoodSearchResult {
                queried = barcode
                return FoodSearchResult(providerFailures = 2, needsIpApproval = true)
            }
        }
        compose.setContent { OliveTheme {
            FoodSearchPage(true, PaddingValues(0.dp), { _, _, _ -> error("Must not add food") }, source)
        } }
        compose.onNodeWithText(text(R.string.nutrition_barcode)).performClick()
        val search = compose.onNodeWithContentDescription(text(R.string.search))
        search.assertIsNotEnabled()
        compose.onNodeWithTag("fitness_food_search").performTextInput("a12345678b")
        search.assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.food_search_unavailable)).assertExists()
        compose.onNodeWithText(text(R.string.nutrition_ip_approval)).assertExists()
        compose.runOnIdle { assertEquals("12345678", queried) }
    }
}
