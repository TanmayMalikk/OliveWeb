package com.olive.app.ui.users.admin

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.olive.app.R
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AdminDashboardAndWorkloadDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun adminPortalNavigatesThroughDashboardAndStaffWorkloadCards() {
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "admin-1"
            session.runtime.state.profile = UserProfile(
                username = "admin_root",
                fullName = "Admin Root",
                dob = "1985-04-04",
                email = "admin@olive.org",
                role = AccountRole.ADMIN,
            )
            val counts = AccountCounts(
                users = 12,
                safeContacts = 6,
                staff = 4,
                admins = 2,
                openTickets = 3,
                closedTickets = 8,
            )
            session.runtime.state.adminCounts = counts
            val staffSummary = AdminAccountSummary(
                uid = "staff-1",
                username = "staff_taylor",
                role = AccountRole.STAFF,
                fullName = "Taylor Staff",
                email = "taylor@olive.org",
                phone = "780-555-0140",
                profileComplete = true,
                protected = false,
                safetyPlans = 5,
                completedSafetyPlans = 3,
                supportChats = 4,
                closedSupportChats = 2,
            )
            session.runtime.state.adminAccounts = listOf(staffSummary)

            compose.setContent {
                OliveUiTestHost {
                    AdminPortal(session)
                }
            }
            compose.waitForIdle()

            // Navigate to Staff Management, wait for ensureAdminStaff(), then verify workload card renders
            compose.onNodeWithText(context.getString(R.string.admin_staff_management)).performScrollTo().performClick()
            compose.waitForIdle()
            session.runtime.adminPreloader.adopt(listOf(staffSummary), counts)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("staff_taylor", substring = true).assertExists()

            // Trigger refresh on Staff Management
            compose.onNodeWithText(context.getString(R.string.refresh)).performClick()
            compose.waitForIdle()
        }
    }
}
