// Fitness cover users: stores step goals, nutrition goals, and the foods logged for each day.
package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.core.content.edit
import com.olive.app.data.network.nonNegativeFloat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// Each cover account receives separate local fitness and meal history.
internal class FitnessStore(private val context: Context, private val accountId: String) {
    internal val preferences = fitnessPreferences(context, accountId)

    /**
     * Loads fitness goals and body details saved on this phone.
     *
     * @return The resulting profile.
     */
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

    /**
     * Saves calorie, macro, activity, and body goals for the fitness cover.
     *
     * @param value Input value to store, parse, or format.
     * @param coachUpdateDate Optional coach update date supplied to [saveProfile].
     */
    fun saveProfile(value: FitnessProfile, coachUpdateDate: LocalDate? = null) = preferences.edit {
        putFloat("weight_kg", value.weightKg.coerceIn(30f, 300f))
        putFloat("height_cm", value.heightCm.coerceIn(100f, 230f))
        putInt("step_goal", value.stepGoal.coerceIn(500, 100_000))
        putInt("calorie_goal", value.calorieGoal.coerceIn(500, 10_000))
        putInt("protein_goal", value.proteinGoal.coerceIn(10, 1_000))
        putInt("carbohydrate_goal", value.carbohydrateGoal.coerceIn(10, 2_000))
        putInt("fat_goal", value.fatGoal.coerceIn(10, 1_000))
        putInt("water_goal_ml", value.waterGoalMl.coerceIn(250, 10000))
        coachUpdateDate?.let { putString("coach_goal_update", it.toString()) }
    }

    /**
     * Loads the snapshot needed by the fitness cover.
     *
     * @param today Today supplied to [snapshot].
     * @param nowMillis Duration or timestamp for now, in milliseconds.
     * @return The resulting [FitnessSnapshot] instance.
     */
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

    /**
     * Fitness cover users: loads up to 31 saved days for activity and nutrition graphs.
     *
     * @param days Days supplied to [history].
     * @param endDate End date supplied to [history].
     * @return List of resulting history items.
     */
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

