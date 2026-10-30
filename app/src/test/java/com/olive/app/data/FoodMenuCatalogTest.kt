// Tests food-cover menu filters, dish choices, and checkout totals.
package com.olive.app.data

import com.olive.app.ui.covers.food.CoverTab
import com.olive.app.ui.covers.food.OliveViewModel
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

class OliveFoodViewModelTest {
    private val bowl = Dish(
        id = "bowl", name = "Garden Bowl", restaurant = "Olive Kitchen",
        category = "Bowls", description = "Fresh vegetables", priceCents = 1_495,
        minutes = "20-30", rating = "4.9", art = "bowl", diet = "Vegan",
        ingredients = listOf("Tomato", "Spinach"),
    )
    private val pizza = bowl.copy(
        id = "pizza", name = "Olive Pizza", category = "Pizza", priceCents = 1_600,
        diet = "Vegetarian", ingredients = listOf("Tomato", "Cheese"),
    )
    private fun viewModel() = OliveViewModel(object : CatalogRepository {
        override fun dishes() = listOf(bowl, pizza)
    })

    // Inputs: ordinary, blank, unknown, and localized searches with category filters. Expected: only matching known dishes are shown.
    @Test
    fun searchAndCategoryFiltersCoverNormalEmptyUnknownAndLocalizedText() {
        val model = viewModel()
        assertEquals(listOf(bowl, pizza), model.results)
        model.search("garden")
        assertEquals(listOf(bowl), model.results)
        model.search("not on the menu")
        assertTrue(model.results.isEmpty())
        model.updateSearchLabels(mapOf("pizza" to "Pizza aux olives"))
        model.search("olives")
        assertEquals(listOf(pizza), model.results)
        model.filter("Vegan")
        assertTrue(model.results.isEmpty())
        model.search("")
        assertEquals(listOf(bowl), model.results)
    }

    // Inputs: tab changes, a selected dish, repeated Back actions, and return-to-cover. Expected: Back unwinds the actual UI state in order.
    @Test
    fun navigationBackClosesDishThenRestoresPreviousTabs() {
        val model = viewModel()
        model.navigate(CoverTab.Saved)
        model.navigate(CoverTab.Cart)
        model.select(bowl)
        assertTrue(model.back())
        assertNull(model.selectedDish)
        assertTrue(model.back())
        assertEquals(CoverTab.Saved, model.tab)
        assertTrue(model.back())
        assertEquals(CoverTab.Discover, model.tab)
        assertFalse(model.back())
        model.navigate(CoverTab.Account)
        model.search("pizza")
        model.select(pizza)
        model.returnToCover()
        assertEquals(CoverTab.Discover, model.tab)
        assertEquals("", model.query)
        assertNull(model.selectedDish)
    }

    // Inputs: malformed restored cart values, unknown IDs, and an extreme quantity change. Expected: state is limited to known dishes and 0-99 quantities without overflow.
    @Test
    fun restoredAndChangedCartStateRejectsUnknownNegativeAndOverflowValues() {
        val model = viewModel()
        model.restoreCover(
            cart = mapOf("bowl" to Int.MAX_VALUE, "pizza" to -1, "unknown" to 4),
            favorites = setOf("bowl", "unknown"),
            customizations = mapOf("pizza" to setOf("Cheese"), "unknown" to setOf("Bad")),
        )
        assertEquals(mapOf("bowl" to 99), model.cart)
        assertEquals(setOf("bowl"), model.favorites)
        assertEquals(mapOf("pizza" to setOf("Cheese")), model.customizations)
        assertEquals(99, model.itemCount)
        model.changeQuantity("bowl", Int.MAX_VALUE)
        assertEquals(99, model.cart["bowl"])
        model.changeQuantity("bowl", Int.MIN_VALUE)
        assertFalse("bowl" in model.cart)
        model.changeQuantity("unknown", 1)
        assertTrue(model.cart.isEmpty())
    }

    // Inputs: favourites, ingredient changes, and cart changes. Expected: every saved-state change invokes persistence with the resulting state.
    @Test
    fun userChangesPersistFavoritesIngredientsAndCartState() {
        val model = viewModel()
        val snapshots = mutableListOf<Triple<Map<String, Int>, Set<String>, Map<String, Set<String>>>>()
        model.persistCover = { cart, favorites, choices -> snapshots += Triple(cart, favorites, choices) }
        model.favorite("bowl")
        model.toggleIngredient(bowl, "Tomato")
        model.changeQuantity("bowl", 1)
        model.changeQuantity("bowl", -1)
        assertEquals(4, snapshots.size)
        assertEquals(setOf("bowl"), model.favorites)
        assertEquals(setOf("Spinach"), model.selectedIngredients(bowl))
        assertTrue(model.cart.isEmpty())
        assertEquals(0, model.total)
    }

    // Inputs: whitespace and overlong delivery areas. Expected: text is trimmed and bounded for display and storage.
    @Test
    fun deliveryAreaIsTrimmedAndLengthLimited() {
        val model = viewModel()
        model.chooseDeliveryArea("  Edmonton  ")
        assertEquals("Edmonton", model.deliveryArea)
        model.chooseDeliveryArea("x".repeat(100))
        assertEquals(60, model.deliveryArea.length)
    }

    // Inputs: 500 deterministic additions and removals with occasional integer extremes. Expected: every intermediate quantity remains 0-99 and totals never wrap negative.
    @Test
    fun repeatedCartMutationsKeepEveryIntermediateStateBounded() {
        val model = viewModel()
        val random = kotlin.random.Random(0xF00D)
        repeat(500) { step ->
            val id = if (random.nextBoolean()) "bowl" else "pizza"
            val delta = when {
                step % 101 == 0 -> Int.MAX_VALUE
                step % 79 == 0 -> Int.MIN_VALUE
                else -> random.nextInt(-20, 21)
            }
            model.changeQuantity(id, delta)
            assertTrue("step=$step id=$id cart=${model.cart}", model.cart.values.all { it in 1..99 })
            assertTrue("step=$step total=${model.total}", model.total >= 0)
            assertTrue("step=$step itemCount=${model.itemCount}", model.itemCount in 0..198)
        }
    }
}
