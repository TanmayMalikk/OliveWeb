package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class MessagingAndFriendsInteractiveDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun messagesScreenWithRequestsFriendsAndActiveChatExercisesActionsAndQuickExitHint() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "u1"
            session.runtime.state.friends = listOf(
                Friend("fs-1", "friend-1", "jordan_f"),
            )
            session.runtime.state.incomingFriendRequests = listOf(
                FriendRequest("req-in-1", "sender-1", "alex_sender", selfUid, "me", 1000L),
                FriendRequest("req-in-2", "sender-2", "pat_sender", selfUid, "me", 1100L),
            )
            session.runtime.state.outgoingFriendRequests = listOf(
                FriendRequest("req-out-1", selfUid, "me", "recip-1", "morgan_out", 1200L),
            )
            val conv = ChatConversation(
                id = "conv-direct-1",
                friendshipId = "fs-1",
                participantIds = listOf(selfUid, "friend-1"),
                participantUsernames = mapOf(selfUid to "me", "friend-1" to "jordan_f"),
                lastMessage = "Hello",
                updatedAt = 2000L,
            )
            session.runtime.state.conversations = listOf(conv)
            session.runtime.state.chatQuickExitHintVisible = true

            compose.setContent {
                OliveUiTestHost {
                    MessagesScreen(session, exit = {})
                }
            }
            compose.waitForIdle()

            // Expand incoming requests section and exercise accept/decline
            val incomingHeader = context.getString(R.string.incoming_friend_requests)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(incomingHeader))
            compose.onNodeWithText(incomingHeader).performClick()
            compose.waitForIdle()

            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("accept_friend_req-in-1"))
            compose.onNodeWithTag("accept_friend_req-in-1").performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("decline_friend_req-in-2"))
            compose.onNodeWithTag("decline_friend_req-in-2").performClick()

            // Expand outgoing requests and friends sections
            val outgoingHeader = context.getString(R.string.outgoing_friend_requests)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(outgoingHeader))
            compose.onNodeWithText(outgoingHeader).performClick()
            compose.waitForIdle()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("cancel_friend_req-out-1"))
            compose.onNodeWithTag("cancel_friend_req-out-1").performClick()

            val friendsHeader = context.getString(R.string.friends)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(friendsHeader))
            compose.onNodeWithText(friendsHeader).performClick()
            compose.waitForIdle()

            // Open conversation directly to show ChatPane + Quick Exit popup + dismiss
            session.runtime.state.activeConversation = conv
            compose.waitForIdle()
            compose.onNodeWithTag("dismiss_chat_quick_exit_hint").assertExists().performClick()
            compose.waitForIdle()

            // Toggle loading/failed state branches
            session.runtime.state.conversationLoadFailed = true
            compose.waitForIdle()
        }
    }
}
