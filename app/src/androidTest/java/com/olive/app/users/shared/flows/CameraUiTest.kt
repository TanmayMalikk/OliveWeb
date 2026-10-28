// Tests photo and video capture in the shared safe-app camera.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.Manifest
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Test

class CameraUiTest : OliveUiTestBase() {
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
        compose.onNodeWithTag("quick_exit").assertDoesNotExist()
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
    }
}
