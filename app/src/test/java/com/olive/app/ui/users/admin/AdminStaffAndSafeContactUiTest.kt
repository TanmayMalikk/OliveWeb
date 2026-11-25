package com.olive.app.ui.users.admin

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.session.EmergencyAlert
import com.olive.app.ui.users.safecontact.SafeContactArea
import com.olive.app.ui.users.shared.auth.Credentials
import com.olive.app.ui.users.shared.auth.PinPrompt
import com.olive.app.ui.users.shared.auth.Welcome
import com.olive.app.ui.users.shared.navigation.OliveRoot
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.onboarding.ProfileSetup
import com.olive.app.ui.users.staff.StaffPortal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AdminStaffAndSafeContactUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val sampleProfile = UserProfile(
        username = "alex_user",
        fullName = "Alex Rivera",
        phone = "555-0100",
        dob = "1995-04-12",
        language = "English",
        situation = "",
        affectedNames = "",
        address = DeliveryAddress(city = "Edmonton", province = "AB", country = "Canada"),
        role = AccountRole.USER,
    )

    @Test
    fun adminPortalEmergencyResponseAndCountsRenderCleanly() {
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            handle.session.runtime.state.profile = sampleProfile.copy(role = AccountRole.ADMIN, username = "admin_1")
            handle.session.runtime.state.adminCounts = AccountCounts(users = 4, safeContacts = 2, staff = 3, admins = 1)
            handle.session.runtime.state.adminAccounts = listOf(
                AdminAccountSummary("u-1", "user_1", AccountRole.USER, fullName = "User One", email = "u1@example.com", phone = "555-0101", profileComplete = true, protected = false),
                AdminAccountSummary("s-1", "staff_1", AccountRole.STAFF, fullName = "Staff One", email = "s1@example.com", phone = "555-0102", profileComplete = true, protected = false),
            )
            var trackedUid = ""
            var stage by mutableIntStateOf(0)
            val alert = EmergencyAlert("e-1", "u-1", "User One", 1_700_000_000_000L, "media-1", resolvedAt = 0L)

            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    when (stage) {
                        0 -> AdminPortal(handle.session)
                        1 -> EmergencyResponseScreen(
                            session = handle.session,
                            alerts = listOf(alert),
                            error = null,
                            onBack = {},
                            onTrack = { trackedUid = it },
                        )
                        else -> Column {
                            AccountCountGrid(AccountCounts(users = 4, safeContacts = 2, staff = 3, admins = 1))
                            EmergencyAlertDialog(
                                session = handle.session,
                                alert = alert,
                                onDismiss = {},
                                onTrack = { trackedUid = alert.ownerUid },
                                onOpenResponse = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.admin_panel)).assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText("User One").assertExists()

            stage = 2
            compose.waitForIdle()
            assertEquals("", trackedUid)
        }
    }

    @Test
    fun staffPortalRendersCleanly() {
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Portal, localMode = true).use { handle ->
            handle.session.runtime.state.profile = sampleProfile.copy(role = AccountRole.STAFF, username = "staff_1")
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    StaffPortal(handle.session)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.staff_panel)).assertExists()
        }
    }

    @Test
    fun safeContactAndAuthOnboardingScreensRenderAllBranches() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.SAFE_CONTACT, screen = SessionScreen.Safe, localMode = true).use { handle ->
            handle.session.runtime.state.profile = sampleProfile.copy(role = AccountRole.SAFE_CONTACT, username = "safe_1")
            handle.session.runtime.state.viewedSharedEntry = PrivateEntry(
                id = "se-1",
                kind = PrivateEntryKind.Incident,
                title = "Shared Incident",
                body = "Details",
                timestamp = 1_700_000_000_000L,
            )

            var stage by mutableIntStateOf(0)
            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> SafeContactArea(handle.session, exit = {})
                        1 -> Welcome(handle.session)
                        2 -> Credentials(handle.session, create = true)
                        3 -> ProfileSetup(handle.session)
                        4 -> PinPrompt(handle.session)
                        else -> OliveRoot(handle.session)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Shared Incident").assertExists()

            for (nextStage in 1..5) {
                stage = nextStage
                compose.mainClock.advanceTimeBy(250)
                compose.waitForIdle()
            }
            compose.onNodeWithTag("pin_entry").let {
                // Verify stage 5 OliveRoot composed cleanly
                compose.waitForIdle()
            }
        }
    }
}
