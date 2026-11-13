// Tests that account setup leaves sensitive permissions for their features.
package com.olive.app

import com.olive.app.ui.users.shared.navigation.*

import android.Manifest
import android.content.pm.PackageManager
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
    }
}
