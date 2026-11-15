// Tests that account setup leaves sensitive permissions for their features.
package com.olive.app

import com.olive.app.ui.users.shared.navigation.*

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import org.junit.Assert.*
import org.junit.Test

// This class sorts before tests that deliberately grant permissions for unrelated feature flows.
class FirstRunLocationPermissionUiTest : OliveUiTestBase() {
    @Test
    fun onboardingCompletesWithoutRequestingSensitiveFeaturePermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        compose.waitUntil(10_000) { session.screen == SessionScreen.Cover }
        assertTrue(session.account.configured)
        assertEquals(SessionScreen.Cover, session.screen)
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        unlock()
        compose.runOnIdle { session.navigation.openSafePage(SafePage.Location) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun isPermissionScreen(): Boolean {
            val systemPackage = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            return systemPackage.endsWith("permissioncontroller") || systemPackage == "com.android.settings"
        }
        compose.waitUntil(10_000) {
            val window = automation.rootInActiveWindow ?: return@waitUntil false
            val allow = window.findAccessibilityNodeInfosByViewId(
                "${window.packageName}:id/permission_allow_foreground_only_button"
            ).firstOrNull() ?: window.findAccessibilityNodeInfosByText("While using the app").firstOrNull()
                ?: return@waitUntil false
            val button = generateSequence(allow) { it.parent }.firstOrNull { it.isClickable }
            button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        compose.waitUntil(10_000) { !isPermissionScreen() }
        waitFor("location_back")
        assertEquals(SessionScreen.Safe, session.screen)
        assertEquals(SafePage.Location, session.page)
        assertTrue(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        assertNotEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            click("background_location")
            click("background_location_continue")
            compose.waitUntil(10_000) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val option = context.packageManager.backgroundPermissionOptionLabel.toString()
                    automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(option)?.isNotEmpty() == true
                } else {
                    isPermissionScreen()
                }
            }
            assertEquals(SessionScreen.Safe, session.screen)
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input keyevent KEYCODE_BACK")
            ).use { it.readBytes() }
            compose.waitUntil(10_000) { !isPermissionScreen() }
            waitFor("location_back")
            assertEquals(SessionScreen.Safe, session.screen)
            assertEquals(SafePage.Location, session.page)
        }
    }
}
