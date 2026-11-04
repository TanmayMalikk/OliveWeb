// Camera tests: checks the names shown for files created inside the app camera.
package com.olive.app.ui.users.shared.camera

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFileNamesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // Uses one fixed instant to prove each capture type gets the requested prefix and extension.
    @Test
    fun builtInCameraFilesUseDateAndTimestampNames() {
        val instant = 0L

        assertEquals(
            "Oliveimage19700101_000000_000.jpg",
            builtInCameraFileName(BuiltInCameraFile.IMAGE, instant, utc),
        )
        assertEquals(
            "Olivevideo19700101_000000_000.mp4",
            builtInCameraFileName(BuiltInCameraFile.VIDEO, instant, utc),
        )
        assertEquals(
            "OliveDoc19700101_000000_000.pdf",
            builtInCameraFileName(BuiltInCameraFile.DOCUMENT, instant, utc),
        )
    }
}
