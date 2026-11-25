package com.olive.app.ui.users.shared.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.organization.OrganizationProfileSetup
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AccountOnboardingAndOrgDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun onboardingProgressDrawsMultiPageBranchAndNavigation() {
        var page by mutableIntStateOf(0)
        var backClicks = 0
        var nextClicks = 0
        compose.setContent {
            OliveUiTestHost {
                Column {
                    OnboardingProgress(
                        completed = page + 1,
                        total = 4,
                        page = page,
                        pages = 4,
                        pageComplete = listOf(page >= 1, page >= 2, page >= 3, false),
                    )
                    OnboardingNavigation(
                        back = { backClicks++; if (page > 0) page-- },
                        next = { nextClicks++; if (page < 3) page++ },
                        last = page == 3,
                    )
                }
            }
        }
        compose.waitForIdle()
        repeat(3) {
            compose.onNodeWithTag("onboarding_next").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText(context.getString(R.string.back)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun organizationSettingsAndProfileSetupExerciseValidationTabsAndSteps() {
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val session = handle.session
            val profile = UserProfile(
                username = "staff_one",
                fullName = "Staff Member",
                dob = "1990-02-02",
                email = "staff@olive.org",
                phone = "780-555-0101",
                role = AccountRole.STAFF,
            )
            session.runtime.state.profile = profile
            session.runtime.pendingProfile = profile
            var showSetup by mutableStateOf(false)

            compose.setContent {
                OliveUiTestHost {
                    if (!showSetup) {
                        OrganizationSettings(session, onBack = {})
                    } else {
                        OrganizationProfileSetup(session)
                    }
                }
            }
            compose.waitForIdle()

            // Validation fail and valid save on Profile tab
            compose.onNodeWithTag("organization_settings_name").performScrollTo().performTextReplacement("")
            compose.onNodeWithText(context.getString(R.string.save_profile)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("organization_settings_name").performScrollTo().performTextReplacement("Staff Updated")
            compose.onNodeWithText(context.getString(R.string.save_profile)).performScrollTo().performClick()
            compose.waitForIdle()

            // Switch to Password tab and trigger validation + submit
            compose.onNodeWithTag("settings_tab_password").performClick()
            compose.waitForIdle()
            val changePasswordBtn = hasText(context.getString(R.string.change_password)) and hasClickAction()
            compose.onNode(changePasswordBtn).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("organization_current_password").performScrollTo().performTextReplacement("OldPass1!")
            compose.onNodeWithTag("organization_new_password").performScrollTo().performTextReplacement("ValidPass1!")
            compose.onNodeWithTag("organization_confirm_password").performScrollTo().performTextReplacement("ValidPass1!")
            compose.onNode(changePasswordBtn).performScrollTo().performClick()
            compose.waitForIdle()

            // Permissions tab
            compose.onNodeWithTag("settings_tab_permissions").performClick()
            compose.waitForIdle()

            // Switch to OrganizationProfileSetup wizard
            showSetup = true
            compose.waitForIdle()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("organization_phone").performScrollTo().performTextReplacement("780-555-0188")
            compose.onNodeWithTag("organization_profile_submit").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }
}
