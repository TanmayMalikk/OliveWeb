// Fitness cover users: stores step goals, nutrition goals, and the foods logged for each day.
package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
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
    FATSECRET("FatSecret"),
    OPEN_FOOD_FACTS("Open Food Facts"),
    MANUAL("Manual entry"),
}

internal enum class MealType(val displayName: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack"),
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

internal data class HeartRateSample(
    val recordedAt: Long,
    val bpm: Int,
)

internal data class HeartRateDayStats(
    val peakBpm: Int = 0,
    val lowestBpm: Int = 0,
    val totalBpm: Long = 0L,
    val sampleCount: Int = 0,
    val restingTotalBpm: Long = 0L,
    val restingSampleCount: Int = 0,
) {
    // Fitness watch users: adds one valid watch reading to today's heart-rate summary.
    fun record(heartRate: Int, resting: Boolean): HeartRateDayStats {
        if (heartRate !in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM) return this
        return copy(
            peakBpm = maxOf(peakBpm, heartRate),
            lowestBpm = if (lowestBpm == 0) heartRate else minOf(lowestBpm, heartRate),
            totalBpm = totalBpm + heartRate,
            sampleCount = sampleCount + 1,
            restingTotalBpm = restingTotalBpm + if (resting) heartRate else 0,
            restingSampleCount = restingSampleCount + if (resting) 1 else 0,
        )
    }

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
            restingBpm = restingSampleCount.takeIf { it > 0 }?.let {
                (restingTotalBpm.toDouble() / it).roundToInt()
            },
            averageBpm = (totalBpm.toDouble() / sampleCount).roundToInt(),
            sampleCount = sampleCount,
            watchConnected = watchConnected,
            lastUpdatedAt = lastUpdatedAt,
            samples = samples,
        )
    }

    private companion object {
        const val MIN_HEART_RATE_BPM = 30
        const val MAX_HEART_RATE_BPM = 240
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
        NutritionTotals(
            calories = total.calories + entry.food.calories * entry.servings,
            proteinGrams = total.proteinGrams + entry.food.proteinGrams * entry.servings,
            carbohydrateGrams = total.carbohydrateGrams +
                    entry.food.carbohydrateGrams * entry.servings,
            fatGrams = total.fatGrams + entry.food.fatGrams * entry.servings,
            saturatedFatGrams = total.saturatedFatGrams +
                    entry.food.saturatedFatGrams * entry.servings,
            transFatGrams = total.transFatGrams + entry.food.transFatGrams * entry.servings,
            fiberGrams = total.fiberGrams + entry.food.fiberGrams * entry.servings,
            sugarGrams = total.sugarGrams + entry.food.sugarGrams * entry.servings,
            sodiumMilligrams = total.sodiumMilligrams +
                    entry.food.sodiumMilligrams * entry.servings,
            cholesterolMilligrams = total.cholesterolMilligrams +
                    entry.food.cholesterolMilligrams * entry.servings,
            potassiumMilligrams = total.potassiumMilligrams +
                    entry.food.potassiumMilligrams * entry.servings,
        )
    }
}

// Fitness cover users: combines one saved day's activity and nutrition for history charts.
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
    // Returns oldest-to-newest dates so every graph uses the same horizontal order.
    fun datesEndingOn(endDate: LocalDate, days: Int): List<LocalDate> =
        (days.coerceIn(1, 31) - 1 downTo 0).map { offset ->
            endDate.minusDays(offset.toLong())
        }
}

// These formulas keep estimates consistent between the UI and unit tests.
internal object FitnessMath {
    fun strideMeters(heightCm: Float) = (heightCm.coerceIn(100f, 230f) * 0.413f) / 100f

    fun distanceKm(steps: Int, heightCm: Float) =
        steps.coerceAtLeast(0) * strideMeters(heightCm) / 1_000f

    fun walkingCalories(steps: Int, profile: FitnessProfile) =
        distanceKm(steps, profile.heightCm) *
                profile.weightKg.coerceIn(30f, 300f) * 0.75f
}

// Fitness cover users: decides which sensor delta counts without adding the same walk twice.
internal object StepSourcePolicy {
    const val WATCH_ACTIVE_WINDOW_MS = 120_000L

