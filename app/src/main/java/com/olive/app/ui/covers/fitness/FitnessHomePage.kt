// Fitness cover users: shows today's summary, step progress, and the goals shortcut.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.oliveScrollbar
import kotlin.math.roundToInt
import java.time.LocalDate
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import java.util.Locale


// Shows a quick summary of steps, calories, macros, and goals.
@Composable
internal fun FitnessHomePage(snapshot: FitnessSnapshot, onGoals: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val summaryDescription = buildString {
        append(stringResource(R.string.today_summary))
        append(". ${snapshot.stepsToday} ${stringResource(R.string.steps_today)}. ")
        append("${snapshot.nutrition.calories.displayAmount()} ${stringResource(R.string.calories)}. ")
        append("${snapshot.nutrition.proteinGrams.displayAmount()} ${stringResource(R.string.protein)}.")
    }
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("fitness_home"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.fitness_home_message),
                color = FitnessSubtle,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(FitnessAccent, FitnessHeroEnd)))
                    .clearAndSetSemantics { contentDescription = summaryDescription }
                    .padding(22.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.today_summary),
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper.copy(alpha = 0.82f),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeroMetric(snapshot.stepsToday.toString(), stringResource(R.string.steps_today))
                        HeroMetric(snapshot.nutrition.calories.displayAmount(), stringResource(R.string.calories))
                        HeroMetric("${snapshot.nutrition.proteinGrams.displayAmount()} g", stringResource(R.string.protein))
                    }
                }
            }
        }
        item { StepSummaryCard(snapshot) }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = FitnessWarmCard,
                    contentColor = FitnessText,
                ),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onGoals),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.goals), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.edit_fitness_goals), color = FitnessSubtle)
                    }
                    Icon(Icons.Outlined.Settings, null, tint = FitnessAccent)
                }
            }
        }
    }
}
// Draws one large summary value on the fitness home page.
@Composable
private fun RowScope.HeroMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
            .background(Paper.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Paper.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
    }
}

// fitness cover shows this step summary card view so the user can view its details and use its actions.
@Composable
private fun StepSummaryCard(snapshot: FitnessSnapshot) {
    val progress = (snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal).coerceIn(0f, 1f)
    val description = buildString {
        append("${stringResource(R.string.steps_today)}, ${snapshot.stepsToday}. ")
        append("${stringResource(R.string.daily_step_goal)}, ${snapshot.profile.stepGoal}. ")
        append("${stringResource(R.string.distance)}, ")
        append("${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km. ")
        append("${stringResource(R.string.active_calories)}, ")
        append("${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal.")
    }
    Card(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(PaleOlive),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, tint = FitnessAccent) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.steps_today), color = FitnessSubtle)
                        Text(
                            snapshot.stepsToday.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.goals), color = FitnessSubtle)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityMiniMetric(snapshot.profile.stepGoal.toString(), stringResource(R.string.daily_step_goal))
                ActivityMiniMetric(
                    "${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km",
                    stringResource(R.string.distance),
                )
                ActivityMiniMetric(
                    "${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal",
                    stringResource(R.string.active_calories),
                )
            }
        }
    }
}

// fitness cover shows this activity mini metric view so the user can view its details and use its actions.
@Composable
private fun RowScope.ActivityMiniMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(FitnessWarmCard)
            .clearAndSetSemantics { contentDescription = "$label, $value" }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, color = FitnessText, fontWeight = FontWeight.Bold)
        Text(label, color = FitnessSubtle, style = MaterialTheme.typography.labelSmall)
    }
}

// Fitness cover users: defines activity, nutrition, and heart-rate values without storing them.

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
        lastWatchActivityAt > 0L && nowMillis >= lastWatchActivityAt &&
            nowMillis - lastWatchActivityAt <= WATCH_ACTIVE_WINDOW_MS

    // Adds only new phone steps so watch and phone totals are not counted twice.
    fun phoneDeltaToAdd(phoneDelta: Int, lastWatchActivityAt: Long, nowMillis: Long): Int =
        if (watchIsActive(lastWatchActivityAt, nowMillis)) 0 else phoneDelta.coerceAtLeast(0)

    // Fitness cover: Watches delta to add.
    fun watchDeltaToAdd(watchDelta: Int, phoneFallbackSinceWatch: Int): Int =
        (watchDelta.coerceAtLeast(0) - phoneFallbackSinceWatch.coerceAtLeast(0)).coerceAtLeast(0)
}

// Fitness cover users: summarizes today's steps, distance, and calories in one card.

private val MacroRingColor = Color(0xFF875E78)

// Shows today's movement summary and the 14-day activity graphs.
@Composable
internal fun FitnessActivityDashboard(snapshot: FitnessSnapshot) {
    val distance = FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm)
    val burned = FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile)
    val progress = (
        snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal.coerceAtLeast(1)
    ).coerceIn(0f, 1f)
    val description = pluralStringResource(
        R.plurals.fitness_activity_dashboard_description,
        snapshot.stepsToday,
        snapshot.stepsToday,
        distance,
        burned,
    )

    Card(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = description
        },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF263722)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.daily_activity),
                color = Paper,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    DashboardMetric(
                        snapshot.stepsToday.toString(),
                        stringResource(R.string.fitness_steps_unit),
                        Color(0xFFA7CA65),
                    )
                    DashboardMetric(
                        String.format(Locale.ROOT, "%.2f", distance),
                        stringResource(R.string.fitness_kilometres_unit),
                        Color(0xFF53BBD8),
                    )
                    DashboardMetric(
                        burned.roundToInt().toString(),
                        stringResource(R.string.fitness_kilocalories_unit),
                        MacroRingColor,
                    )
                }
                ActivityProgressRings(progress)
            }
        }
    }
}

// Builds the dashboard metric that the fitness cover needs.
@Composable
private fun DashboardMetric(value: String, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.size(13.dp).background(color, CircleShape))
        Text(value, color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Paper.copy(alpha = 0.72f))
    }
}

// Draws progress for steps, active time, and burned calories.
@Composable
private fun ActivityProgressRings(progress: Float) {
    Canvas(Modifier.size(142.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val widths = floatArrayOf(11.dp.toPx(), 10.dp.toPx(), 9.dp.toPx())
        val radii = floatArrayOf(size.minDimension * 0.43f, size.minDimension * 0.32f, size.minDimension * 0.21f)
        val colours = listOf(Color(0xFFA7CA65), Color(0xFF53BBD8), MacroRingColor)
        radii.indices.forEach { index ->
            drawCircle(
                color = Paper.copy(alpha = 0.12f),
                radius = radii[index],
                center = centre,
                style = Stroke(widths[index]),
            )
            drawArc(
                color = colours[index],
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(centre.x - radii[index], centre.y - radii[index]),
                size = androidx.compose.ui.geometry.Size(radii[index] * 2f, radii[index] * 2f),
                style = Stroke(widths[index], cap = StrokeCap.Round),
            )
        }
    }
}
