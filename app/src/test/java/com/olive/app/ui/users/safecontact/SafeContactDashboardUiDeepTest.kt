package com.olive.app.ui.users.safecontact

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafeContactDashboardUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safeContactHomeProfileAndPendingSyncNavigateCleanly() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(
            role = AccountRole.SAFE_CONTACT,
            screen = SessionScreen.Safe,
            page = SafePage.Home,
            localMode = true,
        ).use { handle ->
            val session = handle.session
            val populatedProfile = (session.profile ?: UserProfile(
                username = "safe_contact_user",
                fullName = "Sam Contact",
                dob = "1990-01-01",
                region = "Edmonton",
                address = DeliveryAddress(city = "Edmonton"),
                role = AccountRole.SAFE_CONTACT,
            )).copy(fullName = "Sam Contact", dob = "1990-01-01", region = "Edmonton")
            session.runtime.state.profile = populatedProfile
            session.runtime.state.pendingSync = true
            var exited = false

            compose.setContent {
                OliveUiTestHost(onQuickExit = { exited = true }) {
                    SafeContactArea(session, exit = { exited = true })
                }
            }
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithTag("safe_contact_profile").performClick()
            compose.mainClock.advanceTimeBy(450)
            compose.onNodeWithTag("safe_contact_profile_back").assertExists()
            compose.onNodeWithTag("safe_contact_edit_profile").performClick()
            compose.mainClock.advanceTimeBy(350)
            assertTrue(session.page == SafePage.Settings)
            assertTrue(!exited)
        }
    }

    @Test
    fun sharedEntryViewerRendersIncidentAndCloses() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(
            role = AccountRole.SAFE_CONTACT,
            screen = SessionScreen.Safe,
            page = SafePage.Home,
            localMode = true,
        ).use { handle ->
            val session = handle.session
            session.runtime.state.viewedSharedEntry = PrivateEntry(
                id = "inc-1",
                kind = PrivateEntryKind.Incident,
                title = "Incident Report",
                body = "Observed outside building",
                incidentDate = "2026-05-01",
                incidentTime = "14:00",
                incidentLocation = "Jasper Ave",
                attachmentIds = listOf("att-1"),
            )

            compose.setContent {
                OliveUiTestHost(onQuickExit = {}) {
                    SharedEntryViewer(session)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.secure_shared_entry)).assertExists()
            compose.onAllNodesWithText("Incident Report")[0].assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.close_shared_entry)).performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }

    @Test
    fun sharedEntryViewerRendersNoteEntry() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(
            role = AccountRole.SAFE_CONTACT,
            screen = SessionScreen.Safe,
            page = SafePage.Home,
            localMode = true,
        ).use { handle ->
            val session = handle.session
            session.runtime.state.viewedSharedEntry = PrivateEntry(
                id = "note-1",
                kind = PrivateEntryKind.Note,
                title = "Note Title",
                body = "Safe contact body text",
            )

            compose.setContent {
                OliveUiTestHost(onQuickExit = {}) {
                    SharedEntryViewer(session)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Safe contact body text").assertExists()
        }
    }

    @Test
    fun safeContactHelperCardsRenderOverflowBadgeAndBlankFallback() {
        compose.mainClock.autoAdvance = false
        val blankProfile = UserProfile(
            username = "sc_blank",
            fullName = "",
            dob = "",
            region = "",
            address = DeliveryAddress(city = "Edmonton"),
            role = AccountRole.SAFE_CONTACT,
        )
        compose.setContent {
            OliveUiTestHost {
                Column {
                    SafeContactActionCard(
                        label = "Alerts",
                        description = "Pending badge overflow",
                        icon = Icons.Outlined.Description,
                        tag = "test_overflow_badge_card",
                        badgeCount = 120,
                        onClick = {},
                    )
                    SafeContactIdentityCard(blankProfile, onClick = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("99+").assertExists()
        compose.onNodeWithText("?").assertExists()
    }
}
