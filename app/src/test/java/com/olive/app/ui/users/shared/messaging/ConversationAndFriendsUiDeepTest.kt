package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMembershipEvent
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w840dp-h915dp")
class ConversationAndFriendsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun helperFunctionsAndMessagesScreenTwoPaneAndChatPaneRenderAndInteract() {
        val archivedConv = ChatConversation("c-arch", "", listOf("owner", "p1"), mapOf("owner" to "alex", "p1" to "sam"), closedAt = 0L)
        val closedConv = ChatConversation("c-closed", "", listOf("owner", "p1"), mapOf("owner" to "alex", "p1" to "sam"), closedAt = 500L)
        assertTrue(isConversationArchived(archivedConv, setOf("c-arch"), emptySet()))
        assertTrue(isConversationArchived(closedConv, emptySet(), emptySet()))
        assertFalse(isConversationArchived(closedConv, emptySet(), setOf("c-closed")))
        assertTrue(visibleSupportTags(AccountRole.ADMIN).contains(SUPPORT_TAG_NEEDS_ADMIN))
        assertTrue(visibleSupportTags(AccountRole.STAFF).size == 3)
        assertTrue(visibleSupportTags(AccountRole.USER).isEmpty())

        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Portal, page = SafePage.Messages, localMode = false).use { handle ->
            val conv = ChatConversation(
                id = "c-sup-1",
                friendshipId = "",
                participantIds = listOf("owner", "user-1"),
                participantUsernames = mapOf("owner" to "staff_user", "user-1" to "alex"),
                type = "SUPPORT",
                ownerUid = "user-1",
                staffUid = "owner",
            )
            handle.session.runtime.state.profile = UserProfile(username = "staff_user", fullName = "Staff", role = AccountRole.STAFF)
            handle.session.runtime.state.conversations = listOf(conv, closedConv)
            handle.session.runtime.state.friends = listOf(Friend("f1", "user-1", "alex"))
            handle.session.runtime.state.activeConversation = conv
            handle.session.runtime.state.chatMessages = listOf(
                ChatMessage("m2", "owner", "staff_user", "SHARED_NOTE:n1:Plan note", 2000L),
                ChatMessage("m3", "user-1", "alex", "SHARED_MEDIA:d1:scan.jpg", 3000L),
                ChatMessage("m4", "user-1", "alex", "SHARED_LOCATION:9999999999", 4000L),
                ChatMessage("m6", "user-1", "alex", "Removed", 5000L, unsent = true),
                ChatMessage("m1", "user-1", "alex", "Hello support", 7000L),
            )
            var exited = false

            compose.setContent {
                OliveUiTestHost {
                    MessagesScreen(handle.session, exit = { exited = true })
                }
            }
            compose.mainClock.advanceTimeBy(50)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithText("Hello support").assertExists()
            compose.onNodeWithTag("request_admin_help").assertExists()
            compose.onNodeWithTag("close_support_chat").assertExists()
            compose.onNodeWithTag("safe_home").performClick()
            compose.mainClock.advanceTimeBy(150)
            assertTrue(exited)
        }
    }
}
