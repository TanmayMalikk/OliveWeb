package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val dishes = DemoCatalogRepository(com.olive.app.ui.testResourceText()::invoke).dishes()
    @Test fun homemadeMenuHasTwentyItemsAndAllDietChoices() {
        assertTrue(dishes.size>=20)
        assertEquals(setOf("Vegetarian","Vegan","Meat"),dishes.map { it.diet }.toSet())
        assertEquals(dishes.size,filterDishes(dishes,"  OLIVE KITCHEN  ","All").size)
    }
    @Test fun categoryAndSearchAreCombined() {
        assertTrue(filterDishes(dishes,"chicken","Vegan").isEmpty())
        assertEquals("pizza3",filterDishes(dishes,"garden pizza","Vegan").single().id)
    }
    @Test fun totalsUseCentsAndIgnoreUnknownItems() {
        assertEquals(4590,cartTotal(dishes,mapOf("bowl" to 2,"pizza" to 1,"unknown" to 7)))
        assertEquals(0,cartTotal(dishes,emptyMap()))
    }
    @Test fun negativeQuantitiesCannotReduceTheTotal() {
        assertEquals(0,cartTotal(dishes,mapOf("bowl" to -1)))
    }
}
