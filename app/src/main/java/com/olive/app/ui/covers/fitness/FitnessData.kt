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
internal class FitnessStore(private val context: Context, private val accountId: String) {
    internal val preferences = fitnessPreferences(context, accountId)

    // Loads fitness goals and body details saved on this phone.
    fun profile() = FitnessProfile(
        weightKg = preferences.getFloat("weight_kg", 70f),
        heightCm = preferences.getFloat("height_cm", 170f),
        stepGoal = preferences.getInt("step_goal", 8_000),
        calorieGoal = preferences.getInt("calorie_goal", 2_000),
        proteinGoal = preferences.getInt("protein_goal", 120),
        carbohydrateGoal = preferences.getInt("carbohydrate_goal", 250),
        fatGoal = preferences.getInt("fat_goal", 65),
        waterGoalMl = preferences.getInt("water_goal_ml", 2000),
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
        putInt("water_goal_ml", value.waterGoalMl.coerceIn(250, 10000))
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
            waterTodayMl = waterTotal(today),
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
        heartRateUpdatedAt: Long = watchUpdatedAt,
    ): FitnessSnapshot {
        if (watchUpdatedAt <= preferences.getLong(watchReportAtKey(today), 0L)) return snapshot(today, nowMillis)
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
        val watchIsWorn = (heartRate in 30..240 && heartRateUpdatedAt >= watchUpdatedAt - 60_000L) || watchDelta > 0
        val shouldReconcile = previousWatchSteps < 0 || watchIsWorn
        val safeUpdatedAt = watchUpdatedAt.takeIf {
            it in 1L..(nowMillis + 60_000L)
        } ?: nowMillis
        val safeHeartRate = heartRate.takeIf { it in 30..240 }
        val lastHeartRateAt = preferences.getLong(heartRateLastSampleAtKey(today), 0L)
        val safeHeartRateAt = heartRateUpdatedAt.takeIf { it in 1L..(nowMillis + 60_000L) } ?: 0L
        val isNewHeartRateSample = safeHeartRate != null && safeHeartRateAt > lastHeartRateAt
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
                sample = HeartRateSample(safeHeartRateAt, bpm),
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
                putLong(heartRateLastSampleAtKey(today), safeHeartRateAt)
                putString(
                    heartRateGraphSamplesKey(today),
                    encodeHeartRateSamples(updatedGraphSamples.orEmpty()),
                )
            }
        }
        return snapshot(today, nowMillis)
    }

    // Health Connect aggregates overlapping step sources; merge by maximum rather than adding them.
    fun recordHealthConnect(steps: Int, samples: List<HeartRateSample>, today: LocalDate = LocalDate.now()) {
        var stats = heartRateStats(today)
        var graph = heartRateSamples(today)
        var last = preferences.getLong(heartRateLastSampleAtKey(today), 0L)
        samples.sortedBy { it.recordedAt }.forEach { sample ->
            if (sample.recordedAt > last && sample.bpm in 30..240) {
                stats = stats.record(sample.bpm, resting = false)
                graph = appendHeartRateGraphSample(graph, sample)
                last = sample.recordedAt
            }
        }
        preferences.edit {
            putInt(activityStepsKey(today), maxOf(activitySteps(today), steps.coerceIn(0, MAX_DAILY_STEPS)))
            if (samples.isNotEmpty()) {
                putInt(heartRatePeakKey(today), stats.peakBpm)
                putInt(heartRateLowestKey(today), stats.lowestBpm)
                putLong(heartRateTotalKey(today), stats.totalBpm)
                putInt(heartRateSampleCountKey(today), stats.sampleCount)
                putLong(heartRateLastSampleAtKey(today), last)
                putString(heartRateGraphSamplesKey(today), encodeHeartRateSamples(graph))
            }
        }
    }

    fun addWater(milliliters: Int, goalMl: Int, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        require(milliliters in -10000..10000 && milliliters != 0 && goalMl in 250..10000)
        val adjustment = if (milliliters < 0) maxOf(milliliters, -waterTotal(today)) else milliliters
        if (adjustment == 0) return snapshot(today)
        val id = UUID.randomUUID().toString()
        val drink = JSONObject().put("logId", id).put("milliliters", adjustment)
        val key = "water_$today"
        val entries = runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrDefault(JSONArray())
        entries.put(drink)
        preferences.edit { putString(key, entries.toString()); putInt("water_goal_ml", goalMl) }
        FitnessAccountSync.water(context, accountId, today.toString(), id, drink.toString())
        return snapshot(today)
    }

    private fun waterTotal(today: LocalDate): Int = runCatching {
        val entries = JSONArray(preferences.getString("water_$today", "[]"))
        (0 until entries.length()).sumOf { entries.getJSONObject(it).optInt("milliliters").coerceIn(-10000, 10000).toLong() }
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)

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
            servings = servings.coerceIn(0.0001f, 10_000f),
            meal = meal,
            loggedAt = System.currentTimeMillis(),
        )
        saveFoods(today, foods(today) + entry)
        FitnessAccountSync.meal(context, accountId, today.toString(), entry.logId, entry.toJson().toString())
        return snapshot(today)
    }

    fun updateFood(entry: FoodLogEntry, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        require(entry.servings.isFinite() && entry.servings in 0.0001f..10000f)
        val existing = foods(today)
        if (existing.none { it.logId == entry.logId }) return snapshot(today)
        saveFoods(today, existing.map { if (it.logId == entry.logId) entry else it })
        FitnessAccountSync.meal(context, accountId, today.toString(), entry.logId, entry.toJson().toString())
        return snapshot(today)
    }

    // Removes one food entry from today's diary.
    fun removeFood(logId: String, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        saveFoods(today, foods(today).filterNot { it.logId == logId })
        FitnessAccountSync.meal(context, accountId, today.toString(), logId, null)
        return snapshot(today)
    }

    fun recentFoods(today: LocalDate = LocalDate.now()): List<FoodLogEntry> =
        FitnessHistory.datesEndingOn(today, 30).asReversed().asSequence()
            .flatMap { foods(it).asReversed().sortedByDescending(FoodLogEntry::loggedAt).asSequence() }
            .distinctBy { it.food.id }.take(10).toList()

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
    if (loggedAt > 0) put("loggedAt", loggedAt)
    put("food", food.toJson())
}

