package com.olive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.olive.app.data.MapLocationPoint
import com.olive.app.ui.InteractiveLocationMap
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.ShareDurationDialog
import org.junit.Test
import org.junit.Assert.assertEquals

class LocationMapUiTest:OliveUiTestBase() {
    @Test fun mapLoadsAndExposesInteractiveControls() {
        compose.activity.setContent { OliveTheme { InteractiveLocationMap(listOf(
            MapLocationPoint("test-user","You",53.5461,-113.4937,System.currentTimeMillis(),true)),Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("location_map_view").assertIsDisplayed()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("map_zoom_in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("map_zoom_in").assertIsDisplayed()
        compose.onNodeWithTag("map_zoom_out").assertIsDisplayed()
        compose.onNodeWithTag("recenter_map").assertIsDisplayed()
    }

    @Test fun sharingDurationIncludesTenSecondTestOption() {
        var selected=0L
        compose.activity.setContent { OliveTheme {
            ShareDurationDialog(onDismiss={},onSelected={selected=it})
        } }
        compose.onNodeWithTag("location_duration_10_seconds").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(10_000L,selected) }
    }
}
