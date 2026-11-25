package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.UserProfile
import com.olive.app.data.safety.SafetyConsultation
import com.olive.app.shouldApplyTaskIdentity
import com.olive.app.shouldBlankRecentPreview
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.admin.AdminAccounts
import com.olive.app.ui.users.shared.auth.Credentials
import com.olive.app.ui.users.shared.organization.OrganizationProfileSetup
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import com.olive.app.ui.users.shared.walkthrough.SafeWalkthroughScreen
import com.olive.app.ui.users.shared.walkthrough.WalkthroughCoachmarkOverlay
import com.olive.app.ui.users.shared.walkthrough.walkthroughTourStepsForRole
import com.olive.app.ui.users.staff.SafetyConsultations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class NavigationWalkthroughAndOrgUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun oliveRootCoversLoadingErrorBlackoutAndIdleLockStates() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Loading, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    OliveRoot(handle.session, closeApp = {})
                }
            }
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()

            for (screen in listOf(
                SessionScreen.StorageError,
                SessionScreen.Welcome,
                SessionScreen.NewCredentials,
                SessionScreen.ReturningCredentials,
                SessionScreen.ProfileSetup,
                SessionScreen.TriggerSetup,
                SessionScreen.DevicePin,
                SessionScreen.Pin,
            )) {
                compose.runOnIdle { handle.session.runtime.state.screen = screen }
                compose.mainClock.advanceTimeBy(150)
                compose.waitForIdle()
            }

            compose.runOnIdle {
                handle.session.runtime.state.privacyBlackout = true
            }
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("privacy_blackout").assertExists()

            assertTrue(shouldBlankRecentPreview(protectionEnabled = true, sensitive = true))
            assertFalse(shouldBlankRecentPreview(protectionEnabled = false, sensitive = true))
            assertTrue(shouldApplyTaskIdentity(null, CoverType.FITNESS, AccountRole.USER))
            assertFalse(shouldApplyTaskIdentity(CoverType.FITNESS to AccountRole.USER, CoverType.FITNESS, AccountRole.USER))
        }
    }

    @Test
    fun organizationSettingsAdminAccountsConsultationsAndCredentialsWork() {
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            handle.session.runtime.state.profile = UserProfile(
                username = "admin_1",
                fullName = "Admin One",
                email = "admin@example.com",
                phone = "555-0100",
                dob = "1990-01-01",
                role = AccountRole.ADMIN,
            )
            handle.session.runtime.state.adminAccounts = listOf(
                AdminAccountSummary("u-1", "user_1", AccountRole.USER, "User One", "u1@example.com", "555-0101", profileComplete = true, protected = false),
                AdminAccountSummary("s-1", "staff_1", AccountRole.STAFF, "Staff One", "s1@example.com", "555-0102", profileComplete = false, protected = true),
            )

            var stage by mutableIntStateOf(0)
            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> OrganizationSettings(handle.session, onBack = {})
                        1 -> OrganizationProfileSetup(handle.session)
                        2 -> AdminAccounts(handle.session, onBack = {}, onCreate = {}, onDelete = {})
                        3 -> SafetyConsultations(handle.session, onBack = {})
                        else -> Credentials(handle.session, create = false)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Admin One").assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.accounts)).assertExists()

            stage = 3
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.safety_inbox)).assertExists()

            stage = 4
            compose.waitForIdle()
            compose.onNodeWithTag("username").performTextInput("user_1")
            compose.onNodeWithTag("password").performTextInput("ValidPass1!")
        }
    }

    @Test
    fun walkthroughOverlayAndContentsScreenRenderAndAdvance() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Walkthrough, localMode = true).use { handle ->
            var advancedTo: String? = ""
            var skipped = 0
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        Box(Modifier.fillMaxSize()) {
                            WalkthroughCoachmarkOverlay(
                                role = AccountRole.USER,
                                activeStepId = walkthroughTourStepsForRole(AccountRole.USER).first().id,
                                showBottomControls = true,
                                onAdvance = { advancedTo = it },
                                onOpenContents = {},
                                onSkip = { skipped++ },
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            SafeWalkthroughScreen(handle.session)
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("walkthrough_tour_next").performClick()
            assertTrue(advancedTo != "")

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("walkthrough_toc").assertExists()
            assertTrue(skipped == 0)
        }
    }
}
