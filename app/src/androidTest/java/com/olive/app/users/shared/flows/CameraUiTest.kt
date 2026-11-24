// Tests photo and video capture in the shared safe-app camera.
package com.olive.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiTest : OliveUiTestBase() {
    // Checks the embedded camera captures photos and videos for private review step with the test's sample data.
    @Test
    fun embeddedCameraCapturesPhotosAndVideosForPrivateReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        register()
        unlock()
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[SessionViewModel::class.java].navigation.openSafePage(
                SafePage.Camera
            )
        }
        waitFor("camera_screen")
        val camera = compose.onNodeWithTag("camera_screen").getUnclippedBoundsInRoot()
        val exit = compose.onNodeWithTag("quick_exit").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(exit.bottom <= camera.bottom && exit.right - exit.left < camera.right - camera.left)
        waitFor("camera_ready")
        compose.onNodeWithTag("camera_flip").assertIsDisplayed().performClick()
        waitFor("camera_ready")
        compose.onNodeWithTag("camera_shutter").assertIsDisplayed().performClick()
        waitFor("camera_save")
        compose.onNodeWithTag("camera_save").assertIsDisplayed()
        compose.onNodeWithTag("camera_retake").performClick()
        waitFor("camera_ready")
        compose.onNodeWithTag("camera_mode_video").performClick()
        compose.onNodeWithTag("camera_shutter").performClick()
        waitFor("camera_recording_active")
        Thread.sleep(3_000)
        compose.onNodeWithTag("camera_shutter").performClick()
        waitFor("camera_save")
        compose.onNodeWithTag("camera_save").assertIsDisplayed()
        compose.onNodeWithTag("camera_retake").performClick()
        waitFor("camera_ready")

        compose.onNodeWithTag("camera_mode_document").performClick()
        compose.onNodeWithTag("camera_shutter").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("camera_save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("camera_save").performClick()
        waitFor("camera_upload_error")
        compose.onNodeWithTag("camera_retake").performClick()
        compose.onNodeWithTag("camera_upload_error").assertDoesNotExist()
    }
}
