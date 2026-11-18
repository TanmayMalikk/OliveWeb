package com.olive.app.ui.covers.fitness

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FoodNutritionRepositoryTest {
    @Test fun localEntryAndShortQueriesDoNotInitializeFirebase() = kotlinx.coroutines.runBlocking {
        var initialized = false
        val repository = FoodNutritionRepository(functions = {
            initialized = true
            error("No Firebase configuration")
        })
        assertFalse(initialized)
        assertEquals(FoodSearchResult(), repository.search(" "))
        assertEquals(FoodSearchResult(), repository.search("a"))
        assertFalse(initialized)
        // Remote failures remain a retryable result, rather than crashing the screen.
        assertEquals(2, repository.lookupBarcode("12345678").providerFailures)
        assertTrue(initialized)
    }

    @Test fun servingSelectionRetainsIndependentNutrientsAndScalesDiaryTotals() {
        val food = parseFatSecretFood(mapOf(
            "id" to "42", "name" to "Oats", "calories" to 400, "servingDescription" to "100 g",
            "servings" to listOf(mapOf("servingId" to "cup", "servingDescription" to "1 cup",
                "calories" to 150, "proteinGrams" to 4, "fiberGrams" to 3,
                "sodiumMilligrams" to 12)),
        ))!!
        assertEquals(400f, food.calories, 0f)
        val cup = food.servingOptions.single()
        assertEquals("fatsecret:42:cup", cup.id)
        assertEquals("1 cup", cup.servingDescription)
        assertEquals(150f, cup.calories, 0f)
        assertTrue(cup.servingOptions.isEmpty())
        val totals = FitnessSnapshot(FitnessProfile(), 0,
            listOf(FoodLogEntry("log", cup, 1.5f, MealType.LUNCH))).nutrition
        assertEquals(225f, totals.calories, 0f)
        assertEquals(4.5f, totals.fiberGrams, 0f)
        assertEquals(18f, totals.sodiumMilligrams, 0f)
    }

    @Test fun invalidNutrientsCannotPoisonDailyTotals() {
        val food = parseFatSecretFood(mapOf("id" to "bad", "name" to "Food",
            "calories" to Float.NaN, "sodiumMilligrams" to Float.POSITIVE_INFINITY,
            "proteinGrams" to -1))!!
        assertEquals(0f, food.calories, 0f)
        assertEquals(0f, food.sodiumMilligrams, 0f)
        assertEquals(0f, food.proteinGrams, 0f)
        assertNull(parseFatSecretFood(mapOf("name" to " ")))
    }
}