    /**
     * Reads up to 14 days of logged activity and meals for the coach.
     *
     * @param today Most recent date to include, even if today's log is still empty.
     * @return Chronological entries with missing activity or nutrition kept separate from zero.
     */
    fun coachHistory(today: LocalDate = LocalDate.now()): List<FitnessCoachDay> =
        (FITNESS_COACH_HISTORY_DAYS - 1 downTo 0).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            val steps = activitySteps(date).takeIf {
                preferences.contains(activityStepsKey(date)) || preferences.contains(legacyStepsKey(date))
            }
            val meals = foods(date)
            if (steps == null && meals.isEmpty() && date != today) return@mapNotNull null
            val nutrition = meals.takeIf { it.isNotEmpty() }?.nutritionTotals()
            val foodNames = meals.asSequence().map { it.food.name.trim().take(60) }
                .filter { it.isNotEmpty() }.distinct().take(8).toList()
            FitnessCoachDay(date, steps, nutrition, foodNames)
        }

    /**
     * Phone fitness users: counts phone steps only while a worn watch is not actively reporting.
     *
     * @param totalSinceBoot Total since boot supplied to [recordSensorValue].
     * @param today Today supplied to [recordSensorValue].
     * @param nowMillis Duration or timestamp for now, in milliseconds.
     * @return The resulting [FitnessSnapshot] for record sensor value.
     */
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

    /**
     * Watch fitness users: makes a worn watch primary and removes phone fallback overlap.
     *
     * @param watchSteps Watch steps supplied to [recordWatchActivity].
     * @param heartRate Heart rate supplied to [recordWatchActivity].
     * @param watchUpdatedAt Watch updated at supplied to [recordWatchActivity].
     * @param today Today supplied to [recordWatchActivity].
     * @param nowMillis Duration or timestamp for now, in milliseconds.
     * @param heartRateUpdatedAt Heart rate updated at supplied to [recordWatchActivity].
     * @return The resulting [FitnessSnapshot] for record watch activity.
     */
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

    /**
     * Health Connect aggregates overlapping step sources; merge by maximum rather than adding them.
     *
     * @param steps Steps supplied to [recordHealthConnect].
     * @param samples Collection of samples values to process.
     * @param today Today supplied to [recordHealthConnect].
     */
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

    /**
     * Adds water.
     *
     * @param milliliters Milliliters supplied to [addWater].
     * @param goalMl Goal ml supplied to [addWater].
     * @param today Today supplied to [addWater].
     * @return The resulting [FitnessSnapshot] for add water.
     */
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

    /**
     * Computes water total.
     *
     * @param today Today supplied to [waterTotal].
     * @return The computed water total numeric value.
     */
    private fun waterTotal(today: LocalDate): Int = runCatching {
        val entries = JSONArray(preferences.getString("water_$today", "[]"))
        (0 until entries.length()).sumOf { entries.getJSONObject(it).optInt("milliliters").coerceIn(-10000, 10000).toLong() }
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)

    /**
     * Adds a selected food and its nutrients to today's diary.
     *
     * @param food Food supplied to [addFood].
     * @param servings Servings supplied to [addFood].
     * @param meal Meal supplied to [addFood].
     * @param today Today supplied to [addFood].
     * @param logId Stable key for the add operation and its Firestore document.
     * @return The resulting [FitnessSnapshot] for add food.
     */
    fun addFood(
        food: NutritionFood,
        servings: Float,
        meal: MealType,
        today: LocalDate = LocalDate.now(),
        logId: String = UUID.randomUUID().toString(),
    ): FitnessSnapshot {
        val entry = FoodLogEntry(
            logId = logId,
            food = food,
            servings = servings.coerceIn(0.0001f, 10_000f),
            meal = meal,
            loggedAt = System.currentTimeMillis(),
        )
        return restoreFood(entry, today)
    }

    /**
     * Inserts a diary entry once, retaining its original time and sync key when undoing a deletion.
     *
     * @param entry The food entry to insert.
     * @param date The diary date that owns the entry.
     * @return The latest fitness snapshot.
     */
    fun restoreFood(entry: FoodLogEntry, date: LocalDate): FitnessSnapshot {
        val existing = foods(date)
        if (existing.any { it.logId == entry.logId }) return snapshot(date)
        saveFoods(date, (existing + entry).sortedBy(FoodLogEntry::loggedAt))
        FitnessAccountSync.meal(context, accountId, date.toString(), entry.logId, entry.toJson().toString())
        return snapshot(date)
    }

    /**
     * Updates food.
     *
     * @param entry Entry supplied to [updateFood].
     * @param today Today supplied to [updateFood].
     * @return The resulting [FitnessSnapshot] for update food.
     */
    fun updateFood(entry: FoodLogEntry, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        require(entry.servings.isFinite() && entry.servings in 0.0001f..10000f)
        val existing = foods(today)
        if (existing.none { it.logId == entry.logId }) return snapshot(today)
        saveFoods(today, existing.map { if (it.logId == entry.logId) entry else it })
        FitnessAccountSync.meal(context, accountId, today.toString(), entry.logId, entry.toJson().toString())
        return snapshot(today)
    }

    /**
     * Removes one food entry from today's diary.
     *
     * @param logId Log ID supplied to [removeFood].
     * @param today Today supplied to [removeFood].
     * @return The resulting [FitnessSnapshot] for remove food.
     */
    fun removeFood(logId: String, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        val existing = foods(today)
        val remaining = existing.filterNot { it.logId == logId }
        if (remaining.size == existing.size) return snapshot(today)
        saveFoods(today, remaining)
        FitnessAccountSync.meal(context, accountId, today.toString(), logId, null)
        return snapshot(today)
    }

    /**
     * Computes recent foods.
     *
     * @param today Today supplied to [recentFoods].
     * @return List of resulting recent foods items.
     */
    fun recentFoods(today: LocalDate = LocalDate.now()): List<FoodLogEntry> =
        FitnessHistory.datesEndingOn(today, 30).asReversed().asSequence()
            .flatMap { foods(it).asReversed().sortedByDescending(FoodLogEntry::loggedAt).asSequence() }
            .distinctBy { it.food.id }.take(10).toList()

    /**
     * Reads today's steps from Health Connect for the fitness dashboard.
     *
     * @param date Date supplied to [activitySteps].
     * @return The computed activity steps numeric value.
     */
    private fun activitySteps(date: LocalDate): Int = preferences.getInt(
        activityStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    /**
     * Combines phone step readings without counting sensor resets twice.
     *
     * @param date Date supplied to [phoneSteps].
     * @return The computed phone steps numeric value.
     */
    private fun phoneSteps(date: LocalDate): Int = preferences.getInt(
        phoneStepsKey(date),
        preferences.getInt(legacyStepsKey(date), 0),
    )

    /**
     * Gives the fitness cover the phone fallback since watch it needs.
     *
     * @param date Date supplied to [phoneFallbackSinceWatch].
     * @return The resulting phone fallback since watch.
     */
    private fun phoneFallbackSinceWatch(date: LocalDate) =
        preferences.getInt(phoneFallbackKey(date), 0)

    /**
     * Returns the heart rate stats used by the fitness cover.
     *
     * @param date Date supplied to [heartRateStats].
     * @return The resulting heart rate stats.
     */
    private fun heartRateStats(date: LocalDate) = HeartRateDayStats(
        peakBpm = preferences.getInt(heartRatePeakKey(date), 0),
        lowestBpm = preferences.getInt(heartRateLowestKey(date), 0),
        totalBpm = preferences.getLong(heartRateTotalKey(date), 0L),
        sampleCount = preferences.getInt(heartRateSampleCountKey(date), 0),
        restingTotalBpm = preferences.getLong(heartRateRestingTotalKey(date), 0L),
        restingSampleCount = preferences.getInt(heartRateRestingSampleCountKey(date), 0),
    )

    /**
     * Reads recent heart-rate samples for the fitness vitals graph.
     *
     * @param date Date supplied to [heartRateSamples].
     * @return List of resulting heart rate samples items.
     */
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

    /**
     * Fitness vitals graphs: keeps at most one point per minute and one day of points.
     *
     * @param existing Collection of existing values to process.
     * @param sample Sample supplied to [appendHeartRateGraphSample].
     * @return List of resulting append heart rate graph sample items.
     */
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

    /**
     * Fitness cover: Converts heart rate samples.
     *
     * @param samples Collection of samples values to process.
     * @return The resulting encode heart rate samples.
     */
    private fun encodeHeartRateSamples(samples: List<HeartRateSample>) = JSONArray().apply {
        samples.forEach { sample ->
            put(JSONObject().apply {
                put("recordedAt", sample.recordedAt)
                put("bpm", sample.bpm)
            })
        }
    }.toString()

    /**
     * Keeps the saved activity steps key name in one place for the fitness cover.
     *
     * @param date Date supplied to [activityStepsKey].
     * @return The resulting activity steps key.
     */
    private fun activityStepsKey(date: LocalDate) = "activity_steps_$date"
    /**
     * Keeps the saved phone steps key name in one place for the fitness cover.
     *
     * @param date Date supplied to [phoneStepsKey].
     * @return The resulting phone steps key.
     */
    private fun phoneStepsKey(date: LocalDate) = "phone_steps_$date"
    /**
     * Watches steps key for fitness cover.
     *
     * @param date Date supplied to [watchStepsKey].
     * @return The resulting watch steps key.
     */
    private fun watchStepsKey(date: LocalDate) = "watch_steps_$date"
    /**
     * Keeps the saved phone fallback key name in one place for the fitness cover.
     *
     * @param date Date supplied to [phoneFallbackKey].
     * @return The resulting phone fallback key.
     */
    private fun phoneFallbackKey(date: LocalDate) = "phone_fallback_since_watch_$date"
    /**
     * Watches active at key for fitness cover.
     *
     * @param date Date supplied to [watchActiveAtKey].
     * @return The resulting watch active at key.
     */
    private fun watchActiveAtKey(date: LocalDate) = "watch_active_at_$date"
    /**
     * Fitness cover: Watches report at key.
     *
     * @param date Date supplied to [watchReportAtKey].
     * @return The resulting watch report at key.
     */
    private fun watchReportAtKey(date: LocalDate) = "watch_report_at_$date"
    /**
     * Keeps the saved heart rate peak key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRatePeakKey].
     * @return The resulting heart rate peak key.
     */
    private fun heartRatePeakKey(date: LocalDate) = "heart_rate_peak_$date"
    /**
     * Keeps the saved heart rate lowest key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateLowestKey].
     * @return The resulting heart rate lowest key.
     */
    private fun heartRateLowestKey(date: LocalDate) = "heart_rate_lowest_$date"
    /**
     * Keeps the saved heart rate total key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateTotalKey].
     * @return The resulting heart rate total key.
     */
    private fun heartRateTotalKey(date: LocalDate) = "heart_rate_total_$date"
    /**
     * Keeps the saved heart rate sample count key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateSampleCountKey].
     * @return The resulting heart rate sample count key.
     */
    private fun heartRateSampleCountKey(date: LocalDate) = "heart_rate_sample_count_$date"
    /**
     * Keeps the saved heart rate resting total key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateRestingTotalKey].
     * @return The resulting heart rate resting total key.
     */
    private fun heartRateRestingTotalKey(date: LocalDate) = "heart_rate_resting_total_$date"
    /**
     * Keeps the saved heart rate resting sample count key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateRestingSampleCountKey].
     * @return The resulting heart rate resting sample count key.
     */
    private fun heartRateRestingSampleCountKey(date: LocalDate) =
        "heart_rate_resting_sample_count_$date"
    /**
     * Keeps the saved heart rate last sample at key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateLastSampleAtKey].
     * @return The resulting heart rate last sample at key.
     */
    private fun heartRateLastSampleAtKey(date: LocalDate) = "heart_rate_last_sample_at_$date"
    /**
     * Keeps the saved heart rate graph samples key name in one place for the fitness cover.
     *
     * @param date Date supplied to [heartRateGraphSamplesKey].
     * @return The resulting heart rate graph samples key.
     */
    private fun heartRateGraphSamplesKey(date: LocalDate) = "heart_rate_graph_samples_$date"
    /**
     * Keeps the saved legacy steps key name in one place for the fitness cover.
     *
     * @param date Date supplied to [legacyStepsKey].
     * @return The resulting legacy steps key.
     */
    private fun legacyStepsKey(date: LocalDate) = "steps_$date"

    /**
     * Loads food-diary entries for the selected day.
     *
     * @param date Date supplied to [foods].
     * @return List of resulting foods items.
     */
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

    /**
     * Saves today's food diary on the phone.
     *
     * @param date Date supplied to [saveFoods].
     * @param foods Collection of foods values to process.
     */
    private fun saveFoods(date: LocalDate, foods: List<FoodLogEntry>) {
        val encoded = JSONArray().apply {
            foods.takeLast(250).forEach { put(it.toJson()) }
        }.toString()
        preferences.edit { putString(foodKey(date), encoded) }
    }

    /**
     * Keeps the saved food key name in one place for the fitness cover.
     *
     * @param date Date supplied to [foodKey].
     * @return The resulting food key.
     */
    private fun foodKey(date: LocalDate) = "foods_$date"

    private companion object {
        const val MAX_DAILY_STEPS = 500_000
    }
}