internal fun NutritionFood.toJson(includeServingOptions: Boolean = false): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("brand", brand)
    put("servingDescription", servingDescription)
    put("calories", calories.toDouble())
    put("proteinGrams", proteinGrams.toDouble())
    put("carbohydrateGrams", carbohydrateGrams.toDouble())
    put("fatGrams", fatGrams.toDouble())
    put("saturatedFatGrams", saturatedFatGrams.toDouble())
    put("transFatGrams", transFatGrams.toDouble())
    put("fiberGrams", fiberGrams.toDouble())
    put("sugarGrams", sugarGrams.toDouble())
    put("sodiumMilligrams", sodiumMilligrams.toDouble())
    put("cholesterolMilligrams", cholesterolMilligrams.toDouble())
    put("potassiumMilligrams", potassiumMilligrams.toDouble())
    put("source", source.name)
    put("barcode", barcode)
    if (includeServingOptions && servingOptions.isNotEmpty()) {
        put("servingOptions", JSONArray().apply {
            servingOptions.forEach { put(it.toJson()) }
        })
    }
}

// Converts a saved food entry into the model used by the nutrition screen.
private fun parseFoodLogEntry(value: JSONObject): FoodLogEntry? = runCatching {
    FoodLogEntry(
        logId = value.getString("logId"),
        servings = value.getDouble("servings").toFloat().coerceIn(0.0001f, 10_000f),
        meal = MealType.valueOf(value.getString("meal")),
        loggedAt = value.optLong("loggedAt"),
        food = checkNotNull(value.getJSONObject("food").toNutritionFood()),
    )
}.getOrNull()

internal fun JSONObject.toNutritionFood(includeServingOptions: Boolean = false): NutritionFood? = runCatching {
    NutritionFood(
        id = getString("id"),
        name = getString("name"),
        brand = optString("brand"),
        servingDescription = optString("servingDescription", "1 serving"),
        calories = safeNutrient("calories"),
        proteinGrams = safeNutrient("proteinGrams"),
        carbohydrateGrams = safeNutrient("carbohydrateGrams"),
        fatGrams = safeNutrient("fatGrams"),
        source = FoodDataSource.valueOf(getString("source")),
        barcode = optString("barcode"),
        saturatedFatGrams = safeNutrient("saturatedFatGrams"),
        transFatGrams = safeNutrient("transFatGrams"),
        fiberGrams = safeNutrient("fiberGrams"),
        sugarGrams = safeNutrient("sugarGrams"),
        sodiumMilligrams = safeNutrient("sodiumMilligrams"),
        cholesterolMilligrams = safeNutrient("cholesterolMilligrams"),
        potassiumMilligrams = safeNutrient("potassiumMilligrams"),
        servingOptions = if (includeServingOptions) readNutritionFoods(optJSONArray("servingOptions"), false)
            else emptyList(),
    )
}.getOrNull()

internal fun readNutritionFoods(array: JSONArray?, includeServingOptions: Boolean = true): List<NutritionFood> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull {
        array.optJSONObject(it)?.toNutritionFood(includeServingOptions)
    }

// Reads a nutrient value and replaces invalid numbers with zero.
private fun JSONObject.safeNutrient(name: String) =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

// Formats the food serving amount shown in the diary.
internal fun Float.displayAmount(): String = when {
    this > 0f && this < 1f ->
        java.math.BigDecimal(toString()).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    this == roundToInt().toFloat() -> roundToInt().toString()
    else -> String.format(Locale.ROOT, "%.1f", this)
}

internal data class FitnessProfile(
    val weightKg: Float = 70f,
    val heightCm: Float = 170f,
    val stepGoal: Int = 8_000,
    val calorieGoal: Int = 2_000,
    val proteinGoal: Int = 120,
    val carbohydrateGoal: Int = 250,
    val fatGoal: Int = 65,
    val waterGoalMl: Int = 2000,
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
    val servingOptions: List<NutritionFood> = emptyList(),
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
    val loggedAt: Long = 0,
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
    val waterTodayMl: Int = 0,
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
