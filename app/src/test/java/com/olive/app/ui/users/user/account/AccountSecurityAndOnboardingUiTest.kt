package com.olive.app.ui.users.user.account

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.Friend
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.onboarding.OnboardingNavigation
import com.olive.app.ui.users.shared.onboarding.OnboardingProgress
import com.olive.app.ui.users.shared.onboarding.ProfileSetup
import com.olive.app.ui.users.shared.onboarding.RequirementBullet
import com.olive.app.ui.users.shared.onboarding.SafetyPlanQuestionnaireDialog
import com.olive.app.ui.users.shared.onboarding.SafetyPlanQuestionnaireForm
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AccountSecurityAndOnboardingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val sampleProfile = UserProfile(
        username = "alex_user",
        fullName = "Alex Rivera",
        affectedNames = "Child One",
        abuserName = "Jordan",
        dob = "1995-04-12",
        language = "English",
        situation = "Safety planning",
        region = "Alberta",
        contacts = listOf(SafeContact("c-1", "Sam", "555-0199", safeToStay = true, linkedUid = "friend-1", relationship = "Sibling")),
        address = DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", postalCode = "T5J 1N3", country = "Canada"),
        coverType = CoverType.FITNESS,
        role = AccountRole.USER,
        email = "alex@example.com",
        emailCommunicationConsent = true,
        phone = "555-0100",
    )

    @Test
    fun safeEntrySetupTriggerSetupAndDeviceLockPromptWork() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Pin, localMode = true).use { handle ->
            var stage by mutableIntStateOf(0)
            var backupClicks = 0

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> SafeEntrySetup(handle.session)
                        1 -> TriggerSetup(handle.session)
                        else -> DeviceLockPrompt(handle.session, onUseBackupPin = { backupClicks++ })
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("entry_knows_pin_yes").performScrollTo().performClick()
            compose.onNodeWithTag("entry_recommendation").assertExists()
            compose.onNodeWithTag("entry_knows_pin_no").performScrollTo().performClick()
            compose.onNodeWithTag("entry_choose_pin").performScrollTo().performClick()
            compose.onNodeWithTag("new_pin").assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("trigger_setup_submit").performScrollTo().performClick()
            compose.onNodeWithTag("trigger").performTextInput("olive trigger")
            compose.onNodeWithTag("trigger_setup_submit").performScrollTo().performClick()

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithTag("use_backup_pin").performClick()
            assertTrue(backupClicks == 1)
        }
    }

    @Test
    fun settingsEditorAllTabsAndProfileContactEditorsRenderAndInteract() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Settings, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> SettingsEditor(handle.session, sampleProfile)
                        1 -> ContactEditor(handle.session, sampleProfile)
                        else -> SafeProfileScreen(handle.session, sampleProfile)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("settings_tab_password").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("change_password").assertExists()

            compose.onNodeWithTag("settings_tab_privacy").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("recent_file_caching").assertExists()

            compose.onNodeWithTag("settings_tab_permissions").performClick()
            compose.waitForIdle()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("safe_to_stay_c-1").assertExists()
            compose.onNodeWithTag("contact_name").performScrollTo().performTextInput("Taylor")
            compose.onNodeWithTag("save_contact").performScrollTo().performClick()

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithText("Alex Rivera").assertExists()
            compose.onNodeWithText("100 Main St, Edmonton, AB, T5J 1N3, Canada").assertExists()
        }
    }

    @Test
    fun onboardingProfileSetupAndQuestionnaireFormWork() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.ProfileSetup, localMode = true).use { handle ->
            var intake = SafetyPlanIntake()
            var saved = false
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> ProfileSetup(handle.session)
                        1 -> Column {
                            OnboardingProgress(1, 2, 0, 2, listOf(true, false))
                            RequirementBullet("At least 8 characters", met = true, active = true)
                            OnboardingNavigation(back = {}, next = {})
                            SafetyPlanQuestionnaireForm(intake = intake, enabled = true, onChange = { intake = it })
                        }
                        else -> SafetyPlanQuestionnaireDialog(
                            initialIntake = intake,
                            enabled = true,
                            onDismiss = {},
                            onSave = { saved = true },
                            inWindow = false,
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("intake_abuse_PHYSICAL").performClick()
            compose.onNodeWithTag("intake_immigration_CITIZEN").performClick()
            assertTrue(intake.abuseTypes.isNotEmpty())

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.onboarding_safety_questionnaire)).assertExists()
            assertTrue(!saved)
        }
    }
}
