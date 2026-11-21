package com.olive.app.ui.covers.fitness

import android.content.Context
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FitnessStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val date = LocalDate.of(2026, 11, 16)
    private fun store() = FitnessStore(context, "fixture-${UUID.randomUUID()}")
    private val food get() = NutritionFood("oats", "Oats", "Test brand", "100 g", 200f, 8f, 34f, 4f, FoodDataSource.OPEN_FOOD_FACTS)

    @Test fun localAccountsWithoutFirebaseCanOpenFitnessAndSkipCloudHealthReads() {
        assertNull(fitnessAuthenticatedUid())
        kotlinx.coroutines.runBlocking { FitnessHealthConnect.read(context, "local-fixture") }
    }
    @Test fun profilesPersistAndClampGoalsWithoutLeakingAcrossAccounts() {
        val first = store()
        first.saveProfile(FitnessProfile(weightKg = 500f, heightCm = 50f, stepGoal = -1, calorieGoal = 100000, waterGoalMl = 0))
        assertEquals(300f, first.profile().weightKg, 0f)
        assertEquals(100f, first.profile().heightCm, 0f)
        assertEquals(500, first.profile().stepGoal)
        assertEquals(10000, first.profile().calorieGoal)
        assertEquals(250, first.profile().waterGoalMl)
        assertEquals(8000, store().profile().stepGoal)
    }
    @Test fun foodEditsReplaceTheSameLogAndPreserveAllNutritionValues() {
        val store = store()
        val added = store.addFood(food, 1.5f, MealType.BREAKFAST, date).foodsToday.single()
        val edited = added.copy(servings = 0.75f, meal = MealType.DINNER)
        val result = store.updateFood(edited, date)
        assertEquals(added.logId, result.foodsToday.single().logId)
        assertEquals(150f, result.nutrition.calories, 0.001f)
        assertEquals(MealType.DINNER, result.foodsToday.single().meal)
        assertEquals(0, store.snapshot(date.plusDays(1)).foodsToday.size)
        assertEquals(1, store.updateFood(edited.copy(logId = "unknown"), date).foodsToday.size)
        assertEquals(0, store.removeFood(added.logId, date).foodsToday.size)
    }
    @Test fun invalidEditedPortionsDoNotChangeTheDiary() {
        val store = store()
        val entry = store.addFood(food, 1f, MealType.LUNCH, date).foodsToday.single()
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 10001f)) {
            assertThrows(IllegalArgumentException::class.java) { store.updateFood(entry.copy(servings = value), date) }
        }
        assertEquals(1f, store.snapshot(date).foodsToday.single().servings, 0f)
    }

    @Test fun duplicateSaveAndUndoUseTheOriginalLogId() {
        val store = store()
        val key = "same-save"
        val entry = store.addFood(food, 1f, MealType.LUNCH, date, logId = key).foodsToday.single()
        assertEquals(listOf(entry), store.addFood(food, 1f, MealType.LUNCH, date, logId = key).foodsToday)
        assertTrue(store.removeFood(key, date).foodsToday.isEmpty())
        assertEquals(listOf(entry), store.restoreFood(entry, date).foodsToday)
        assertEquals(listOf(entry), store.restoreFood(entry, date).foodsToday)
    }
    @Test fun hydrationAdjustmentsCannotGoNegativeAndAreSeparatedByDay() {
        val store = store()
        assertEquals(250, store.addWater(250, 2000, date).waterTodayMl)
        assertEquals(750, store.addWater(500, 2000, date).waterTodayMl)
        assertEquals(0, store.addWater(-1000, 2000, date).waterTodayMl)
        assertEquals(0, store.addWater(-250, 2000, date).waterTodayMl)
        assertEquals(0, store.snapshot(date.plusDays(1)).waterTodayMl)
        assertThrows(IllegalArgumentException::class.java) { store.addWater(0, 2000, date) }
        assertThrows(IllegalArgumentException::class.java) { store.addWater(10001, 2000, date) }
        assertThrows(IllegalArgumentException::class.java) { store.addWater(250, 0, date) }
    }
    @Test fun phoneSensorResetsAndDuplicateSamplesCannotInflateSteps() {
        val store = store()
        assertEquals(0, store.recordSensorValue(1000, date, 10000).stepsToday)
        assertEquals(200, store.recordSensorValue(1200, date, 11000).stepsToday)
        assertEquals(200, store.recordSensorValue(1200, date, 12000).stepsToday)
        assertEquals(200, store.recordSensorValue(5, date, 13000).stepsToday)
        assertEquals(210, store.recordSensorValue(15, date, 14000).stepsToday)
    }
    @Test fun watchAndHealthConnectTotalsReconcileInsteadOfAddingOverlappingSteps() {
        val store = store()
        assertEquals(500, store.recordWatchActivity(500, 80, 10000, date, 10000).stepsToday)
        assertEquals(500, store.recordWatchActivity(9999, 80, 9000, date, 10000).stepsToday)
        store.recordHealthConnect(600, emptyList(), date)
        assertEquals(600, store.snapshot(date).stepsToday)
        store.recordHealthConnect(400, emptyList(), date)
        assertEquals(600, store.snapshot(date).stepsToday)
        assertEquals(0, store.snapshot(date.plusDays(1)).stepsToday)
    }
    @Test fun heartRateImportsRejectInvalidAndRepeatedSamples() {
        val store = store()
        val samples = listOf(HeartRateSample(200000, 90), HeartRateSample(100000, 70), HeartRateSample(300000, 400))
        store.recordHealthConnect(100, samples, date)
        store.recordHealthConnect(100, samples, date)
        val vitals = store.snapshot(date).heartRateVitals!!
        assertEquals(2, vitals.samples.size)
        assertEquals(listOf(70, 90), vitals.samples.map { it.bpm })
    }
    @Test fun historyContainsDailyNutritionAndStepsWithoutInventingData() {
        val store = store()
        store.addFood(food, 2f, MealType.BREAKFAST, date)
        store.recordHealthConnect(1000, emptyList(), date)
        val history = store.history(3, date)
        assertEquals(3, history.size)
        assertEquals(400f, history.last().caloriesEaten, 0f)
        assertEquals(1000, history.last().steps)
        assertEquals(0f, history.first().caloriesEaten, 0f)
    }
}