/**
 * Fitness cover: Converts this record into JSON.
 *
 * @return JSON representation of this object.
 */
private fun FoodLogEntry.toJson() = JSONObject().apply {
    put("logId", logId)
    put("servings", servings.toDouble())
    put("meal", meal.name)
    if (loggedAt > 0) put("loggedAt", loggedAt)
    put("food", food.toJson())
}

/**
 * Serializes this instance into a JSON object.
 *
 * @param includeServingOptions Whether to include serving options.
 * @return JSON object representing to JSON.
 */
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

/**
 * Converts a saved food entry into the model used by the nutrition screen.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting [FoodLogEntry], or `null` if unavailable.
 */
private fun parseFoodLogEntry(value: JSONObject): FoodLogEntry? = runCatching {
    FoodLogEntry(
        logId = value.getString("logId"),
        servings = value.getDouble("servings").toFloat().coerceIn(0.0001f, 10_000f),
        meal = MealType.valueOf(value.getString("meal")),
        loggedAt = value.optLong("loggedAt"),
        food = checkNotNull(value.getJSONObject("food").toNutritionFood()),
    )
}.getOrNull()

/**
 * Converts JSON object into nutrition food.
 *
 * @param includeServingOptions Whether to include serving options.
 * @return The resulting [NutritionFood], or `null` if unavailable.
 */
