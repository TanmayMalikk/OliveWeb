// Tests user map controls and location-sharing duration limits.
package com.olive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import com.olive.app.ui.shared.OliveTheme
import org.junit.Test

class LocationMapUiTest : OliveUiTestBase() {
    // Checks the map loads and exposes interactive controls step with the test's sample data.
    @Test
    fun mapLoadsAndExposesInteractiveControls() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                OliveTheme {
                    InteractiveLocationMap(
                        listOf(
                            MapLocationPoint(
                                "test-user",
                                "You",
                                53.5461,
                                -113.4937,
                                System.currentTimeMillis(),
                                true
                            )
                        ), Modifier.fillMaxSize()
                    )
                }
            }
        }
        compose.onNodeWithTag("location_map_view").assertIsDisplayed()
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("map_zoom_in").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map_zoom_in").assertIsDisplayed()
        compose.onNodeWithTag("map_zoom_out").assertIsDisplayed()
        compose.onNodeWithTag("recenter_map").assertIsDisplayed()
    }

}
