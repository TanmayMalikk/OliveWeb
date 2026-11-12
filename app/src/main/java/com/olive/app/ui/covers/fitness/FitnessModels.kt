// Fitness cover users: defines activity, nutrition, and heart-rate values without storing them.
package com.olive.app.ui.covers.fitness

import java.time.LocalDate
import kotlin.math.roundToInt

internal data class FitnessProfile(
    val weightKg: Float = 70f,
    val heightCm: Float = 170f,
    val stepGoal: Int = 8_000,
    val calorieGoal: Int = 2_000,
    val proteinGoal: Int = 120,
    val carbohydrateGoal: Int = 250,
    val fatGoal: Int = 65,
)

internal data class NutritionFood(
    val id: String,
    val name: String,
    val brand: String,
    val servingDescription: String,
    val calories: Float,
    val proteinGrams: Float,
    val carbohydrateGrams: Float,
    val fatGrams: Float,
    val source: FoodDataSource,
    val barcode: String = "",
    val saturatedFatGrams: Float = 0f,
    val transFatGrams: Float = 0f,
    val fiberGrams: Float = 0f,
    val sugarGrams: Float = 0f,
    val sodiumMilligrams: Float = 0f,
    val cholesterolMilligrams: Float = 0f,
    val potassiumMilligrams: Float = 0f,
)

internal enum class FoodDataSource(val displayName: String) {
    FATSECRET("FatSecret"), OPEN_FOOD_FACTS("Open Food Facts"), MANUAL("Manual entry")
}

internal enum class MealType(val displayName: String) {
    BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACK("Snack")
}

internal data class FoodLogEntry(
    val logId: String,
    val food: NutritionFood,
    val servings: Float,
    val meal: MealType,
)

internal data class NutritionTotals(
    val calories: Float = 0f,
    val proteinGrams: Float = 0f,
    val carbohydrateGrams: Float = 0f,
    val fatGrams: Float = 0f,
    val saturatedFatGrams: Float = 0f,
    val transFatGrams: Float = 0f,
    val fiberGrams: Float = 0f,
    val sugarGrams: Float = 0f,
    val sodiumMilligrams: Float = 0f,
    val cholesterolMilligrams: Float = 0f,
    val potassiumMilligrams: Float = 0f,
)

internal data class HeartRateVitals(
    val date: LocalDate,
    val peakBpm: Int,
    val lowestBpm: Int,
    val restingBpm: Int?,
    val averageBpm: Int,
    val sampleCount: Int,
    val watchConnected: Boolean,
    val lastUpdatedAt: Long,
    val samples: List<HeartRateSample> = emptyList(),
)

internal data class HeartRateSample(val recordedAt: Long, val bpm: Int)

internal data class HeartRateDayStats(
    val peakBpm: Int = 0,
    val lowestBpm: Int = 0,
    val totalBpm: Long = 0L,
    val sampleCount: Int = 0,
    val restingTotalBpm: Long = 0L,
    val restingSampleCount: Int = 0,
) {
    // Fitness watch users: adds one medically plausible watch reading to today's summary.
    fun record(heartRate: Int, resting: Boolean): HeartRateDayStats {
        if (heartRate !in 30..240) return this
        return copy(
            peakBpm = maxOf(peakBpm, heartRate),
            lowestBpm = if (lowestBpm == 0) heartRate else minOf(lowestBpm, heartRate),
            totalBpm = totalBpm + heartRate,
            sampleCount = sampleCount + 1,
            restingTotalBpm = restingTotalBpm + if (resting) heartRate else 0,
            restingSampleCount = restingSampleCount + if (resting) 1 else 0,
        )
    }

    // Fitness history: converts saved totals into values the daily vitals screen can display.
    fun toVitals(
        date: LocalDate,
        watchConnected: Boolean,
        lastUpdatedAt: Long,
        samples: List<HeartRateSample> = emptyList(),
    ): HeartRateVitals? {
        if (sampleCount <= 0) return null
        return HeartRateVitals(
            date = date,
            peakBpm = peakBpm,
            lowestBpm = lowestBpm,
            restingBpm = restingSampleCount.takeIf { it > 0 }
                ?.let { (restingTotalBpm.toDouble() / it).roundToInt() },
            averageBpm = (totalBpm.toDouble() / sampleCount).roundToInt(),
            sampleCount = sampleCount,
            watchConnected = watchConnected,
            lastUpdatedAt = lastUpdatedAt,
            samples = samples,
        )
    }
}

