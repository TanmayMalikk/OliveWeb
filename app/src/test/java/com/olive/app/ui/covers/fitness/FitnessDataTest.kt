// Tests fitness calculations and nutrition totals with normal and boundary inputs.
package com.olive.app.ui.covers.fitness

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FitnessDataTest {
    // A normal adult height and 1,000 steps should produce stable positive estimates.
    @Test
    fun stepEstimatesUseHeightAndWeight() {
        val profile = FitnessProfile(weightKg = 70f, heightCm = 170f)
        assertEquals(0.7021f, FitnessMath.distanceKm(1_000, profile.heightCm), 0.0001f)
        assertEquals(36.86f, FitnessMath.walkingCalories(1_000, profile), 0.02f)
    }

    // Negative steps and extreme body measurements must not create negative or unbounded totals.
    @Test
    fun stepEstimatesClampInvalidInputs() {
        assertEquals(0f, FitnessMath.distanceKm(-5_000, 170f), 0f)
        assertEquals(
            FitnessMath.strideMeters(100f),
            FitnessMath.strideMeters(-2_000f),
            0f,
        )
        assertEquals(
            FitnessMath.strideMeters(230f),
            FitnessMath.strideMeters(20_000f),
            0f,
        )
    }

    // Two foods with fractional servings should add calories and all three macros independently.
    @Test
    fun dailyNutritionAddsEveryLoggedServing() {
        val breakfast = FoodLogEntry(
            "one",
            NutritionFood(
                "oats", "Oats", "", "100 g", 200f, 8f, 34f, 4f,
                FoodDataSource.OPEN_FOOD_FACTS,
                saturatedFatGrams = 1f,
                fiberGrams = 5f,
                sugarGrams = 2f,
                sodiumMilligrams = 25f,
            ),
            1.5f,
            MealType.BREAKFAST,
        )
        val snack = FoodLogEntry(
            "two",
            NutritionFood(
                "yogurt", "Yogurt", "", "1 cup", 100f, 10f, 12f, 2f,
                FoodDataSource.FATSECRET,
            ),
            0.5f,
            MealType.SNACK,
        )
        val totals = FitnessSnapshot(FitnessProfile(), 0, listOf(breakfast, snack)).nutrition
        assertEquals(350f, totals.calories, 0f)
        assertEquals(17f, totals.proteinGrams, 0f)
        assertEquals(57f, totals.carbohydrateGrams, 0f)
        assertEquals(7f, totals.fatGrams, 0f)
        assertEquals(1.5f, totals.saturatedFatGrams, 0f)
        assertEquals(7.5f, totals.fiberGrams, 0f)
        assertEquals(3f, totals.sugarGrams, 0f)
        assertEquals(37.5f, totals.sodiumMilligrams, 0f)
    }

    // Display values keep whole numbers simple while preserving one useful decimal place.
    @Test
    fun displayAmountsStayReadable() {
        assertEquals("12", 12f.displayAmount())
        assertEquals("12.3", 12.25f.displayAmount())
    }

    // A recently active watch is the primary step source, so the same phone steps are ignored.
    @Test
    fun activeWatchPreventsDuplicatePhoneSteps() {
        val now = 1_000_000L
        assertEquals(
            0,
            StepSourcePolicy.phoneDeltaToAdd(
                phoneDelta = 75,
                lastWatchActivityAt = now - 30_000L,
                nowMillis = now,
            ),
        )
    }

    // Once watch activity is stale, new phone steps become the fallback instead of being lost.
    @Test
    fun staleWatchAllowsPhoneFallbackSteps() {
        val now = 1_000_000L
        assertEquals(
            75,
            StepSourcePolicy.phoneDeltaToAdd(
                phoneDelta = 75,
                lastWatchActivityAt = now - StepSourcePolicy.WATCH_ACTIVE_WINDOW_MS - 1L,
                nowMillis = now,
            ),
        )
    }

    // When the watch reconnects, only steps beyond the phone fallback are added to the total.
    @Test
    fun returningWatchRemovesFallbackOverlap() {
        assertEquals(20, StepSourcePolicy.watchDeltaToAdd(120, 100))
        assertEquals(0, StepSourcePolicy.watchDeltaToAdd(80, 100))
    }

    // Invalid negative sensor changes never reduce or inflate the daily step total.
    @Test
    fun sourcePolicyRejectsNegativeDeltas() {
        assertEquals(0, StepSourcePolicy.phoneDeltaToAdd(-5, 0L, 1_000L))
        assertEquals(0, StepSourcePolicy.watchDeltaToAdd(-5, -10))
    }

    // The history graph receives exactly 14 dates in oldest-to-newest order, ending today.
    @Test
    fun historyDatesCoverTheLatestFourteenDaysInOrder() {
        val endDate = LocalDate.of(2026, 10, 31)
        val dates = FitnessHistory.datesEndingOn(endDate, 14)

        assertEquals(14, dates.size)
        assertEquals(LocalDate.of(2026, 10, 18), dates.first())
        assertEquals(endDate, dates.last())
    }

    // Invalid ranges are kept small enough for the UI and never produce an empty graph.
    @Test
    fun historyDateCountStaysWithinSupportedBounds() {
        val endDate = LocalDate.of(2026, 10, 31)
        assertEquals(1, FitnessHistory.datesEndingOn(endDate, 0).size)
        assertEquals(31, FitnessHistory.datesEndingOn(endDate, 500).size)
    }

    // Four watch samples should produce separate lowest, peak, average, and resting values.
    @Test
    fun heartRateSamplesBuildTodaysVitals() {
        val stats = HeartRateDayStats()
            .record(72, resting = true)
            .record(120, resting = false)
            .record(60, resting = true)
            .record(88, resting = false)
        val vitals = stats.toVitals(
            date = LocalDate.of(2026, 10, 31),
            watchConnected = true,
            lastUpdatedAt = 5_000L,
            samples = listOf(
                HeartRateSample(recordedAt = 1_000L, bpm = 72),
                HeartRateSample(recordedAt = 5_000L, bpm = 88),
            ),
        )!!

        assertEquals(120, vitals.peakBpm)
        assertEquals(60, vitals.lowestBpm)
        assertEquals(66, vitals.restingBpm)
        assertEquals(85, vitals.averageBpm)
        assertEquals(LocalDate.of(2026, 10, 31), vitals.date)
        assertEquals(4, vitals.sampleCount)
        assertEquals(true, vitals.watchConnected)
        assertEquals(5_000L, vitals.lastUpdatedAt)
        assertEquals(2, vitals.samples.size)
    }

    // Sensor errors and impossible heart rates must not affect the daily summary.
    @Test
    fun heartRateVitalsRejectInvalidSensorReadings() {
        val stats = HeartRateDayStats()
            .record(0, resting = true)
            .record(29, resting = true)
            .record(241, resting = false)
            .record(80, resting = false)
        val vitals = stats.toVitals(
            date = LocalDate.of(2026, 10, 31),
            watchConnected = true,
            lastUpdatedAt = 1L,
        )!!

        assertEquals(80, vitals.peakBpm)
        assertEquals(80, vitals.lowestBpm)
        assertEquals(80, vitals.averageBpm)
        assertEquals(null, vitals.restingBpm)
        assertEquals(1, vitals.sampleCount)
    }
}
