package com.olive.app.ui.covers.fitness

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class FitnessAccountSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun collidingLegacyAccountHashesCannotShareFitnessCaches() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(fitnessAccountKey("Aa"), fitnessAccountKey("BB"))
        fitnessPreferences(context, "Aa").edit().putInt("step_goal", 12345).commit()
        assertFalse(fitnessPreferences(context, "BB").contains("step_goal"))
    }

    @Test fun onlyAccountDataSyncsAndHardwareBaselinesStayOnTheirDevice() {
        assertTrue(cloudFitnessKey("step_goal"))
        assertTrue(cloudFitnessKey("activity_steps_2026-11-15"))
        assertTrue(cloudFitnessKey("heart_rate_graph_samples_2026-11-15"))
        assertTrue(cloudFitnessKey("workout_history"))
        for (key in listOf("last_sensor_value", "watch_steps_2026-11-15", "phone_steps_2026-11-15", "sensor_total"))
            assertFalse(cloudFitnessKey(key))
    }

    @Test fun foodsAndGoalsSurviveAccountSwitchAndDeletedMealsRemainQueuedAsTombstones() {
        val a = FitnessStore(context, "profile-a")
        val b = FitnessStore(context, "profile-b")
        a.saveProfile(FitnessProfile(stepGoal = 12345))
        val food = NutritionFood("one", "Test food", "", "1 bowl", 150f, 4f, 20f, 5f, FoodDataSource.MANUAL)
        val added = a.addFood(food, 1.5f, MealType.LUNCH)
        assertEquals(225f, added.nutrition.calories, 0f)
        assertTrue(b.snapshot().foodsToday.isEmpty())
        assertEquals(8000, b.profile().stepGoal)
        assertEquals(12345, FitnessStore(context, "profile-a").profile().stepGoal)
        val id = added.foodsToday.single().logId
        a.removeFood(id)
        val pending = FitnessAccountSync.outbox(context, "profile-a")
        val key = "meal_${LocalDate.now()}_$id"
        assertTrue(pending.contains(key))
        assertNull(FitnessAccountSync.decode(pending.getString(key, null)!!))
        assertTrue(a.snapshot().foodsToday.isEmpty())
        assertFalse(FitnessAccountSync.outbox(context, "profile-b").contains(key))
    }

    @Test fun waterEntriesAreStoredPerAccountAndQueuedIndividually() {
        val store = FitnessStore(context, "water-account")
        store.addWater(250, 2500)
        val snapshot = store.addWater(500, 2500)
        assertEquals(750, snapshot.waterTodayMl)
        assertEquals(2500, snapshot.profile.waterGoalMl)
        assertEquals(750, FitnessStore(context, "water-account").snapshot().waterTodayMl)
        assertEquals(0, FitnessStore(context, "another-water-account").snapshot().waterTodayMl)
        val drinks = FitnessAccountSync.outbox(context, "water-account").all.keys.filter { it.startsWith("drink_") }
        assertEquals(2, drinks.size)
        assertTrue(cloudFitnessKey("water_goal_ml"))
    }

    @Test fun waterMinusUsesSignedAdjustmentsWithoutGoingBelowZero() {
        val store = FitnessStore(context, "water-adjustment")
        store.addWater(500, 2000)
        assertEquals(250, store.addWater(-250, 2000).waterTodayMl)
        assertEquals(0, store.addWater(-500, 2000).waterTodayMl)
        val pending = FitnessAccountSync.outbox(context, "water-adjustment")
        assertEquals(3, pending.all.keys.count { it.startsWith("drink_") })
        assertEquals(0, store.addWater(-250, 2000).waterTodayMl)
        assertEquals(3, pending.all.keys.count { it.startsWith("drink_") })
        assertEquals(250, store.addWater(250, 2000).waterTodayMl)
        assertEquals(0, FitnessStore(context, "other-water-adjustment").snapshot().waterTodayMl)
    }

    @Test fun editingAFoodUpdatesTheSameLogRecordAndScaledNutrition() {
        val store = FitnessStore(context, "food-edit")
        val food = NutritionFood("oats", "Oats", "", "100 g", 400f, 12f, 60f, 8f, FoodDataSource.MANUAL)
        val entry = store.addFood(food, 1f, MealType.BREAKFAST).foodsToday.single()
        val edited = entry.copy(food = food.copy(name = "Edited oats", calories = 350f), servings = 0.5f, meal = MealType.LUNCH)
        val result = store.updateFood(edited)
        assertEquals(1, result.foodsToday.size)
        assertEquals(entry.logId, result.foodsToday.single().logId)
        assertEquals("Edited oats", result.foodsToday.single().food.name)
        assertEquals(MealType.LUNCH, result.foodsToday.single().meal)
        assertEquals(175f, result.nutrition.calories, 0f)
        assertEquals(6f, result.nutrition.proteinGrams, 0f)
        val pending = FitnessAccountSync.outbox(context, "food-edit").all.filterKeys { it.startsWith("meal_") }
        assertEquals(1, pending.size)
        val payload = org.json.JSONObject(FitnessAccountSync.decode(pending.values.single() as String) as String)
        assertEquals(entry.logId, payload.getString("logId"))
        assertEquals("LUNCH", payload.getString("meal"))
        store.removeFood(entry.logId)
        assertTrue(store.updateFood(edited).foodsToday.isEmpty())
    }

    @Test fun repeatedTransportHeartbeatsAndOutOfOrderWatchDataDoNotDuplicateSamplesOrSteps() {
        val store = FitnessStore(context, "watch-test")
        val today = LocalDate.of(2026, 11, 15)
        store.recordWatchActivity(100, 70, 1000, today, nowMillis = 1000, heartRateUpdatedAt = 1000)
        val newer = store.recordWatchActivity(110, 70, 2000, today, nowMillis = 2000, heartRateUpdatedAt = 1000)
        assertEquals(110, newer.stepsToday)
        assertEquals(1, newer.heartRateVitals!!.sampleCount)
        store.recordWatchActivity(50, 80, 1500, today, nowMillis = 3000)
        val repeated = store.recordWatchActivity(110, 70, 3000, today, nowMillis = 3000, heartRateUpdatedAt = 1000)
        assertEquals(110, repeated.stepsToday)
        assertEquals(1, repeated.heartRateVitals!!.sampleCount)
    }

    @Test fun foodLibraryAndOutboxDecoderHandleAllSupportedValueTypesAndRecentSearches() {
        assertEquals(42, FitnessAccountSync.decode(JSONObject().put("type", "int").put("value", 42).toString()))
        assertEquals(99L, FitnessAccountSync.decode(JSONObject().put("type", "long").put("value", 99L).toString()))
        assertEquals(3.5f, FitnessAccountSync.decode(JSONObject().put("type", "float").put("value", 3.5).toString()))
        assertEquals(true, FitnessAccountSync.decode(JSONObject().put("type", "bool").put("value", true).toString()))
        assertEquals("abc", FitnessAccountSync.decode(JSONObject().put("type", "string").put("value", "abc").toString()))
        assertNull(FitnessAccountSync.decode(JSONObject().put("type", "null").put("value", JSONObject.NULL).toString()))
        assertNull(FitnessAccountSync.decode("{not-json"))

        val prefs = fitnessPreferences(context, "library-account")
        val library = FitnessFoodLibrary(prefs)
        val observed = mutableListOf<List<SavedFood>>()
        val subscription = library.observeSavedFoods { observed += it }

        val customFood = NutritionFood("custom-1", "Quinoa", "", "1 cup", 220f, 8f, 39f, 3.5f, FoodDataSource.MANUAL)
        library.setSaved(customFood, true)
        library.setSaved(customFood, false)

        library.recordSearch(RecentFoodSearch("   ", false))
        for (i in 1..10) library.recordSearch(RecentFoodSearch("Food $i", i % 2 == 0))
        val searches = library.searches()
        assertEquals(8, searches.size)
        assertEquals("Food 10", searches.first().query)

        subscription.close()
        assertTrue(observed.isNotEmpty())
    }
}
