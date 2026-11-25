package com.olive.app.ui.users.user.account

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class UserSettingsAndSecurityUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsEditorOrganizationSettingsAndSafeEntrySetupRenderAllTabsAndChoices() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Settings, localMode = true).use { handle ->
            val profile = UserProfile(
                username = "test_user",
                fullName = "Alex",
                dob = "1995-05-05",
                email = "alex@example.com",
                address = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                role = AccountRole.USER,
            )
            handle.session.runtime.state.profile = profile
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> SettingsEditor(handle.session, profile)
                        1 -> OrganizationSettings(handle.session, onBack = {})
                        else -> SafeEntrySetup(handle.session)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200)
            listOf(
                "settings_tab_password",
                "settings_tab_privacy",
                "settings_tab_permissions",
                "settings_tab_profile",
            ).forEach { tabTag ->
                compose.onNodeWithTag(tabTag).performClick()
                compose.mainClock.advanceTimeBy(180)
            }

            stage = 1
            compose.mainClock.advanceTimeBy(200)
            listOf(
                "settings_tab_password",
                "settings_tab_permissions",
                "settings_tab_profile",
            ).forEach { tabTag ->
                compose.onNodeWithTag(tabTag).performClick()
                compose.mainClock.advanceTimeBy(180)
            }

            stage = 2
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("entry_knows_pin_yes").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("entry_recommendation").assertExists()
            compose.onNodeWithTag("entry_knows_pin_no").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("entry_choose_pin").performClick()
            compose.mainClock.advanceTimeBy(150)
        }
    }
}
