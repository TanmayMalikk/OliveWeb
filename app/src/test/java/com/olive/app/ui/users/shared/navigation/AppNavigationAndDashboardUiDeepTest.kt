package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.user.dashboard.SafeArea
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AppNavigationAndDashboardUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oliveRootAndSafeAreaRenderCoverSafePortalAndAccountSubpages() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, page = SafePage.Home, localMode = true).use { handle ->
            handle.session.runtime.state.profile = UserProfile(
                username = "test_user",
                fullName = "Alex",
                dob = "1995-05-05",
                address = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                coverType = CoverType.NEWS,
                role = AccountRole.USER,
            )
            handle.session.runtime.state.coverType = CoverType.NEWS
            var mode by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    if (mode == 0) {
                        OliveRoot(handle.session)
                    } else {
                        OliveUiTestHost {
                            SafeArea(handle.session, exit = {})
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("cover_root").assertExists()

            // Switch OliveRoot to Pin then Safe then Loading/StorageError states
            handle.session.runtime.state.screen = SessionScreen.Pin
            compose.mainClock.advanceTimeBy(250)

            handle.session.runtime.state.screen = SessionScreen.DevicePin
            compose.mainClock.advanceTimeBy(250)

            handle.session.runtime.state.screen = SessionScreen.Safe
            compose.mainClock.advanceTimeBy(250)

            handle.session.runtime.state.privacyBlackout = true
            compose.mainClock.advanceTimeBy(200)
            handle.session.runtime.state.privacyBlackout = false
            compose.mainClock.advanceTimeBy(200)

            // Now exercise SafeArea across Profile, Settings, Contacts, Activity, Home
            mode = 1
            compose.mainClock.advanceTimeBy(250)
            listOf(
                SafePage.Profile,
                SafePage.Settings,
                SafePage.Contacts,
                SafePage.Activity,
                SafePage.Home,
            ).forEach { target ->
                handle.session.runtime.state.page = target
                compose.mainClock.advanceTimeBy(250)
            }
        }
    }
}
