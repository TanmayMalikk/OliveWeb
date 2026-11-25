package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class ConversationTimelineAndFiltersDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun staffMessagesFilterTabsAndChatPaneDeliveryStatusesRenderCleanly() {
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Portal, page = SafePage.Messages, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "staff-1"
            val ticket = ChatConversation(
                id = "conv-support-1",
                friendshipId = "tk-1",
                participantIds = listOf("user-1", selfUid),
                participantUsernames = mapOf("user-1" to "user_one", selfUid to "staff_one"),
                type = "SUPPORT",
                supportTag = "support",
                ownerUid = "user-1",
                staffUid = selfUid,
                lastMessage = "Need help",
                updatedAt = 2000L,
            )
            session.runtime.state.conversations = listOf(ticket)
            session.runtime.state.chatMessages = listOf(
                ChatMessage("m1", selfUid, "staff_one", "Queued note", 1000L, deliveryStatus = MessageDeliveryStatus.QUEUED),
                ChatMessage("m2", selfUid, "staff_one", "Retryable", 1100L, deliveryStatus = MessageDeliveryStatus.FAILED_RETRYABLE),
                ChatMessage("m3", selfUid, "staff_one", "Perm fail", 1200L, deliveryStatus = MessageDeliveryStatus.FAILED_PERMANENT, deliveryFailureCode = "ATTACHMENT_ACCESS_REVOKED"),
                ChatMessage("m4", selfUid, "staff_one", "Sending", 1300L, deliveryStatus = MessageDeliveryStatus.SENDING),
                ChatMessage("m5", "user-1", "user_one", "Incoming text", 1400L),
            )

            compose.setContent {
                OliveUiTestHost {
                    MessagesScreen(session, exit = {})
                }
            }
            compose.waitForIdle()

            // Switch support filter tabs as STAFF
            compose.onNodeWithTag("support_filter_plan_consults").performClick()
            compose.onNodeWithTag("support_filter_needs_admin").performClick()
            compose.waitForIdle()

            // Open ChatPane and inspect delivery statuses
            session.runtime.state.activeConversation = ticket
            compose.waitForIdle()
            compose.onNodeWithTag("message_status_m2").performClick()
            compose.onNodeWithTag("message_status_m3").performClick()
            compose.waitForIdle()
        }
    }
}
