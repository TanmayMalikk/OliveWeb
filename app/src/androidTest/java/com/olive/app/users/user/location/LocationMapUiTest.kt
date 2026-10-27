// Tests the location map UI test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.olive.app.data.MapLocationPoint
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.location.ShareDurationDialog
import org.junit.Test
import org.junit.Assert.assertEquals

// Groups the data and actions for location map UI test.
class LocationMapUiTest : OliveUiTestBase() {
    @Test
    fun mapLoadsAndExposesInteractiveControls() {
        compose.activity.setContent {
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
        compose.onNodeWithTag("location_map_view").assertIsDisplayed()
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("map_zoom_in").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map_zoom_in").assertIsDisplayed()
        compose.onNodeWithTag("map_zoom_out").assertIsDisplayed()
        compose.onNodeWithTag("recenter_map").assertIsDisplayed()
    }

    @Test
    fun sharingDurationIncludesTenSecondTestOption() {
        var selected = 0L
        compose.activity.setContent {
            OliveTheme {
                ShareDurationDialog(onDismiss = {}, onSelected = { selected = it })
            }
        }
        compose.onNodeWithTag("location_duration_10_seconds").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(10_000L, selected) }
    }

    @Test
    fun customSharingDurationIsCappedAtSeventyTwoHours() {
        var selected = 0L
        compose.activity.setContent {
            OliveTheme {
                ShareDurationDialog(
                    onDismiss = {},
                    onSelected = { selected = it })
            }
        }
        compose.onNodeWithTag("location_custom_hours").performTextReplacement("73")
        compose.onNodeWithTag("location_custom_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("location_custom_hours").performTextReplacement("72")
        compose.onNodeWithTag("location_custom_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(72L * 60 * 60 * 1_000, selected) }
    }
}
