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

// Each cover account receives separate local fitness and meal history.
internal class FitnessStore(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences(
        "fitness_cover_${accountId.hashCode()}",
        Context.MODE_PRIVATE,
    )

    // Loads fitness goals and body details saved on this phone.
    fun profile() = FitnessProfile(
        weightKg = preferences.getFloat("weight_kg", 70f),
        heightCm = preferences.getFloat("height_cm", 170f),
        stepGoal = preferences.getInt("step_goal", 8_000),
        calorieGoal = preferences.getInt("calorie_goal", 2_000),
        proteinGoal = preferences.getInt("protein_goal", 120),
        carbohydrateGoal = preferences.getInt("carbohydrate_goal", 250),
        fatGoal = preferences.getInt("fat_goal", 65),
    )

    // Saves calorie, macro, activity, and body goals for the fitness cover.
    fun saveProfile(value: FitnessProfile) = preferences.edit {
        putFloat("weight_kg", value.weightKg.coerceIn(30f, 300f))
        putFloat("height_cm", value.heightCm.coerceIn(100f, 230f))
        putInt("step_goal", value.stepGoal.coerceIn(500, 100_000))
        putInt("calorie_goal", value.calorieGoal.coerceIn(500, 10_000))
        putInt("protein_goal", value.proteinGoal.coerceIn(10, 1_000))
        putInt("carbohydrate_goal", value.carbohydrateGoal.coerceIn(10, 2_000))
        putInt("fat_goal", value.fatGoal.coerceIn(10, 1_000))
    }

    // Loads the snapshot needed by the fitness cover.
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
        val difference = if (previous !in 0..totalSinceBoot) {
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
            // Add only new phone steps since the last sensor reading.
            if (phoneDelta > 0) {
                putInt(
                    phoneStepsKey(today),
                    (phoneSteps(today) + phoneDelta).coerceAtMost(MAX_DAILY_STEPS),
                )
            }
            // Ignore duplicate or older step readings.
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
        val watchDelta = if (previousWatchSteps !in 0..safeWatchSteps) {
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

    // Adds a selected food and its nutrients to today's diary.
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

    // Removes one food entry from today's diary.
    fun removeFood(logId: String, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        saveFoods(today, foods(today).filterNot { it.logId == logId })
        return snapshot(today)
    }

    // Reads today's steps from Health Connect for the fitness dashboard.
    private fun activitySteps(date: LocalDate): Int = preferences.getInt(
        activityStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    // Combines phone step readings without counting sensor resets twice.
    private fun phoneSteps(date: LocalDate): Int = preferences.getInt(
        phoneStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    // Gives the fitness cover the phone fallback since watch it needs.
    private fun phoneFallbackSinceWatch(date: LocalDate) =
        preferences.getInt(phoneFallbackKey(date), 0)

    // Returns the heart rate stats used by the fitness cover.
    private fun heartRateStats(date: LocalDate) = HeartRateDayStats(
        peakBpm = preferences.getInt(heartRatePeakKey(date), 0),
        lowestBpm = preferences.getInt(heartRateLowestKey(date), 0),
        totalBpm = preferences.getLong(heartRateTotalKey(date), 0L),
        sampleCount = preferences.getInt(heartRateSampleCountKey(date), 0),
        restingTotalBpm = preferences.getLong(heartRateRestingTotalKey(date), 0L),
        restingSampleCount = preferences.getInt(heartRateRestingSampleCountKey(date), 0),
    )

    // Reads recent heart-rate samples for the fitness vitals graph.
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

    // Fitness cover: Converts heart rate samples.
    private fun encodeHeartRateSamples(samples: List<HeartRateSample>) = JSONArray().apply {
        samples.forEach { sample ->
            put(JSONObject().apply {
                put("recordedAt", sample.recordedAt)
                put("bpm", sample.bpm)
            })
        }
    }.toString()

    // Keeps the saved activity steps key name in one place for the fitness cover.
    private fun activityStepsKey(date: LocalDate) = "activity_steps_$date"
    // Keeps the saved phone steps key name in one place for the fitness cover.
    private fun phoneStepsKey(date: LocalDate) = "phone_steps_$date"
    // Watches steps key for fitness cover.
    private fun watchStepsKey(date: LocalDate) = "watch_steps_$date"
    // Keeps the saved phone fallback key name in one place for the fitness cover.
    private fun phoneFallbackKey(date: LocalDate) = "phone_fallback_since_watch_$date"
    // Watches active at key for fitness cover.
    private fun watchActiveAtKey(date: LocalDate) = "watch_active_at_$date"
    // Fitness cover: Watches report at key.
    private fun watchReportAtKey(date: LocalDate) = "watch_report_at_$date"
    // Keeps the saved heart rate peak key name in one place for the fitness cover.
    private fun heartRatePeakKey(date: LocalDate) = "heart_rate_peak_$date"
    // Keeps the saved heart rate lowest key name in one place for the fitness cover.
    private fun heartRateLowestKey(date: LocalDate) = "heart_rate_lowest_$date"
    // Keeps the saved heart rate total key name in one place for the fitness cover.
    private fun heartRateTotalKey(date: LocalDate) = "heart_rate_total_$date"
    // Keeps the saved heart rate sample count key name in one place for the fitness cover.
    private fun heartRateSampleCountKey(date: LocalDate) = "heart_rate_sample_count_$date"
    // Keeps the saved heart rate resting total key name in one place for the fitness cover.
    private fun heartRateRestingTotalKey(date: LocalDate) = "heart_rate_resting_total_$date"
    // Keeps the saved heart rate resting sample count key name in one place for the fitness cover.
    private fun heartRateRestingSampleCountKey(date: LocalDate) =
        "heart_rate_resting_sample_count_$date"
    // Keeps the saved heart rate last sample at key name in one place for the fitness cover.
    private fun heartRateLastSampleAtKey(date: LocalDate) = "heart_rate_last_sample_at_$date"
    // Keeps the saved heart rate graph samples key name in one place for the fitness cover.
    private fun heartRateGraphSamplesKey(date: LocalDate) = "heart_rate_graph_samples_$date"
    // Keeps the saved legacy steps key name in one place for the fitness cover.
    private fun legacyStepsKey(date: LocalDate) = "steps_$date"

    // Loads food-diary entries for the selected day.
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

    // Saves today's food diary on the phone.
    private fun saveFoods(date: LocalDate, foods: List<FoodLogEntry>) {
        val encoded = JSONArray().apply {
            foods.takeLast(250).forEach { put(it.toJson()) }
        }.toString()
        preferences.edit { putString(foodKey(date), encoded) }
    }

    // Keeps the saved food key name in one place for the fitness cover.
    private fun foodKey(date: LocalDate) = "foods_$date"

    private companion object {
        const val MAX_DAILY_STEPS = 500_000
    }
}

// Fitness cover: Converts this record into JSON.
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

// Converts a saved food entry into the model used by the nutrition screen.
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

// Reads a nutrient value and replaces invalid numbers with zero.
private fun JSONObject.safeNutrient(name: String) =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

// Formats the food serving amount shown in the diary.
internal fun Float.displayAmount(): String = if (this == roundToInt().toFloat()) {
    roundToInt().toString()
} else {
    String.format(Locale.ROOT, "%.1f", this)
}
