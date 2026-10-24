// Tests the location permission UI test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import com.olive.app.ui.*
import org.junit.Assert.*
import org.junit.Test

// Groups the data and actions for location permission UI test.
class LocationPermissionUiTest : OliveUiTestBase() {
    override val grantLocationPermissions get() = false
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun find(node: AccessibilityNodeInfo?, suffix: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.endsWith(suffix) == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), suffix)?.let { return it }
        return null
    }

    private fun permissionButton(suffix: String) {
        compose.waitUntil(20_000) { find(automation.rootInActiveWindow, suffix) != null }
        automation.waitForIdle(500, 5_000)
        assertTrue(
            find(
                automation.rootInActiveWindow,
                suffix
            )?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        )
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    @Test
    // Runs when boarding requests all feature permissions and hotline can retry with approximate permission happens.
    fun onboardingRequestsAllFeaturePermissionsAndHotlineCanRetryWithApproximatePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        automation.serviceInfo = automation.serviceInfo.apply {
            flags =
                flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        // Run this regression after revoking location permission from the disposable emulator, before instrumentation starts.
        org.junit.Assume.assumeTrue(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        automation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        automation.revokeRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        context.getSharedPreferences("olive_permissions", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        waitFor("new_user")
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.chooseNew()
            session.authenticate(
                "location_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            session.saveSetupProfile(
                UserProfile(
                    "location_tester",
                    "Location Tester",
                    "",
                    "",
                    "2000-01-02",
                    "English",
                    "",
                    "Edmonton",
                    InputPolicy.triggerHash("moon garden"),
                    address = DeliveryAddress(city = "Edmonton")
                )
            )
        }
        chooseAppPin()
        fill("new_pin", "123456")
        fill(
            "confirm_pin",
            "123456"
        )
        click("pin_setup_submit")
        // Local test mode intentionally skips notifications; location follows the completed onboarding step directly.
        permissionButton("/permission_deny_button")
        // Camera and microphone follow immediately; denying either must still finish onboarding normally.
        permissionButton("/permission_deny_button")
        permissionButton("/permission_deny_button")
        waitCover()
        assertTrue(session.configured)
        unlock()
        compose.runOnIdle { session.navigate(SafePage.Hotlines) }
        waitFor("hotline_use_device_location")
        compose.onNodeWithTag("hotline_city").assertDoesNotExist()
        compose.onNodeWithTag("hotline_use_device_location").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            find(
                automation.rootInActiveWindow,
                "/permission_allow_foreground_only_button"
            ) != null
        }
        assertEquals(
            "A runtime permission dialog must not exit the safe screen",
            SessionScreen.Safe,
            session.screen
        )
        find(
            automation.rootInActiveWindow,
            "/permission_location_accuracy_radio_coarse"
        )?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        permissionButton("/permission_allow_foreground_only_button")
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("hotline_use_device_location").fetchSemanticsNodes()
                .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        assertEquals(SessionScreen.Safe, session.screen)
        assertEquals("Edmonton", session.profile!!.address.city)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        // Leaving during a new lookup still locks the app; a late permission/location result cannot restore it.
        compose.onNodeWithTag("hotline_use_device_location").performScrollTo().performClick()
        shell("input keyevent 3")
        compose.waitUntil(10_000) { session.screen == SessionScreen.Cover }
        shell("am start -n com.olive.app/.OliveActivity")
        waitCover()
        assertNull(session.profile)
        compose.onNodeWithTag("hotline_page").assertDoesNotExist()
    }
}