internal fun JSONObject.toNutritionFood(includeServingOptions: Boolean = false): NutritionFood? = runCatching {
    NutritionFood(
        id = getString("id"),
        name = getString("name"),
        brand = optString("brand"),
        servingDescription = optString("servingDescription", "1 serving"),
        calories = nonNegativeFloat("calories"),
        proteinGrams = nonNegativeFloat("proteinGrams"),
        carbohydrateGrams = nonNegativeFloat("carbohydrateGrams"),
        fatGrams = nonNegativeFloat("fatGrams"),
        source = FoodDataSource.valueOf(getString("source")),
        barcode = optString("barcode"),
        saturatedFatGrams = nonNegativeFloat("saturatedFatGrams"),
        transFatGrams = nonNegativeFloat("transFatGrams"),
        fiberGrams = nonNegativeFloat("fiberGrams"),
        sugarGrams = nonNegativeFloat("sugarGrams"),
        sodiumMilligrams = nonNegativeFloat("sodiumMilligrams"),
        cholesterolMilligrams = nonNegativeFloat("cholesterolMilligrams"),
        potassiumMilligrams = nonNegativeFloat("potassiumMilligrams"),
        servingOptions = if (includeServingOptions) readNutritionFoods(optJSONArray("servingOptions"), false)
            else emptyList(),
    )
}.getOrNull()

/**
 * Reads nutrition foods.
 *
 * @param array Optional array supplied to [readNutritionFoods].
 * @param includeServingOptions Whether to include serving options.
 * @return List of resulting read nutrition foods items.
 */
