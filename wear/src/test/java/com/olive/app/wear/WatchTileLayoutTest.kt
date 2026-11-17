package com.olive.app.wear

import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DeviceParametersBuilders.SCREEN_SHAPE_ROUND
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.expression.VersionBuilders.VersionInfo
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.materialScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class WatchTileLayoutTest {
    private val device = DeviceParameters.Builder()
        .setScreenWidthDp(228)
        .setScreenHeightDp(228)
        .setScreenDensity(2f)
        .setScreenShape(SCREEN_SHAPE_ROUND)
        .setRendererSchemaVersion(VersionInfo.Builder().setMajor(1).setMinor(500).build())
        .build()
    private val noReadings = WatchTileSnapshot(0, 8000, 0, null, null, null, null)

    private fun render(layout: MaterialScope.() -> LayoutElement): String =
        materialScope(RuntimeEnvironment.getApplication(), device, allowDynamicTheme = false, layout = layout)
            .toLayoutElementProto().toString()

    @Test
    fun activityTileDescribesProgressAndOpensTheFitnessApp() {
        val layout = render { activityTileLayout(noReadings.copy(steps = 4500, caloriesBurned = 180)) }
        assertTrue(layout.contains("4500 of 8000 steps, 180 calories burned"))
        assertTrue(layout.contains("open_activity"))
        assertTrue(layout.contains(WatchFitnessActivity::class.java.name))
    }

    @Test
    fun vitalsTileDistinguishesMissingAndPresentReadings() {
        val empty = render { vitalsTileLayout(noReadings) }
        assertTrue(empty.contains("No heart-rate data yet"))
        assertTrue(empty.contains("Waiting for readings"))
        val current = render {
            vitalsTileLayout(noReadings.copy(currentHeartRate = 78, peakHeartRate = 91,
                lowestHeartRate = 59, averageHeartRate = 72))
        }
        assertTrue(current.contains("Current heart rate 78 beats per minute"))
        assertTrue(current.contains("Avg 72 bpm"))
        assertTrue(current.contains("open_vitals"))
        assertFalse(current.contains("No heart-rate data yet"))
    }
}
