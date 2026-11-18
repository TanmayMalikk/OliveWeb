package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.ui.covers.fitness.*
import com.olive.app.ui.shared.OliveTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NutritionControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun keepDisplayAwake() { compose.activityRule.scenario.onActivity {
        it.setShowWhenLocked(true); it.setTurnScreenOn(true)
        it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } }
    private fun capture(tag: String, name: String) {
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun manualEntryIsCompactAndCameraIsAnIcon() {
        var selected: NutritionFood? = null
        compose.setContent { OliveTheme { ManualFoodEntryDialog({}, { selected = it }) } }
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_scan_label)).assertIsDisplayed()
        compose.onNodeWithTag("manual_food_name").performTextInput("Test cereal bar")
        compose.onNodeWithTag("manual_food_serving").performTextInput("1 bar (40 g)")
        for ((field, value) in listOf("CALORIES" to "150", "PROTEIN" to "4", "CARBS" to "23", "FAT" to "5"))
            compose.onNodeWithTag("manual_$field").performTextInput(value)
        compose.onNodeWithTag("manual_food_continue").assertIsEnabled()
        // Hide IME before capturing the synthetic form.
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        capture("manual_food_dialog", "nutrition-manual-test.png")
        compose.onNodeWithTag("manual_food_continue").performClick()
        compose.runOnIdle { assertEquals(150f, selected!!.calories, 0f) }
    }

    @Test fun nutritionActionsAppearAboveSummaryWithWaterVisible() {
        val food = NutritionFood("demo", "Test food", "", "1 serving", 237.5f, 18.9f, 26.6f, 7.3f, FoodDataSource.MANUAL)
        compose.setContent { OliveTheme {
            TodayPage(FitnessSnapshot(FitnessProfile(), 0, listOf(FoodLogEntry("demo", food, 1f, MealType.LUNCH)), waterTodayMl = 750),
                true, PaddingValues(0.dp), {}, {}, { _, _ -> })
        } }
        val addTop = compose.onNodeWithText(context.getString(R.string.add_food)).fetchSemanticsNode().boundsInRoot.top
        val summaryTop = compose.onNodeWithText("237.5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(addTop < summaryTop)
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_progress, 750, 2000)).assertIsDisplayed()
        capture("fitness_today", "nutrition-summary-test.png")
    }

    @Test fun foodEntryModesShareOneRowAndBarcodeHasCamera() {
        compose.setContent { OliveTheme { FoodSearchPage(true, PaddingValues(0.dp), { _, _, _ -> }) } }
        compose.onNodeWithTag("nutrition_manual_entry").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nutrition_all_foods)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nutrition_generic)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nutrition_branded)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nutrition_barcode)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_scan_barcode)).assertIsDisplayed()
    }

    @Test fun extraNutrientsStartCollapsed() {
        compose.setContent { OliveTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DailyNutritionCard(FitnessSnapshot(FitnessProfile(), 0, emptyList()))
        } } }
        compose.onNodeWithContentDescription(context.getString(R.string.sodium) + ", 0 mg").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.nutrition_other_nutrients)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.sodium) + ", 0 mg").performScrollTo().assertIsDisplayed()
    }

    @Test fun customServingWeightUpdatesSavedFactor() {
        val food = NutritionFood("test", "Test oats", "", "100 g", 400f, 12f, 60f, 8f, FoodDataSource.FATSECRET)
        var saved: Float? = null
        compose.setContent { OliveTheme { AddFoodDialog(food, {}, { _, servings, _ -> saved = servings }) } }
        compose.onNodeWithText("100 g", substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.nutrition_custom_portion)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("35")
        compose.onNodeWithText(context.getString(R.string.add_food)).performClick()
        compose.runOnIdle { assertEquals(0.35f, saved!!, 0.0001f) }
    }

    @Test fun waterQuickButtonsApplyPositiveAndNegativeAmounts() {
        var adjustment = 0
        compose.setContent { OliveTheme { WaterTracker(500, 2000, true, { value, _ -> adjustment = value }) } }
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_minus)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_plus)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_progress, 500, 2000)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_minus)).performClick()
        compose.runOnIdle { assertEquals(-250, adjustment) }
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_plus)).performClick()
        compose.runOnIdle { assertEquals(250, adjustment) }
    }

    @Test fun editingAnExistingPortionUsesItsSavedAmountAndMeal() {
        val food = NutritionFood("test", "Test oats", "", "100 g", 400f, 12f, 60f, 8f, FoodDataSource.MANUAL)
        var saved: Float? = null
        var savedMeal: MealType? = null
        compose.setContent { OliveTheme {
            AddFoodDialog(food, {}, { _, quantity, meal -> saved = quantity; savedMeal = meal },
                initialServings = 0.5f, initialMeal = MealType.LUNCH, editing = true)
        } }
        compose.onNodeWithText("Lunch").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(0.5f, saved!!, 0f); assertEquals(MealType.LUNCH, savedMeal) }
    }

    @Test fun glassOpensWaterEntryAndSavesChosenAmount() {
        var amount = 0
        compose.setContent { OliveTheme { WaterTracker(500, 2000, true, { value, _ -> amount = value }) } }
        compose.onNodeWithContentDescription(context.getString(R.string.nutrition_water_progress, 500, 2000)).performClick()
        compose.onNodeWithText("500 ml").performClick()
        compose.onNodeWithText(context.getString(R.string.nutrition_add_water), useUnmergedTree = true)
        compose.onAllNodesWithText(context.getString(R.string.nutrition_add_water)).onLast().performClick()
        compose.runOnIdle { assertEquals(500, amount) }
    }

    @Test fun labelPhotoOcrReadsSyntheticNutritionValues() = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(1600, 1100, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK; textSize = 64f
        }
        listOf("Nutrition Facts", "Serving size 1 bar (40 g)", "Calories 150", "Total Fat 5 g",
            "Total Carbohydrate 23 g", "Protein 4 g").forEachIndexed { index, line ->
            canvas.drawText(line, 100f, 150f + index * 140, paint)
        }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        val result = parseNutritionLabel(readNutritionPhoto(context, bytes))
        assertEquals(150f, result.nutrients[LabelNutrient.CALORIES]!!.toFloat(), 0f)
        assertEquals(4f, result.nutrients[LabelNutrient.PROTEIN]!!.toFloat(), 0f)
        assertEquals(23f, result.nutrients[LabelNutrient.CARBS]!!.toFloat(), 0f)
        assertEquals(5f, result.nutrients[LabelNutrient.FAT]!!.toFloat(), 0f)
    }

    @Test fun cameraRecognitionReadsSyntheticBarcodeWithoutNetwork() = runBlocking {
        val code = "0049000006346"
        val patterns = listOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
        val left = code.substring(1,7).map { patterns[it.digitToInt()] }.joinToString("")
        val right = code.substring(7).map { patterns[it.digitToInt()].map { bit -> if (bit == '1') '0' else '1' }.joinToString("") }.joinToString("")
        val bits = "101" + left + "01010" + right + "101"
        val bitmap = android.graphics.Bitmap.createBitmap(1000, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        bits.forEachIndexed { i, bit -> if (bit == '1') canvas.drawRect(100f+i*8, 40f, 108f+i*8, 290f, paint) }
        paint.textSize = 48f
        canvas.drawText(code, 200f, 355f, paint)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        assertEquals(code, readBarcodePhoto(context, bytes))
    }
}
