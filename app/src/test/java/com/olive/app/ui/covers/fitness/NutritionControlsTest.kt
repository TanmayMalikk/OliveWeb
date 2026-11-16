package com.olive.app.ui.covers.fitness

import org.junit.Assert.*
import org.junit.Test

class NutritionControlsTest {
    private val grams = NutritionFood("food", "Oats", "", "100 g", 400f, 12f, 60f, 8f, FoodDataSource.FATSECRET)

    @Test fun customWeightScalesEveryNutrientWithoutRenamingTheBasis() {
        val portion = customFoodPortion(grams, "35", "g")!!
        assertEquals(0.35f, portion.servings, 0.0001f)
        assertEquals("100 g", portion.food.servingDescription)
        assertEquals(140f, portion.food.calories * portion.servings, 0.001f)
        assertEquals(4.2f, portion.food.proteinGrams * portion.servings, 0.001f)
        assertNull(customFoodPortion(grams, "35", "ml"))
    }

    @Test fun tinyAndFractionalPortionsRemainAccurateWhenSavedAndDisplayed() {
        val tiny = customFoodPortion(grams, "1", "g")!!
        assertEquals(0.01f, tiny.servings, 0f)
        assertEquals("0.01", tiny.servings.displayAmount())
        assertEquals(0.5f, customFoodPortion(grams, "0,5", "serving")!!.servings, 0f)
        assertEquals(ServingBasis(250f, "ml"), servingBasis("1 cup (250 ml)"))
        assertEquals(ServingBasis(1000f, "g"), servingBasis("1 kg"))
        assertNull(customFoodPortion(grams, "-2", "g"))
    }

    @Test fun barcodeOcrRequiresAChecksumAndOneUnambiguousProduct() {
        assertTrue(validFoodBarcode("0049000006346"))
        assertFalse(validFoodBarcode("0049000006347"))
        assertEquals("0049000006346", barcodeFromLabelText("Barcode\n0 049000 006346"))
        assertNull(barcodeFromLabelText("0049000006346\n9780201379624"))
        assertNull(barcodeFromLabelText("Calories 150 Protein 12 g"))
        assertNull(barcodeFromLabelText("10049000006346"))
    }
}
