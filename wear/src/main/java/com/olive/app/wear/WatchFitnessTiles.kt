// Watch fitness users: provides glanceable activity and vitals tiles outside the app.
package com.olive.app.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
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
    /**
     * Reads the latest synchronized fitness snapshot used by both watch tiles.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting [WatchTileSnapshot] instance.
     */
    fun read(context: Context): WatchTileSnapshot {
        val state = WatchFitnessData.snapshot(context)
        return WatchTileSnapshot(
            steps = state.displayedSteps, stepGoal = state.stepGoal,
            caloriesBurned = (state.displayedSteps * 0.04f).roundToInt(),
            currentHeartRate = state.heartRate,
            peakHeartRate = state.heartRateSummary.peakBpm.takeIf { it > 0 },
            lowestHeartRate = state.heartRateSummary.lowestBpm.takeIf { it > 0 },
            averageHeartRate = state.heartRateSummary.averageBpm,
        )
    }
}

class OliveActivityTileService : Material3TileService() {
    /**
     * Builds the glanceable step, distance, and calorie tile shown outside the watch app.
     *
     * @param requestParams Request params supplied to [tileResponse].
     * @return The resulting [Tile] for tile response.
     */
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile =
        tile(timeline = Timeline.fromLayoutElement(activityTileLayout(WatchTileData.read(this@OliveActivityTileService))))
}

/**
 * Computes activity tile layout for this material scope.
 *
 * @param data Data supplied to [activityTileLayout].
 * @return The resulting [LayoutElement] for activity tile layout.
 */
internal fun MaterialScope.activityTileLayout(data: WatchTileSnapshot): LayoutElement {
    val progress = (data.steps.toFloat() / data.stepGoal).coerceIn(0f, 1f)
    return primaryLayout(
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
}

class OliveVitalsTileService : Material3TileService() {
    /**
     * Builds the glanceable heart-rate tile and its no-reading state.
     *
     * @param requestParams Request params supplied to [tileResponse].
     * @return The resulting [Tile] for tile response.
     */
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile =
        tile(timeline = Timeline.fromLayoutElement(vitalsTileLayout(WatchTileData.read(this@OliveVitalsTileService))))
}

/**
 * Computes vitals tile layout for this material scope.
 *
 * @param data Data supplied to [vitalsTileLayout].
 * @return The resulting [LayoutElement] for vitals tile layout.
 */
internal fun MaterialScope.vitalsTileLayout(data: WatchTileSnapshot): LayoutElement {
    val current = data.currentHeartRate?.toString() ?: "--"
    val range = if (data.lowestHeartRate != null && data.peakHeartRate != null) {
        "${data.lowestHeartRate}–${data.peakHeartRate} today"
    } else {
        "No heart-rate data yet"
    }
    val average = data.averageHeartRate?.let { "Avg $it bpm" } ?: "Waiting for readings"
    val heartProgress = ((data.currentHeartRate ?: 0) / 200f).coerceIn(0f, 1f)
    return primaryLayout(
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
}

/**
 * Opens the full Olive Fitness watch app when a user taps either tile.
 *
 * @param id ID supplied to [openWatchApp].
 * @return The resulting open watch app.
 */
private fun MaterialScope.openWatchApp(id: String) = clickable(
    id = id,
    action = launchAction(
        ComponentName(
            "com.olive.app",
            WatchFitnessActivity::class.java.name,
        )
    ),
)
