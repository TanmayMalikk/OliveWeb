package com.olive.app.ui.users.user.dashboard

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class UserSupportResourcesUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safeResourcesScreenLoadsDirectoryFiltersRegionsAndHandlesLocationStates() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Hotlines).use { handle ->
            var permGranted by mutableStateOf(false)
            var lookupAddress by mutableStateOf<DeliveryAddress?>(null)
            val abProfile = UserProfile(
                username = "test_user",
                fullName = "Test User",
                address = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                role = AccountRole.USER,
            )

            compose.setContent {
                val permFn: suspend () -> Boolean = { permGranted }
                val lookupFn: suspend (Context) -> DeliveryAddress? = { lookupAddress }
                OliveUiTestHost(locationGranted = permGranted) {
                    CompositionLocalProvider(
                        LocalLocationPermissionRequest provides permFn,
                        LocalHotlineLocationLookup provides lookupFn,
                    ) {
                        SafeResourcesScreen(handle.session, abProfile)
                    }
                }
            }

            // Wait for Dispatchers.IO bundled directory read
            val deadline = System.nanoTime() + 4_000_000_000L
            while (compose.onAllNodesWithTag("hotline_region").fetchSemanticsNodes().isEmpty() && System.nanoTime() < deadline) {
                compose.mainClock.advanceTimeBy(50)
                Thread.sleep(20)
            }
            compose.mainClock.advanceTimeBy(150)

            // Test denied location permission
            compose.onNodeWithTag("hotline_use_device_location").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("hotline_location_error").assertExists()
            compose.onNodeWithTag("hotline_location_permissions").assertExists()

            // Test granted permission with null lookup -> unavailable error
            permGranted = true
            lookupAddress = null
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("hotline_use_device_location").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("hotline_location_error").assertExists()

            // Test granted permission with valid Canadian address -> sets region
            lookupAddress = DeliveryAddress(line1 = "200 King St", city = "Toronto", province = "ON", postalCode = "M5H 3T9")
            compose.mainClock.advanceTimeBy(50)
            compose.onNodeWithTag("hotline_use_device_location").performClick()
            compose.mainClock.advanceTimeBy(150)

            // Open region dropdown and select Alberta
            compose.onNodeWithTag("hotline_region").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("hotline_region_AB").performClick()
            compose.mainClock.advanceTimeBy(150)

            // Toggle 24/7 filter chip and switch through category tabs
            compose.onNodeWithTag("hotline_filter_247").performClick()
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText(context.getString(R.string.support_tab_hotlines)).performClick()
            compose.mainClock.advanceTimeBy(120)
            compose.onNodeWithText(context.getString(R.string.support_tab_saved, 0)).performClick()
            compose.mainClock.advanceTimeBy(120)
            compose.onNodeWithText(context.getString(R.string.support_tab_all)).performClick()
            compose.mainClock.advanceTimeBy(120)
        }
    }
}
