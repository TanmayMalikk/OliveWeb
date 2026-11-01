// Watch fitness users: provides glanceable activity and vitals tiles outside the app.
package com.olive.app.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.CardDefaults.filledVariantCardColors
import androidx.wear.protolayout.material3.CircularProgressIndicatorDefaults.filledTonalProgressIndicatorColors
import androidx.wear.protolayout.material3.GraphicDataCardStyle.Companion.largeGraphicDataCardStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography.BODY_MEDIUM
import androidx.wear.protolayout.material3.Typography.DISPLAY_MEDIUM
import androidx.wear.protolayout.material3.Typography.LABEL_MEDIUM
import androidx.wear.protolayout.material3.graphicDataCard
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.segmentedCircularProgressIndicator
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import java.time.LocalDate
import kotlin.math.roundToInt

internal data class WatchTileSnapshot(
    val steps: Int,
    val stepGoal: Int,
    val caloriesBurned: Int,
    val currentHeartRate: Int?,
    val peakHeartRate: Int?,
    val lowestHeartRate: Int?,
    val averageHeartRate: Int?,
)

internal object WatchTileData {
    fun read(context: Context): WatchTileSnapshot {
        val preferences = context.getSharedPreferences("watch_fitness", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val isToday = preferences.getString("step_date", null) == today
        val watchSteps = if (isToday) preferences.getInt("steps_today", 0) else 0
        val steps = maxOf(watchSteps, preferences.getInt("phone_steps", 0))
        val heartRateIsToday = preferences.getString("heart_rate_date", null) == today
        val samples = if (heartRateIsToday) preferences.getInt("heart_rate_samples", 0) else 0
        val total = if (heartRateIsToday) preferences.getLong("heart_rate_total", 0L) else 0L
        return WatchTileSnapshot(
            steps = steps,
            stepGoal = preferences.getInt("step_goal", 8_000).coerceAtLeast(1),
            caloriesBurned = (steps * 0.04f).roundToInt(),
            currentHeartRate = preferences.getInt("heart_rate_latest", 0).takeIf {
                heartRateIsToday && it in 30..240
            },
            peakHeartRate = preferences.getInt("heart_rate_peak", 0).takeIf {
                heartRateIsToday && it > 0
            },
            lowestHeartRate = preferences.getInt("heart_rate_lowest", 0).takeIf {
                heartRateIsToday && it > 0
            },
            averageHeartRate = if (samples > 0) (total / samples).toInt() else null,
        )
    }
}

class OliveActivityTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val data = WatchTileData.read(this@OliveActivityTileService)
        val progress = (data.steps.toFloat() / data.stepGoal).coerceIn(0f, 1f)
        return tile(
            timeline = Timeline.fromLayoutElement(
                primaryLayout(
                    titleSlot = { text("Olive Activity".layoutString, typography = LABEL_MEDIUM) },
                    mainSlot = {
                        graphicDataCard(
                            onClick = openWatchApp("open_activity"),
                            modifier = LayoutModifier.contentDescription(
                                "${data.steps} of ${data.stepGoal} steps, ${data.caloriesBurned} calories burned"
                            ),
                            height = expand(),
                            colors = filledVariantCardColors(),
                            style = largeGraphicDataCardStyle(),
                            title = {
                                text("${data.steps}".layoutString, typography = DISPLAY_MEDIUM)
                            },
                            content = {
                                text(
                                    "${data.stepGoal} goal · ${data.caloriesBurned} kcal".layoutString,
                                    typography = BODY_MEDIUM,
                                )
                            },
                            graphic = {
                                segmentedCircularProgressIndicator(
                                    segmentCount = 10,
                                    staticProgress = progress,
                                    colors = filledTonalProgressIndicatorColors(),
                                )
                            },
                        )
                    },
                )
            )
        )
    }
}

class OliveVitalsTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val data = WatchTileData.read(this@OliveVitalsTileService)
        val current = data.currentHeartRate?.toString() ?: "--"
        val range = if (data.lowestHeartRate != null && data.peakHeartRate != null) {
            "${data.lowestHeartRate}–${data.peakHeartRate} today"
        } else {
            "No heart-rate data yet"
        }
        val average = data.averageHeartRate?.let { "Avg $it bpm" } ?: "Waiting for readings"
        val heartProgress = ((data.currentHeartRate ?: 0) / 200f).coerceIn(0f, 1f)
        return tile(
            timeline = Timeline.fromLayoutElement(
                primaryLayout(
                    titleSlot = { text("Olive Vitals".layoutString, typography = LABEL_MEDIUM) },
                    mainSlot = {
                        graphicDataCard(
                            onClick = openWatchApp("open_vitals"),
                            modifier = LayoutModifier.contentDescription(
                                "Current heart rate $current beats per minute. $range. $average"
                            ),
                            height = expand(),
                            colors = filledVariantCardColors(),
                            style = largeGraphicDataCardStyle(),
                            title = {
                                text("$current bpm".layoutString, typography = DISPLAY_MEDIUM)
                            },
                            content = {
                                text("$range · $average".layoutString, typography = BODY_MEDIUM)
                            },
                            graphic = {
                                segmentedCircularProgressIndicator(
                                    segmentCount = 8,
                                    staticProgress = heartProgress,
                                    colors = filledTonalProgressIndicatorColors(),
                                )
                            },
                        )
                    },
                )
            )
        )
    }
}

private fun MaterialScope.openWatchApp(id: String) = clickable(
    id = id,
    action = launchAction(
        ComponentName(
            "com.olive.app",
            WatchFitnessActivity::class.java.name,
        )
    ),
)