internal data class FitnessSnapshot(
    val profile: FitnessProfile,
    val stepsToday: Int,
    val foodsToday: List<FoodLogEntry>,
    val heartRateVitals: HeartRateVitals? = null,
    val heartRateHistory: List<HeartRateVitals> = emptyList(),
    val watchConnected: Boolean = false,
    val watchSeenWithinFourteenDays: Boolean = false,
) {
    val nutrition = foodsToday.fold(NutritionTotals()) { total, entry ->
        val servings = entry.servings
        val food = entry.food
        NutritionTotals(
            calories = total.calories + food.calories * servings,
            proteinGrams = total.proteinGrams + food.proteinGrams * servings,
            carbohydrateGrams = total.carbohydrateGrams + food.carbohydrateGrams * servings,
            fatGrams = total.fatGrams + food.fatGrams * servings,
            saturatedFatGrams = total.saturatedFatGrams + food.saturatedFatGrams * servings,
            transFatGrams = total.transFatGrams + food.transFatGrams * servings,
            fiberGrams = total.fiberGrams + food.fiberGrams * servings,
            sugarGrams = total.sugarGrams + food.sugarGrams * servings,
            sodiumMilligrams = total.sodiumMilligrams + food.sodiumMilligrams * servings,
            cholesterolMilligrams = total.cholesterolMilligrams + food.cholesterolMilligrams * servings,
            potassiumMilligrams = total.potassiumMilligrams + food.potassiumMilligrams * servings,
        )
    }
}

internal data class FitnessHistoryDay(
    val date: LocalDate,
    val steps: Int,
    val distanceKm: Float,
    val caloriesBurned: Float,
    val caloriesEaten: Float,
    val proteinGrams: Float,
    val carbohydrateGrams: Float,
    val fatGrams: Float,
)

internal object FitnessHistory {
    // Creates the ordered date list used by the 14-day fitness graphs.
    fun datesEndingOn(endDate: LocalDate, days: Int): List<LocalDate> =
        (days.coerceIn(1, 31) - 1 downTo 0).map { endDate.minusDays(it.toLong()) }
}

internal object FitnessMath {
    // Gives the fitness cover the stride meters it needs.
    fun strideMeters(heightCm: Float) = (heightCm.coerceIn(100f, 230f) * 0.413f) / 100f
    // Estimates walking distance from the saved step count.
    fun distanceKm(steps: Int, heightCm: Float) =
        steps.coerceAtLeast(0) * strideMeters(heightCm) / 1_000f
    // Returns the walking calories used by the fitness cover.
    fun walkingCalories(steps: Int, profile: FitnessProfile) =
        distanceKm(steps, profile.heightCm) * profile.weightKg.coerceIn(30f, 300f) * 0.75f
}

internal object StepSourcePolicy {
    const val WATCH_ACTIVE_WINDOW_MS = 120_000L

    // Watches is active for fitness cover.
    fun watchIsActive(lastWatchActivityAt: Long, nowMillis: Long): Boolean =
        lastWatchActivityAt in 1..nowMillis &&
            nowMillis - lastWatchActivityAt <= WATCH_ACTIVE_WINDOW_MS

    // Adds only new phone steps so watch and phone totals are not counted twice.
    fun phoneDeltaToAdd(phoneDelta: Int, lastWatchActivityAt: Long, nowMillis: Long): Int =
        if (watchIsActive(lastWatchActivityAt, nowMillis)) 0 else phoneDelta.coerceAtLeast(0)

    // Fitness cover: Watches delta to add.
    fun watchDeltaToAdd(watchDelta: Int, phoneFallbackSinceWatch: Int): Int =
        (watchDelta.coerceAtLeast(0) - phoneFallbackSinceWatch.coerceAtLeast(0)).coerceAtLeast(0)
}
