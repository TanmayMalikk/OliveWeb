// Tests back navigation between cover tabs and food details.
package com.olive.app.ui

import com.olive.app.ui.covers.food.CoverTab
import com.olive.app.ui.covers.food.OliveViewModel

import com.olive.app.data.DemoCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OliveNavigationTest {
    // Inputs: the values shown below. Expected: back returns through tabs in visit order.
    @Test
    fun backReturnsThroughTabsInVisitOrder() {
        val viewModel =
            OliveViewModel(DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke))

        viewModel.navigate(CoverTab.Saved)
        viewModel.navigate(CoverTab.Cart)

        assertTrue(viewModel.back())
        assertEquals(CoverTab.Saved, viewModel.tab)
        assertTrue(viewModel.back())
        assertEquals(CoverTab.Discover, viewModel.tab)
        assertFalse(viewModel.back())
    }

    // Inputs: the values shown below. Expected: back closes adish before changing tabs.
    @Test
    fun backClosesADishBeforeChangingTabs() {
        val viewModel =
            OliveViewModel(DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke))
        viewModel.navigate(CoverTab.Saved)
        viewModel.select(viewModel.dishes.first())

        assertTrue(viewModel.back())
        assertNull(viewModel.selectedDish)
        assertEquals(CoverTab.Saved, viewModel.tab)
    }

    // Inputs: the values shown below. Expected: repeated tabs unknown cart items and extreme quantities stay bounded.
    @Test
    fun repeatedTabsUnknownCartItemsAndExtremeQuantitiesStayBounded() {
        val viewModel = OliveViewModel(DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke))
        val dish = viewModel.dishes.first()

        viewModel.navigate(CoverTab.Saved)
        viewModel.navigate(CoverTab.Saved)
        assertTrue(viewModel.back())
        assertEquals(CoverTab.Discover, viewModel.tab)
        assertFalse(viewModel.back())

        viewModel.changeQuantity("missing-id", Int.MAX_VALUE)
        assertTrue(viewModel.cart.isEmpty())

        viewModel.changeQuantity(dish.id, Int.MAX_VALUE)
        assertEquals(99, viewModel.cart[dish.id])
        viewModel.changeQuantity(dish.id, Int.MIN_VALUE)
        assertFalse(dish.id in viewModel.cart)
    }
}
