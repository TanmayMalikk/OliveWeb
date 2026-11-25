package com.olive.app.ui.users.staff

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.SafetyConsultation
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
class StaffDashboardUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun staffPortalNavigatesSafetyConsultationsAndAvailability() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Safe, localMode = true).use { handle ->
            val session = handle.session
            session.safetyPlans.consultations = listOf(
                SafetyConsultation(
                    ownerUid = "user-1",
                    ownerName = "Riley",
                    conversationId = "conv-1",
                    staffUid = session.coverAccountId ?: "staff-1",
                    staffName = "Jordan",
                    ticketNumber = 7L,
                ),
                SafetyConsultation(
                    ownerUid = "user-2",
                    ownerName = "Morgan",
                    conversationId = "",
                    staffUid = "",
                    staffName = "",
                    ticketNumber = -1L,
                ),
            )

            compose.setContent {
                OliveUiTestHost {
                    StaffPortal(session)
                }
            }
            compose.mainClock.advanceTimeBy(250)

            compose.onNodeWithText(context.getString(R.string.safety_inbox)).performClick()
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithTag("safety_staff_available").assertExists()
            compose.onNodeWithTag("safety_staff_available").performClick()
            compose.mainClock.advanceTimeBy(200)

            compose.onNodeWithText("Consult Ticket #7").assertExists()
            compose.onNodeWithTag("safety_consultation_user-1").performClick()
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithTag("complete_safety_consultation").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }

    @Test
    fun adminRoleShowsAssignedAndWaitingConsultationGroups() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Safe, localMode = true).use { handle ->
            val session = handle.session
            session.safetyPlans.consultations = listOf(
                SafetyConsultation("u1", "A", "c1", "s1", "Alex Staff", 11L),
                SafetyConsultation("u2", "B", "c2", "", "", 12L),
            )
            compose.setContent {
                OliveUiTestHost {
                    SafetyConsultations(session, onBack = {})
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Consult Ticket #11").assertExists()
            compose.onNodeWithText("Consult Ticket #12").assertExists()
        }
    }
}
