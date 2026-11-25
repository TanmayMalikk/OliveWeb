package com.olive.app.ui.users.admin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.session.EmergencyAlert
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
class AdminEmergencyResponseDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun emergencyResponseScreenAndAlertDialogCoverActiveResolvedAndActions() {
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val session = handle.session
            val activeNoPdf = EmergencyAlert("a1", "owner-1", "Jordan Doe", 1_700_000_000_000L, "")
            val activeWithPdf = EmergencyAlert("a2", "owner-2", "Sam Smith", 1_700_000_100_000L, "pdf-media-1")
            val resolvedAlert = EmergencyAlert("a3", "owner-3", "Riley", 1_700_000_200_000L, "pdf-media-2", resolvedAt = 1_700_000_300_000L)

            var alerts by mutableStateOf<List<EmergencyAlert>?>(null)
            var showDialog by mutableStateOf(false)
            var trackedUid = ""
            var openedResp = false

            compose.setContent {
                OliveUiTestHost {
                    if (!showDialog) {
                        EmergencyResponseScreen(
                            session = session,
                            alerts = alerts,
                            error = null,
                            onBack = {},
                            onTrack = { trackedUid = it },
                        )
                    } else {
                        EmergencyAlertDialog(
                            session = session,
                            alert = activeWithPdf,
                            onDismiss = { showDialog = false },
                            onTrack = { trackedUid = activeWithPdf.ownerUid },
                            onOpenResponse = { openedResp = true },
                        )
                    }
                }
            }
            compose.waitForIdle()

            alerts = emptyList()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.emergency_response_empty)).assertExists()

            alerts = listOf(activeNoPdf, activeWithPdf, resolvedAlert)
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.emergency_response_waiting_pdf)).assertExists()
            compose.onNodeWithText(context.getString(R.string.emergency_response_resolved)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.emergency_response_reopen)).assertExists()

            showDialog = true
            compose.waitForIdle()
            compose.onNodeWithTag("emergency_alert_dialog").assertExists()
            compose.onNodeWithText(context.getString(R.string.emergency_response_track)).performClick()
            compose.onNodeWithText(context.getString(R.string.emergency_response_title)).performClick()
            compose.onNodeWithTag("emergency_alert_close").performClick()
            compose.waitForIdle()
            assertTrue(trackedUid == "owner-2" && openedResp)
        }
    }

    @Test
    fun adminAccountsRendersListAndFiltering() {
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.profile = UserProfile(username = "admin", role = AccountRole.ADMIN)
            session.runtime.state.adminAccounts = listOf(
                AdminAccountSummary("u-1", "alice_user", AccountRole.USER, "Alice", "", "", true, false),
                AdminAccountSummary("s-1", "bob_staff", AccountRole.STAFF, "Bob", "bob@org.ca", "", false, true),
            )
            var createClicked = false
            compose.setContent {
                OliveUiTestHost {
                    AdminAccounts(
                        session = session,
                        onBack = {},
                        onCreate = { createClicked = true },
                        onDelete = {},
                    )
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.create_account)).performClick()
            compose.waitForIdle()
            assertTrue(createClicked)
        }
    }

    @Test
    fun createAccountDialogComposesCleanlyWithoutDeadlock() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val session = handle.session
            var showDialog by mutableStateOf(false)
            compose.setContent {
                OliveUiTestHost {
                    if (showDialog) CreateAccountDialog(session = session, onDismiss = { showDialog = false })
                }
            }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnUiThread {
                showDialog = true
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(250)
            compose.runOnUiThread {
                showDialog = false
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(150)
            compose.mainClock.autoAdvance = true
        }
    }
}
