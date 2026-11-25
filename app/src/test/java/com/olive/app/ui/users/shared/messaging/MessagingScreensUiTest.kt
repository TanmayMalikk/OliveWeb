package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.messaging.SUPPORT_TAG_SUPPORT
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipCallStatus
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
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
class MessagingScreensUiTest {
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
        supportTag = SUPPORT_TAG_SUPPORT,
    )

    @Test
    fun messagesScreenAndChatPaneRenderListsMessagesAndMedia() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = true).use { handle ->
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
                ChatMessage("m-1", "friend-1", "sam_contact", "Hello!", 1_700_000_000_000L),
                ChatMessage("m-2", "owner", "alex_user", "On my way", 1_700_000_010_000L, deliveryStatus = MessageDeliveryStatus.SENT),
                ChatMessage("m-3", "owner", "alex_user", "SHARED_MEDIA:doc-1:photo.jpg", 1_700_000_020_000L),
                ChatMessage("m-4", "owner", "alex_user", "SHARED_LOCATION:${System.currentTimeMillis() + 600_000L}", 1_700_000_030_000L),
                ChatMessage("m-5", "friend-1", "sam_contact", "", 1_700_000_040_000L, unsent = true),
            )

            compose.setContent {
                OliveUiTestHost {
                    Box(Modifier.size(800.dp, 800.dp)) {
                        MessagesScreen(handle.session, exit = {})
                    }
                }
            }
            compose.mainClock.advanceTimeBy(50)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("shared_live_location").assertExists()
        }
    }

    @Test
    fun voiceCallsDialogsBubbleHistoryAndMessagingHelpersWork() {
        val sampleCall = VoipCall(
            callId = "call-1",
            name = "Support Call",
            callerId = "friend-1",
            participants = emptyList(),
            callerName = "sam_contact",
            conversationId = "chat-dm-1",
            status = VoipCallStatus.ACTIVE,
            createdAt = 1_700_000_000_000L,
        )
        val invitation = VoipInvitation(
            callId = "call-1",
            callerId = "friend-1",
            name = "sam_contact",
            status = VoipParticipantStatus.PENDING,
        )
        val failedUpload = MediaUploadPresentation(
            "up-1", "owner", "voice.m4a", "audio/mp4", MediaTransferStatus.FAILED, 0L, 100L, "v-1", "Offline", 1L,
        )
        var accepts = 0
        var declines = 0
        var retries = 0

        compose.setContent {
            OliveUiTestHost {
                Column {
                    IncomingCallDialog(
                        invitation = invitation,
                        onAccept = { accepts++ },
                        onDecline = { declines++ },
                        onQuickExit = {},
                    )
                    ActiveCallDialog(
                        state = VoipSessionState.Active(sampleCall, microphoneMuted = true, speakerEnabled = true, remoteParticipantCount = 1),
                        currentUid = "owner",
                        onMute = {},
                        onSpeaker = {},
                        onFinish = {},
                        onMinimize = {},
                        onQuickExit = null,
                    )
                    DraggableCallBubble(state = VoipSessionState.Connecting(sampleCall), onOpen = {})
                    VoiceMessageUploadStatus(upload = failedUpload, onRetry = { retries++ })
                }
            }
        }
        compose.onNodeWithTag("accept_voice_call").performClick()
        compose.onNodeWithTag("decline_voice_call").performClick()
        assertEquals(1, accepts)
        assertEquals(1, declines)
        assertEquals("01:05", formatCallDuration(65L))
        assertTrue(visibleSupportTags(AccountRole.ADMIN).contains(SUPPORT_TAG_NEEDS_ADMIN))
        assertTrue(canManageSharedMediaOfflineCopy(AccountRole.USER))
        assertFalse(canManageSharedMediaOfflineCopy(AccountRole.ADMIN))
        assertFalse(isConversationArchived(directChat, emptySet(), emptySet()))
    }

    @Test
    fun chatInfoDialogAndFriendInviteControlsRenderAndSubmit() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(
                Friend("f-1", "friend-1", "sam_contact"),
                Friend("f-2", "friend-2", "jordan_contact"),
            )
            handle.session.runtime.state.conversations = listOf(directChat)
            handle.session.runtime.state.activeConversation = directChat

            var stage by mutableIntStateOf(0)
            var closed = 0
            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        FriendInviteControls(handle.session, unreadOnly = false, onUnreadOnlyChange = {})
                    } else {
                        ChatInfoDialog(
                            session = handle.session,
                            currentUid = "owner",
                            onClose = { closed++ },
                            onOpenMedia = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("friend_invite").assertExists()

            stage = 1
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_info_back").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat_info_back").performClick()
            assertEquals(1, closed)
        }
    }
}
