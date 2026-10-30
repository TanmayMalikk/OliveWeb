// Tests food-cover menu filters, dish choices, and checkout totals.
package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class FoodMenuCatalogTest {
    private val dishes = DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke).dishes()

    // Inputs: the values shown below. Expected: homemade menu has twenty items and all diet choices.
    @Test
    fun homemadeMenuHasTwentyItemsAndAllDietChoices() {
        assertTrue(dishes.size >= 20)
        assertEquals(setOf("Vegetarian", "Vegan", "Meat"), dishes.map { it.diet }.toSet())
        assertEquals(dishes.size, filterDishes(dishes, "  OLIVE KITCHEN  ", "All").size)
    }

    // Inputs: the values shown below. Expected: category and search are combined.
    @Test
    fun categoryAndSearchAreCombined() {
        assertTrue(filterDishes(dishes, "chicken", "Vegan").isEmpty())
        assertEquals("pizza3", filterDishes(dishes, "garden pizza", "Vegan").single().id)
    }

    // Inputs: the values shown below. Expected: totals use cents and ignore unknown items.
    @Test
    fun totalsUseCentsAndIgnoreUnknownItems() {
        assertEquals(4590, cartTotal(dishes, mapOf("bowl" to 2, "pizza" to 1, "unknown" to 7)))
        assertEquals(0, cartTotal(dishes, emptyMap()))
    }

    // Inputs: the values shown below. Expected: negative quantities cannot reduce the total.
    @Test
    fun negativeQuantitiesCannotReduceTheTotal() {
        assertEquals(0, cartTotal(dishes, mapOf("bowl" to -1)))
    }

    // Inputs: the values shown below. Expected: blank unknown and wrong case filters have predictable results.
    @Test
    fun blankUnknownAndWrongCaseFiltersHavePredictableResults() {
        assertEquals(dishes, filterDishes(dishes, "   ", "All"))
        assertTrue(filterDishes(dishes, "not a real meal", "All").isEmpty())
        assertTrue(filterDishes(dishes, "", "vegan").isEmpty())
        assertEquals(
            filterDishes(dishes, "GARDEN PIZZA", "Vegan"),
            filterDishes(dishes, "garden pizza", "Vegan"),
        )
    }

    // Gives the food cover a SQL-like search string and expects an empty result, proving it is plain search text.
    @Test
    fun injectionLikeSearchTextCannotChangeTheMenuQuery() {
        val injectionText = "'; DROP TABLE dishes; --"

        assertTrue(filterDishes(dishes, injectionText, "All").isEmpty())
        assertEquals(dishes.size, filterDishes(dishes, "", "All").size)
    }

    // Gives checkout the largest possible quantity and expects a capped total instead of integer overflow.
    @Test
    fun extremeCartQuantityCannotWrapTheTotalIntoANegativeValue() {
        val firstDish = dishes.first()

        assertEquals(
            Int.MAX_VALUE,
            cartTotal(dishes, mapOf(firstDish.id to Int.MAX_VALUE)),
        )
    }
}
