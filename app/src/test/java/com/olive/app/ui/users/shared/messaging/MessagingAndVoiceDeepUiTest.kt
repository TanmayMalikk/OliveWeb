package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.messaging.SUPPORT_TAG_SUPPORT
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipCallStatus
import com.olive.app.data.messaging.VoipSessionState
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
@Config(application = Application::class, sdk = [35], qualifiers = "w840dp-h915dp")
class MessagingAndVoiceDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val directChat = ChatConversation(
        id = "chat-dm-1",
        friendshipId = "f-1",
        participantIds = listOf("owner", "friend-1"),
        participantUsernames = mapOf("owner" to "alex_user", "friend-1" to "sam_contact"),
        lastMessage = "Checking in",
        updatedAt = 1_700_000_000_000L,
        lastMessageId = "m-1",
        lastSenderUid = "friend-1",
        type = "DIRECT",
    )

    private val supportChat = ChatConversation(
        id = "chat-sup-1",
        friendshipId = "",
        participantIds = listOf("owner", "staff-1"),
        participantUsernames = mapOf("owner" to "alex_user", "staff-1" to "staff_helper"),
        type = "SUPPORT",
        ownerUid = "owner",
        staffUid = "staff-1",
        supportTag = SUPPORT_TAG_NEEDS_ADMIN,
    )

    @Test
    fun messagesScreenSwitchesTabsExpandsFriendListsAndSearchesChat() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            handle.session.runtime.state.incomingFriendRequests = listOf(
                FriendRequest("req-1", "req-u", "taylor", "owner", "alex_user", 1000L),
            )
            handle.session.runtime.state.outgoingFriendRequests = listOf(
                FriendRequest("req-2", "owner", "alex_user", "out-u", "jordan", 2000L),
            )
            handle.session.runtime.state.conversations = listOf(directChat, supportChat)
            handle.session.runtime.state.activeConversation = directChat
            handle.session.runtime.state.chatMessages = listOf(
                ChatMessage("m-1", "owner", "alex_user", "Queued msg", 1_700_000_000_000L, deliveryStatus = MessageDeliveryStatus.QUEUED),
                ChatMessage("m-2", "owner", "alex_user", "Sending msg", 1_700_000_010_000L, deliveryStatus = MessageDeliveryStatus.SENDING),
                ChatMessage("m-3", "owner", "alex_user", "Failed retry msg", 1_700_000_020_000L, deliveryStatus = MessageDeliveryStatus.FAILED_RETRYABLE),
                ChatMessage("m-4", "owner", "alex_user", "Revoked attachment", 1_700_000_030_000L, deliveryStatus = MessageDeliveryStatus.FAILED_PERMANENT, deliveryFailureCode = "ATTACHMENT_ACCESS_REVOKED"),
                ChatMessage("m-5", "owner", "alex_user", "VOICE_MESSAGE:v-1:45000,123456", 1_700_000_040_000L, deliveryStatus = MessageDeliveryStatus.SENT),
                ChatMessage("m-6", "friend-1", "sam_contact", "SHARED_NOTE:note-1:Private Note", 1_700_000_050_000L),
            )

            compose.setContent {
                OliveUiTestHost {
                    Box(Modifier.fillMaxSize()) {
                        MessagesScreen(handle.session, exit = {})
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("message_status_m-3").assertExists()
            compose.onNodeWithTag("message_status_m-4").assertExists()
            compose.onNodeWithTag("chat_search_toggle").performClick()
            compose.onNodeWithTag("messages_tickets_tab").performClick()
            compose.onNodeWithTag("messages_dms_tab").performClick()
            compose.onNodeWithTag("archived_chats").performClick()
            compose.onNodeWithTag("archived_chats").performClick()
        }
    }

    @Test
    fun voiceComposerBubbleAndFriendNoteDialogsRender() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> Column {
                            VoiceMessageComposer(
                                enabled = true,
                                withMicrophonePermission = { it() },
                                onSend = { _, _ -> true },
                            ) { _, recordButton -> recordButton?.invoke() }
                            VoiceMessageBubble(
                                mediaId = "v-1",
                                metadata = "45000,123456",
                                session = handle.session,
                                mine = true,
                            )
                            VoiceMessageUploadStatus()
                        }
                        1 -> LazyColumn {
                            friendLists(
                                session = handle.session,
                                pendingExpanded = true,
                                onPendingExpandedChange = {},
                                outgoingExpanded = true,
                                onOutgoingExpandedChange = {},
                                friendsExpanded = true,
                                onFriendsExpandedChange = {},
                                onNoteTargets = {},
                            )
                        }
                        else -> PrivateContactNoteDialog(
                            session = handle.session,
                            targets = listOf(DirectoryUser("friend-1", "sam_contact")),
                            onDismiss = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("record_voice_message").assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.friends)).assertExists()

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithText("sam_contact", substring = true).assertExists()
        }
    }

    @Test
    fun endedAndLeavingVoiceCallStatesRender() {
        val sampleCall = VoipCall(
            callId = "call-2",
            name = "Support Call",
            callerId = "friend-1",
            participants = emptyList(),
            callerName = "sam_contact",
            conversationId = "chat-dm-1",
            status = VoipCallStatus.ENDED,
            createdAt = 1_700_000_000_000L,
        )
        var stage by mutableIntStateOf(0)
        compose.setContent {
            OliveUiTestHost {
                when (stage) {
                    0 -> ActiveCallDialog(
                        state = VoipSessionState.Leaving(sampleCall),
                        currentUid = "owner",
                        onMute = {},
                        onSpeaker = {},
                        onFinish = {},
                        onMinimize = {},
                        onQuickExit = {},
                    )
                    else -> ActiveCallDialog(
                        state = VoipSessionState.Error(sampleCall, "Call ended"),
                        currentUid = "owner",
                        onMute = {},
                        onSpeaker = {},
                        onFinish = {},
                        onMinimize = {},
                        onQuickExit = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        stage = 1
        compose.waitForIdle()
    }
}
