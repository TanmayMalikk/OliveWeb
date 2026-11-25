package com.olive.app.ui.users.user.account

import android.app.Application
import android.app.KeyguardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.Friend
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class UserSettingsAndSecurityDeepActionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun settingsEditorExercisesValidationErrorsTogglesAndTabs() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Settings, localMode = true).use { handle ->
            val session = handle.session
            val profile = UserProfile(
                username = "alex_u",
                fullName = "Alex User",
                dob = "1995-05-05",
                email = "alex@example.com",
                address = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                role = AccountRole.USER,
            )
            session.runtime.state.profile = profile

            compose.setContent {
                OliveUiTestHost {
                    SettingsEditor(session, profile)
                }
            }
            compose.waitForIdle()

            // Save valid profile
            compose.onNodeWithTag("save_settings").performScrollTo().performClick()
            compose.waitForIdle()

            // Switch to Password tab and exercise validation error shakes on change_password and change_trigger
            compose.onNodeWithTag("settings_tab_password").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("current_password").performScrollTo().performTextReplacement("cur")
            compose.onNodeWithTag("new_password").performScrollTo().performTextReplacement("short")
            compose.onNodeWithTag("change_password").performScrollTo().performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("settings_trigger").performScrollTo().performTextReplacement("ab")
            compose.onNodeWithTag("change_trigger").performScrollTo().performClick()
            compose.waitForIdle()

            // Switch to Privacy tab and toggle recent_file_caching
            compose.onNodeWithTag("settings_tab_privacy").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("recent_file_caching").performScrollTo().performClick()
            compose.waitForIdle()

            // Switch to Permissions tab
            compose.onNodeWithTag("settings_tab_permissions").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun contactEditorAndSafeEntrySettingsCoverAddEditRemoveAndTriggerSetup() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Contacts, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.friends = listOf(Friend("fr-1", "friend-uid-1", "trusted_friend"))
            val contact = SafeContact("c-1", "Sam Helper", "780-555-0199", safeToStay = false, linkedUid = "", relationship = "Sister")
            val profile = UserProfile(
                username = "alex_u",
                fullName = "Alex User",
                dob = "1995-05-05",
                address = DeliveryAddress(city = "Edmonton"),
                contacts = listOf(contact),
                role = AccountRole.USER,
            )
            session.runtime.state.profile = profile

            compose.setContent {
                OliveUiTestHost {
                    ContactEditor(session, profile)
                }
            }
            compose.waitForIdle()

            // Toggle safe_to_stay switch
            compose.onNodeWithTag("safe_to_stay_c-1").performScrollTo().performClick()
            compose.waitForIdle()

            // Validation failure on empty form
            compose.onNodeWithTag("save_contact").performScrollTo().performClick()
            compose.waitForIdle()

            // Fill form and add contact
            compose.onNodeWithTag("contact_name").performScrollTo().performTextReplacement("Pat Helper")
            compose.onNodeWithTag("contact_details").performScrollTo().performTextReplacement("pat@example.com")
            compose.onNodeWithTag("contact_relationship").performScrollTo().performTextReplacement("Friend")
            compose.onNodeWithTag("save_contact").performScrollTo().performClick()
            compose.waitForIdle()

            // Edit and cancel edit, then remove
            compose.onAllNodesWithText(context.getString(R.string.edit))[0].performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.cancel_edit)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText(context.getString(R.string.remove))[0].performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun safeEntryMethodAndTriggerSetupCoverSelectionsAndValidation() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setIsDeviceSecure(true)
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, localMode = true).use { handle ->
            val session = handle.session
            var showTrigger by mutableStateOf(false)
            compose.setContent {
                OliveUiTestHost {
                    if (!showTrigger) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SafeEntryMethodSettings(session)
                        }
                    } else {
                        TriggerSetup(session)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("entry_use_device").performScrollTo()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("entry_use_device").performClick()
            compose.mainClock.advanceTimeBy(250)
            showTrigger = true
            compose.mainClock.advanceTimeBy(250)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("trigger_setup_submit").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }
}
