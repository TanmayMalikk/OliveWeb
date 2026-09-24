package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val dishes = DemoCatalogRepository().dishes()
    @Test fun searchMatchesRestaurantIgnoringCaseAndWhitespace() {
        assertEquals(2, filterDishes(dishes, "  ROOTS  ", "All").size)
    }
    @Test fun categoryAndSearchAreCombined() {
        assertTrue(filterDishes(dishes,"burrata","Bowls").isEmpty())
        assertEquals("pizza",filterDishes(dishes,"burrata","Pizza").single().id)
    }
    @Test fun totalsUseCentsAndIgnoreUnknownItems() {
        assertEquals(4840,cartTotal(dishes,mapOf("bowl" to 2,"pizza" to 1,"unknown" to 7)))
        assertEquals(0,cartTotal(dishes,emptyMap()))
    }
    @Test fun negativeQuantitiesCannotReduceTheTotal() {
        assertEquals(0,cartTotal(dishes,mapOf("bowl" to -1)))
    }
}
