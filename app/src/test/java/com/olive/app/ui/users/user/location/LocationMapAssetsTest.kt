package com.olive.app.ui.users.user.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocationMapAssetsTest {
    private fun readMapAsset(fileName: String): String {
        val asset = listOf(
            File("src/main/assets/map/$fileName"),
            File("app/src/main/assets/map/$fileName"),
        ).firstOrNull(File::isFile)
            ?: error("Missing bundled map asset: $fileName")
        return asset.readText()
    }

    @Test
    fun mapMarkerClassesKeepAbsolutePositioningSoPinsStayPinnedOnZoom() {
        val html = readMapAsset("map.html")
        val liveRule = Regex("""\.olive-live-marker\{[^}]*\}""").find(html)?.value
            ?: error("Missing .olive-live-marker CSS rule")
        val serviceRule = Regex("""\.olive-service-marker\{[^}]*\}""").find(html)?.value
            ?: error("Missing .olive-service-marker CSS rule")

        assertTrue(liveRule.contains("position:absolute"))
        assertTrue(liveRule.contains("top:0"))
        assertTrue(liveRule.contains("left:0"))
        assertFalse(liveRule.contains("position:relative"))

        assertTrue(serviceRule.contains("position:absolute"))
        assertTrue(serviceRule.contains("top:0"))
        assertTrue(serviceRule.contains("left:0"))
        assertFalse(serviceRule.contains("position:relative"))
    }

    @Test
    fun mapResizesDotsOnZoomEndInsteadOfEveryZoomFrame() {
        val js = readMapAsset("map.js")
        assertTrue(js.contains("mbMap.on('zoomend'"))
        assertFalse(js.contains("mbMap.on('zoom'"))
    }
}
