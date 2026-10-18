package com.olive.app.ui

import com.olive.app.ui.covers.olive.CoverTab
import com.olive.app.ui.covers.olive.OliveViewModel

import com.olive.app.data.DemoCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OliveNavigationTest {
    @Test fun backReturnsThroughTabsInVisitOrder() {
        val viewModel=OliveViewModel(DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke))

        viewModel.navigate(CoverTab.Saved)
        viewModel.navigate(CoverTab.Cart)

        assertTrue(viewModel.back())
        assertEquals(CoverTab.Saved,viewModel.tab)
        assertTrue(viewModel.back())
        assertEquals(CoverTab.Discover,viewModel.tab)
        assertFalse(viewModel.back())
    }

    @Test fun backClosesADishBeforeChangingTabs() {
        val viewModel=OliveViewModel(DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke))
        viewModel.navigate(CoverTab.Saved)
        viewModel.select(viewModel.dishes.first())

        assertTrue(viewModel.back())
        assertNull(viewModel.selectedDish)
        assertEquals(CoverTab.Saved,viewModel.tab)
    }
}
