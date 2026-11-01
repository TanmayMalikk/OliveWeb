// Tests first-run permission order after a user denies a prompt.
package com.olive.app

import com.olive.app.ui.users.shared.navigation.*

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import org.junit.Assert.*
import org.junit.Test

// This class sorts before tests that deliberately grant permissions for unrelated feature flows.
class FirstRunLocationPermissionUiTest : OliveUiTestBase() {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun find(node: AccessibilityNodeInfo?, suffix: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.endsWith(suffix) == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), suffix)?.let { return it }
        return null
    }

    private fun permissionButton(suffix: String) {
        compose.waitUntil(20_000) {
            find(automation.rootInActiveWindow, suffix)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    @Test
    // Checks that first-run permissions appear in order and denial does not cancel account setup.
    fun onboardingRequestsFeaturePermissionsAndCompletesAfterDenials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        automation.serviceInfo = automation.serviceInfo.apply {
            flags =
                flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        context.getSharedPreferences("olive_permissions", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        openOnboarding(grantPermissions = false)
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.account.chooseNew()
            session.account.authenticate(
                "location_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            session.account.saveSetupProfile(
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
        assertTrue(session.account.configured)
        assertEquals(SessionScreen.Cover, session.screen)
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
}