    fun watchIsActive(lastWatchActivityAt: Long, nowMillis: Long): Boolean =
        lastWatchActivityAt > 0L &&
            nowMillis >= lastWatchActivityAt &&
            nowMillis - lastWatchActivityAt <= WATCH_ACTIVE_WINDOW_MS

    fun phoneDeltaToAdd(phoneDelta: Int, lastWatchActivityAt: Long, nowMillis: Long): Int =
        if (watchIsActive(lastWatchActivityAt, nowMillis)) 0 else phoneDelta.coerceAtLeast(0)

    fun watchDeltaToAdd(watchDelta: Int, phoneFallbackSinceWatch: Int): Int =
        (watchDelta.coerceAtLeast(0) - phoneFallbackSinceWatch.coerceAtLeast(0)).coerceAtLeast(0)
}

// Each cover account receives separate local fitness and meal history.
internal class FitnessStore(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences(
        "fitness_cover_${accountId.hashCode()}",
        Context.MODE_PRIVATE,
    )

    fun profile() = FitnessProfile(
        weightKg = preferences.getFloat("weight_kg", 70f),
        heightCm = preferences.getFloat("height_cm", 170f),
        stepGoal = preferences.getInt("step_goal", 8_000),
        calorieGoal = preferences.getInt("calorie_goal", 2_000),
        proteinGoal = preferences.getInt("protein_goal", 120),
        carbohydrateGoal = preferences.getInt("carbohydrate_goal", 250),
        fatGoal = preferences.getInt("fat_goal", 65),
    )

    fun saveProfile(value: FitnessProfile) = preferences.edit {
        putFloat("weight_kg", value.weightKg.coerceIn(30f, 300f))
        putFloat("height_cm", value.heightCm.coerceIn(100f, 230f))
        putInt("step_goal", value.stepGoal.coerceIn(500, 100_000))
        putInt("calorie_goal", value.calorieGoal.coerceIn(500, 10_000))
        putInt("protein_goal", value.proteinGoal.coerceIn(10, 1_000))
        putInt("carbohydrate_goal", value.carbohydrateGoal.coerceIn(10, 2_000))
        putInt("fat_goal", value.fatGoal.coerceIn(10, 1_000))
    }

    fun snapshot(
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): FitnessSnapshot {
        val watchConnected = StepSourcePolicy.watchIsActive(
            preferences.getLong(watchReportAtKey(today), 0L),
            nowMillis,
        )
        val recentDates = FitnessHistory.datesEndingOn(today, 14)
        val watchSeenWithinFourteenDays = recentDates.any { date ->
            preferences.getLong(watchReportAtKey(date), 0L) > 0L
        }
        val heartRateHistory = recentDates.mapNotNull { date ->
            heartRateStats(date).toVitals(
                date = date,
                watchConnected = watchConnected,
                lastUpdatedAt = preferences.getLong(heartRateLastSampleAtKey(date), 0L),
                samples = heartRateSamples(date),
            )
        }
        val recentVitals = heartRateHistory.lastOrNull()
        return FitnessSnapshot(
            profile = profile(),
            stepsToday = activitySteps(today),
            foodsToday = foods(today),
            heartRateVitals = recentVitals,
            heartRateHistory = heartRateHistory,
            watchConnected = watchConnected,
            watchSeenWithinFourteenDays = watchSeenWithinFourteenDays,
        )
    }

    // Fitness cover users: loads up to 31 saved days for activity and nutrition graphs.
    fun history(
        days: Int = 14,
        endDate: LocalDate = LocalDate.now(),
    ): List<FitnessHistoryDay> {
        val currentProfile = profile()
        return FitnessHistory.datesEndingOn(endDate, days).map { date ->
            val dailySnapshot = FitnessSnapshot(
                profile = currentProfile,
                stepsToday = activitySteps(date),
                foodsToday = foods(date),
            )
            FitnessHistoryDay(
                date = date,
                steps = dailySnapshot.stepsToday,
                distanceKm = FitnessMath.distanceKm(
                    dailySnapshot.stepsToday,
                    currentProfile.heightCm,
                ),
                caloriesBurned = FitnessMath.walkingCalories(
                    dailySnapshot.stepsToday,
                    currentProfile,
                ),
                caloriesEaten = dailySnapshot.nutrition.calories,
                proteinGrams = dailySnapshot.nutrition.proteinGrams,
                carbohydrateGrams = dailySnapshot.nutrition.carbohydrateGrams,
                fatGrams = dailySnapshot.nutrition.fatGrams,
            )
        }
    }

    // Phone fitness users: counts phone steps only while a worn watch is not actively reporting.
    fun recordSensorValue(
        totalSinceBoot: Int,
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): FitnessSnapshot {
        val previous = preferences.getInt("last_sensor_value", -1)
        val difference = if (previous < 0 || totalSinceBoot < previous) {
            0
        } else {
            totalSinceBoot - previous
        }
        val phoneDelta = difference.coerceAtLeast(0)
        val deltaToAdd = StepSourcePolicy.phoneDeltaToAdd(
            phoneDelta = phoneDelta,
            lastWatchActivityAt = preferences.getLong(watchActiveAtKey(today), 0L),
            nowMillis = nowMillis,
        )
        preferences.edit {
            putInt("last_sensor_value", totalSinceBoot.coerceAtLeast(0))
            if (phoneDelta > 0) {
                putInt(
                    phoneStepsKey(today),
                    (phoneSteps(today) + phoneDelta).coerceAtMost(MAX_DAILY_STEPS),
                )
            }
            if (deltaToAdd > 0) {
                putInt(
                    activityStepsKey(today),
                    (activitySteps(today) + deltaToAdd).coerceAtMost(MAX_DAILY_STEPS),
                )
                putInt(
                    phoneFallbackKey(today),
                    (phoneFallbackSinceWatch(today) + deltaToAdd).coerceAtMost(MAX_DAILY_STEPS),
                )
            }
        }
        return snapshot(today)
    }

    // Watch fitness users: makes a worn watch primary and removes phone fallback overlap.
    fun recordWatchActivity(
        watchSteps: Int,
        heartRate: Int,
        watchUpdatedAt: Long,
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): FitnessSnapshot {
        val safeWatchSteps = watchSteps.coerceIn(0, MAX_DAILY_STEPS)
        val previousWatchSteps = preferences.getInt(watchStepsKey(today), -1)
        val watchDelta = if (previousWatchSteps < 0 || safeWatchSteps < previousWatchSteps) {
            0
        } else {
            safeWatchSteps - previousWatchSteps
        }
        val phoneFallback = phoneFallbackSinceWatch(today)
        val watchDeltaToAdd = StepSourcePolicy.watchDeltaToAdd(watchDelta, phoneFallback)
        val firstWatchTotal = if (previousWatchSteps < 0) {
            maxOf(activitySteps(today), safeWatchSteps)
        } else {
            activitySteps(today)
        }
        val watchIsWorn = heartRate > 0 || watchDelta > 0
        val shouldReconcile = previousWatchSteps < 0 || watchIsWorn
        val safeUpdatedAt = watchUpdatedAt.takeIf {
            it in 1L..(nowMillis + 60_000L)
        } ?: nowMillis
        val safeHeartRate = heartRate.takeIf { it in 30..240 }
        val lastHeartRateAt = preferences.getLong(heartRateLastSampleAtKey(today), 0L)
        val isNewHeartRateSample = safeHeartRate != null && safeUpdatedAt > lastHeartRateAt
        val updatedHeartRateStats = if (isNewHeartRateSample) {
            heartRateStats(today).record(
                heartRate = safeHeartRate,
                resting = previousWatchSteps >= 0 && watchDelta == 0,
            )
        } else {
            null
        }
        val updatedGraphSamples = safeHeartRate?.takeIf { isNewHeartRateSample }?.let { bpm ->
            appendHeartRateGraphSample(
                existing = heartRateSamples(today),
                sample = HeartRateSample(safeUpdatedAt, bpm),
            )
        }

        preferences.edit {
            putInt(watchStepsKey(today), safeWatchSteps)
            putInt(
                activityStepsKey(today),
                (firstWatchTotal + if (watchIsWorn) watchDeltaToAdd else 0)
                    .coerceAtMost(MAX_DAILY_STEPS),
            )
            if (shouldReconcile) putInt(phoneFallbackKey(today), 0)
            if (watchIsWorn) putLong(watchActiveAtKey(today), safeUpdatedAt)
            putLong(watchReportAtKey(today), safeUpdatedAt)
            if (updatedHeartRateStats != null) {
                putInt(heartRatePeakKey(today), updatedHeartRateStats.peakBpm)
                putInt(heartRateLowestKey(today), updatedHeartRateStats.lowestBpm)
                putLong(heartRateTotalKey(today), updatedHeartRateStats.totalBpm)
                putInt(heartRateSampleCountKey(today), updatedHeartRateStats.sampleCount)
                putLong(heartRateRestingTotalKey(today), updatedHeartRateStats.restingTotalBpm)
                putInt(
                    heartRateRestingSampleCountKey(today),
                    updatedHeartRateStats.restingSampleCount,
                )
                putLong(heartRateLastSampleAtKey(today), safeUpdatedAt)
                putString(
                    heartRateGraphSamplesKey(today),
                    encodeHeartRateSamples(updatedGraphSamples.orEmpty()),
                )
            }
        }
        return snapshot(today, nowMillis)
    }

    fun addFood(
        food: NutritionFood,
        servings: Float,
        meal: MealType,
        today: LocalDate = LocalDate.now(),
    ): FitnessSnapshot {
        val entry = FoodLogEntry(
            logId = UUID.randomUUID().toString(),
            food = food,
            servings = servings.coerceIn(0.05f, 100f),
            meal = meal,
        )
        saveFoods(today, foods(today) + entry)
        return snapshot(today)
    }

    fun removeFood(logId: String, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        saveFoods(today, foods(today).filterNot { it.logId == logId })
        return snapshot(today)
    }

    private fun activitySteps(date: LocalDate): Int = preferences.getInt(
        activityStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    private fun phoneSteps(date: LocalDate): Int = preferences.getInt(
        phoneStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    private fun phoneFallbackSinceWatch(date: LocalDate) =
        preferences.getInt(phoneFallbackKey(date), 0)

    private fun heartRateStats(date: LocalDate) = HeartRateDayStats(
        peakBpm = preferences.getInt(heartRatePeakKey(date), 0),
        lowestBpm = preferences.getInt(heartRateLowestKey(date), 0),
        totalBpm = preferences.getLong(heartRateTotalKey(date), 0L),
        sampleCount = preferences.getInt(heartRateSampleCountKey(date), 0),
        restingTotalBpm = preferences.getLong(heartRateRestingTotalKey(date), 0L),
        restingSampleCount = preferences.getInt(heartRateRestingSampleCountKey(date), 0),
    )

    private fun heartRateSamples(date: LocalDate): List<HeartRateSample> {
        val encoded = preferences.getString(heartRateGraphSamplesKey(date), null) ?: return emptyList()
        return runCatching {
            val values = JSONArray(encoded)
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    val bpm = value.optInt("bpm")
                    val recordedAt = value.optLong("recordedAt")
                    if (bpm in 30..240 && recordedAt > 0L) {
                        add(HeartRateSample(recordedAt, bpm))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    // Fitness vitals graphs: keeps at most one point per minute and one day of points.
    private fun appendHeartRateGraphSample(
        existing: List<HeartRateSample>,
        sample: HeartRateSample,
    ): List<HeartRateSample> {
        val lastSample = existing.lastOrNull()
        if (lastSample != null && sample.recordedAt - lastSample.recordedAt < 60_000L) {
            return existing.dropLast(1) + sample
        }
        return (existing + sample).takeLast(1_440)
    }

    private fun encodeHeartRateSamples(samples: List<HeartRateSample>) = JSONArray().apply {
        samples.forEach { sample ->
            put(JSONObject().apply {
                put("recordedAt", sample.recordedAt)
                put("bpm", sample.bpm)
            })
        }
    }.toString()

    private fun activityStepsKey(date: LocalDate) = "activity_steps_$date"
    private fun phoneStepsKey(date: LocalDate) = "phone_steps_$date"
    private fun watchStepsKey(date: LocalDate) = "watch_steps_$date"
    private fun phoneFallbackKey(date: LocalDate) = "phone_fallback_since_watch_$date"
    private fun watchActiveAtKey(date: LocalDate) = "watch_active_at_$date"
    private fun watchReportAtKey(date: LocalDate) = "watch_report_at_$date"
    private fun heartRatePeakKey(date: LocalDate) = "heart_rate_peak_$date"
    private fun heartRateLowestKey(date: LocalDate) = "heart_rate_lowest_$date"
    private fun heartRateTotalKey(date: LocalDate) = "heart_rate_total_$date"
    private fun heartRateSampleCountKey(date: LocalDate) = "heart_rate_sample_count_$date"
    private fun heartRateRestingTotalKey(date: LocalDate) = "heart_rate_resting_total_$date"
    private fun heartRateRestingSampleCountKey(date: LocalDate) =
        "heart_rate_resting_sample_count_$date"
    private fun heartRateLastSampleAtKey(date: LocalDate) = "heart_rate_last_sample_at_$date"
    private fun heartRateGraphSamplesKey(date: LocalDate) = "heart_rate_graph_samples_$date"
    private fun legacyStepsKey(date: LocalDate) = "steps_$date"

    private fun foods(date: LocalDate): List<FoodLogEntry> {
        val encoded = preferences.getString(foodKey(date), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    parseFoodLogEntry(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveFoods(date: LocalDate, foods: List<FoodLogEntry>) {
        val encoded = JSONArray().apply {
            foods.takeLast(250).forEach { put(it.toJson()) }
        }.toString()
        preferences.edit { putString(foodKey(date), encoded) }
    }

    private fun foodKey(date: LocalDate) = "foods_$date"

    private companion object {
        const val MAX_DAILY_STEPS = 500_000
    }
}

private fun FoodLogEntry.toJson() = JSONObject().apply {
    put("logId", logId)
    put("servings", servings.toDouble())
    put("meal", meal.name)
    put("food", JSONObject().apply {
        put("id", food.id)
        put("name", food.name)
        put("brand", food.brand)
        put("servingDescription", food.servingDescription)
        put("calories", food.calories.toDouble())
        put("proteinGrams", food.proteinGrams.toDouble())
        put("carbohydrateGrams", food.carbohydrateGrams.toDouble())
        put("fatGrams", food.fatGrams.toDouble())
        put("saturatedFatGrams", food.saturatedFatGrams.toDouble())
        put("transFatGrams", food.transFatGrams.toDouble())
        put("fiberGrams", food.fiberGrams.toDouble())
        put("sugarGrams", food.sugarGrams.toDouble())
        put("sodiumMilligrams", food.sodiumMilligrams.toDouble())
        put("cholesterolMilligrams", food.cholesterolMilligrams.toDouble())
        put("potassiumMilligrams", food.potassiumMilligrams.toDouble())
        put("source", food.source.name)
        put("barcode", food.barcode)
    })
}

private fun parseFoodLogEntry(value: JSONObject): FoodLogEntry? = runCatching {
    val food = value.getJSONObject("food")
    FoodLogEntry(
        logId = value.getString("logId"),
        servings = value.getDouble("servings").toFloat().coerceIn(0.05f, 100f),
        meal = MealType.valueOf(value.getString("meal")),
        food = NutritionFood(
            id = food.getString("id"),
            name = food.getString("name"),
            brand = food.optString("brand"),
            servingDescription = food.optString("servingDescription", "1 serving"),
            calories = food.optDouble("calories").toFloat().coerceAtLeast(0f),
            proteinGrams = food.optDouble("proteinGrams").toFloat().coerceAtLeast(0f),
            carbohydrateGrams = food.optDouble("carbohydrateGrams").toFloat().coerceAtLeast(0f),
            fatGrams = food.optDouble("fatGrams").toFloat().coerceAtLeast(0f),
            source = FoodDataSource.valueOf(food.getString("source")),
            barcode = food.optString("barcode"),
            saturatedFatGrams = food.safeNutrient("saturatedFatGrams"),
            transFatGrams = food.safeNutrient("transFatGrams"),
            fiberGrams = food.safeNutrient("fiberGrams"),
            sugarGrams = food.safeNutrient("sugarGrams"),
            sodiumMilligrams = food.safeNutrient("sodiumMilligrams"),
            cholesterolMilligrams = food.safeNutrient("cholesterolMilligrams"),
            potassiumMilligrams = food.safeNutrient("potassiumMilligrams"),
        ),
    )
}.getOrNull()

private fun JSONObject.safeNutrient(name: String) =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

internal fun Float.displayAmount(): String = if (this == roundToInt().toFloat()) {
    roundToInt().toString()
} else {
    String.format(Locale.ROOT, "%.1f", this)
}
