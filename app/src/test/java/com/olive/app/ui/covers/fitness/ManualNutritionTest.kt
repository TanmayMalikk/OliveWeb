package com.olive.app.ui.covers.fitness

import org.junit.Assert.*
import org.junit.Test

class ManualNutritionTest {
    private fun draft() = ManualNutritionDraft("Cereal bar", "1 bar (40 g)", mapOf(
        LabelNutrient.CALORIES to "150", LabelNutrient.PROTEIN to "4",
        LabelNutrient.CARBS to "23", LabelNutrient.FAT to "5",
        LabelNutrient.FIBER to "2,5", LabelNutrient.SODIUM to "120",
    ))

    @Test fun manualPortionsScaleEveryMacroAndOptionalNutrient() {
        val food = draft().food()!!
        assertEquals(FoodDataSource.MANUAL, food.source)
        assertEquals("1 bar (40 g)", food.servingDescription)
        val totals = FitnessSnapshot(FitnessProfile(), 0,
            listOf(FoodLogEntry("manual", food, 1.5f, MealType.SNACK))).nutrition
        assertEquals(225f, totals.calories, 0f)
        assertEquals(6f, totals.proteinGrams, 0f)
        assertEquals(34.5f, totals.carbohydrateGrams, 0f)
        assertEquals(7.5f, totals.fatGrams, 0f)
        assertEquals(3.75f, totals.fiberGrams, 0f)
        assertEquals(180f, totals.sodiumMilligrams, 0f)
        assertEquals(0f, totals.cholesterolMilligrams, 0f)
    }

    @Test fun missingOrInvalidRequiredValuesCannotBeLoggedButExplicitZeroCan() {
        assertNull(draft().copy(name = " ").food())
        assertNull(draft().copy(serving = "").food())
        for (value in listOf("", "-5", "NaN", "Infinity", "1.2.3", "1e38", "1000001")) {
            assertNull(draft().copy(nutrients = draft().nutrients + (LabelNutrient.CALORIES to value)).food())
        }
        assertNotNull(draft().copy(nutrients = draft().nutrients + (LabelNutrient.FAT to "0")).food())
        assertNull(draft().copy(nutrients = draft().nutrients + (LabelNutrient.SODIUM to "bad")).food())
    }

    @Test fun labelScanExtractsServingAndIgnoresDailyValuePercentages() {
        val reading = parseNutritionLabel("""
            Nutrition Facts
            Serving size 1 bar (40 g)
            Calories 150
            Total Fat 5g 6%
            Saturated Fat 1 g 5%
            Trans Fat 0 g
            Sodium 120 mg 5%
            Total Carbohydrate 23 g 8%
            Dietary Fiber 2.5 g 9%
            Total Sugars 8 g
            Protein 4 g
        """.trimIndent())
        assertEquals("1 bar (40 g)", reading.serving)
        assertEquals(150f, reading.nutrients[LabelNutrient.CALORIES]!!.toFloat(), 0f)
        assertEquals(5f, reading.nutrients[LabelNutrient.FAT]!!.toFloat(), 0f)
        assertEquals(1f, reading.nutrients[LabelNutrient.SATURATED_FAT]!!.toFloat(), 0f)
        assertEquals(120f, reading.nutrients[LabelNutrient.SODIUM]!!.toFloat(), 0f)
        assertEquals(4f, reading.nutrients[LabelNutrient.PROTEIN]!!.toFloat(), 0f)
    }

    @Test fun labelScanConvertsUnitsAndHandlesFrenchAndSplitRows() {
        val reading = parseNutritionLabel("""
            Pour 100 g
            Énergie 840 kJ / 200 kcal
            Lipides 7,5 g
            Glucides 25 g
            Protéines
            9 g
            Sodium 0,12 g
            Potassium 200 mg
        """.trimIndent())
        assertEquals("100 g", reading.serving)
        assertEquals(200f, reading.nutrients[LabelNutrient.CALORIES]!!.toFloat(), 0f)
        assertEquals(7.5f, reading.nutrients[LabelNutrient.FAT]!!.toFloat(), 0f)
        assertEquals(9f, reading.nutrients[LabelNutrient.PROTEIN]!!.toFloat(), 0f)
        assertEquals(120f, reading.nutrients[LabelNutrient.SODIUM]!!.toFloat(), 0.001f)
    }

    @Test fun portionInputPreservesFractionalServingsAndRejectsInvalidQuantities() {
        assertEquals(1.5f, parseServingAmount("1,5")!!, 0f)
        assertEquals(0.5f, parseServingAmount(".5")!!, 0f)
        assertEquals(2.25f, parseServingAmount("2.25")!!, 0f)
        for (value in listOf("-1", "0", "10001", "NaN", "Infinity", "1.2.3", "1e2", "")) {
            assertNull(parseServingAmount(value))
        }
    }

    @Test fun uncertainScansLeaveFieldsBlankRatherThanInventingNutrients() {
        val reading = parseNutritionLabel("Energy 840 kJ\nTotal Fat 10%\nProtein -3g\nSodium\nProtein 2g")
        assertFalse(reading.nutrients.containsKey(LabelNutrient.CALORIES))
        assertFalse(reading.nutrients.containsKey(LabelNutrient.FAT))
        assertFalse(reading.nutrients.containsKey(LabelNutrient.PROTEIN))
        assertFalse(reading.nutrients.containsKey(LabelNutrient.SODIUM))
        assertNull(reading.serving)
        assertTrue(parseNutritionLabel("unreadable").nutrients.isEmpty())
    }
}
