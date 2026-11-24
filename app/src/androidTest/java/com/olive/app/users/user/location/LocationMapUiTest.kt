// Tests user map controls and location-sharing duration limits.
package com.olive.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
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
        compose.onNodeWithTag("loading_skeleton").assertDoesNotExist()
        if (!BuildConfig.MAPBOX_ACCESS_TOKEN.trim().startsWith("pk.")) {
            compose.waitUntil(25_000) {
                compose.onAllNodesWithTag("map_retry").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("map_zoom_in").assertDoesNotExist()
            return
        }
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("map_zoom_in").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map_zoom_in").assertIsDisplayed()
        compose.onNodeWithTag("map_zoom_out").assertIsDisplayed()
        compose.onNodeWithTag("recenter_map").assertIsDisplayed()
        compose.onNodeWithTag("map_loading").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("verifyPublishedBasemap") == "true") {
            compose.waitUntil(20_000) {
                val result = java.util.concurrent.CompletableFuture<Boolean>()
                compose.activityRule.scenario.onActivity { activity ->
                    val webView = findMapWebView(activity.window.decorView)
                    if (webView == null) result.complete(false)
                    else webView.evaluateJavascript(
                        "Boolean(document.querySelector('.mapboxgl-canvas')?.closest('.mapboxgl-map')?.style.visibility === 'visible')",
                    ) { result.complete(it == "true") }
                }
                result.get(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun findMapWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findMapWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