internal fun readNutritionFoods(array: JSONArray?, includeServingOptions: Boolean = true): List<NutritionFood> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull {
        array.optJSONObject(it)?.toNutritionFood(includeServingOptions)
    }

/**
 * Formats the food serving amount shown in the diary.
 *
 * @return The resulting display amount string.
 */
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
    /**
     * Fitness watch users: adds one medically plausible watch reading to today's summary.
     *
     * @param heartRate Heart rate supplied to [record].
     * @param resting Whether to apply resting.
     * @return The resulting [HeartRateDayStats] instance.
     */
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

    /**
     * Fitness history: converts saved totals into values the daily vitals screen can display.
     *
     * @param date Date supplied to [toVitals].
     * @param watchConnected Whether to apply watch connected.
     * @param lastUpdatedAt Last updated at supplied to [toVitals].
     * @param samples Collection of samples values to process.
     * @return The resulting [HeartRateVitals], or `null` if unavailable.
     */
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
    val nutrition = foodsToday.nutritionTotals()
}

private fun List<FoodLogEntry>.nutritionTotals() = fold(NutritionTotals()) { total, entry ->
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
    /**
     * Creates the ordered date list used by the 14-day fitness graphs.
     *
     * @param endDate End date supplied to [datesEndingOn].
     * @param days Days supplied to [datesEndingOn].
     * @return List of resulting dates ending on items.
     */
    fun datesEndingOn(endDate: LocalDate, days: Int): List<LocalDate> =
        (days.coerceIn(1, 31) - 1 downTo 0).map { endDate.minusDays(it.toLong()) }
}

internal object FitnessMath {
    /**
     * Gives the fitness cover the stride meters it needs.
     *
     * @param heightCm Height cm supplied to [strideMeters].
     * @return The resulting stride meters.
     */
    fun strideMeters(heightCm: Float) = (heightCm.coerceIn(100f, 230f) * 0.413f) / 100f
    /**
     * Estimates walking distance from the saved step count.
     *
     * @param steps Steps supplied to [distanceKm].
     * @param heightCm Height cm supplied to [distanceKm].
     * @return The resulting distance km.
     */
    fun distanceKm(steps: Int, heightCm: Float) =
        steps.coerceAtLeast(0) * strideMeters(heightCm) / 1_000f
    /**
     * Returns the walking calories used by the fitness cover.
     *
     * @param steps Steps supplied to [walkingCalories].
     * @param profile Account profile details.
     * @return The resulting walking calories.
     */
    fun walkingCalories(steps: Int, profile: FitnessProfile) =
        distanceKm(steps, profile.heightCm) * profile.weightKg.coerceIn(30f, 300f) * 0.75f
}

internal object StepSourcePolicy {
    const val WATCH_ACTIVE_WINDOW_MS = 120_000L

    /**
     * Watches is active for fitness cover.
     *
     * @param lastWatchActivityAt Last watch activity at supplied to [watchIsActive].
     * @param nowMillis Duration or timestamp for now, in milliseconds.
     * @return `true` if watch is active, or `false` otherwise.
     */
    fun watchIsActive(lastWatchActivityAt: Long, nowMillis: Long): Boolean =
        lastWatchActivityAt in 1..nowMillis &&
            nowMillis - lastWatchActivityAt <= WATCH_ACTIVE_WINDOW_MS

    /**
     * Adds only new phone steps so watch and phone totals are not counted twice.
     *
     * @param phoneDelta Phone delta supplied to [phoneDeltaToAdd].
     * @param lastWatchActivityAt Last watch activity at supplied to [phoneDeltaToAdd].
     * @param nowMillis Duration or timestamp for now, in milliseconds.
     * @return The computed phone delta to add numeric value.
     */
    fun phoneDeltaToAdd(phoneDelta: Int, lastWatchActivityAt: Long, nowMillis: Long): Int =
        if (watchIsActive(lastWatchActivityAt, nowMillis)) 0 else phoneDelta.coerceAtLeast(0)

    /**
     * Fitness cover: Watches delta to add.
     *
     * @param watchDelta Watch delta supplied to [watchDeltaToAdd].
     * @param phoneFallbackSinceWatch Phone fallback since watch supplied to [watchDeltaToAdd].
     * @return The computed watch delta to add numeric value.
     */
    fun watchDeltaToAdd(watchDelta: Int, phoneFallbackSinceWatch: Int): Int =
        (watchDelta.coerceAtLeast(0) - phoneFallbackSinceWatch.coerceAtLeast(0)).coerceAtLeast(0)
}
