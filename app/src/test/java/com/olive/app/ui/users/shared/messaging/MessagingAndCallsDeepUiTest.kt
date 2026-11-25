package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipCallStatus
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.VoipSessionState
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
@Config(application = Application::class, sdk = [35], qualifiers = "w840dp-h915dp")
class MessagingAndCallsDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val groupChat = ChatConversation(
        id = "chat-grp-1",
        friendshipId = "",
        participantIds = listOf("owner", "friend-1"),
        participantUsernames = mapOf("owner" to "alex", "friend-1" to "sam"),
        type = "GROUP",
        ownerUid = "owner",
    )

    @Test
    fun chatInfoDialogCoversMembersStaffRequestAndModerationActions() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(
                Friend("f-1", "friend-1", "sam"),
                Friend("f-2", "friend-2", "jordan"),
            )
            handle.session.runtime.state.conversations = listOf(groupChat)
            handle.session.runtime.state.activeConversation = groupChat
            handle.session.runtime.state.chatMessages = listOf(
                ChatMessage("m-1", "owner", "alex", "Hello everyone", 1_700_000_000_000L, deliveryStatus = MessageDeliveryStatus.SENT),
                ChatMessage("m-2", "friend-1", "sam", "VOICE_MESSAGE:v-1:20000,100", 1_700_000_010_000L),
            )
            var closed = 0

            compose.setContent {
                OliveUiTestHost {
                    Box(Modifier.fillMaxSize()) {
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
            compose.onNodeWithTag("chat_info_screen").assertExists()
            compose.onNodeWithTag("chat_member_friend-1").assertExists()
            compose.onNodeWithTag("chat_add_people").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("chat_request_staff").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("chat_info_back").performClick()
            assertTrue(closed >= 1)
        }
    }

    @Test
    fun voiceCallDialogsAndCallBubbleRenderConnectingActiveAndIncomingStates() {
        val sampleCall = VoipCall(
            callId = "call-1",
            name = "Support Call",
            callerId = "owner",
            participants = listOf(
                VoipParticipant("owner", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("friend-1", VoipParticipantStatus.PENDING),
            ),
            callerName = "Alex",
            conversationId = "chat-sup-1",
            status = VoipCallStatus.ACTIVE,
            createdAt = 1_700_000_000_000L,
            acceptedAt = 1_700_000_005_000L,
        )
        val invite = VoipInvitation(
            callId = "call-1",
            callerId = "friend-1",
            name = "Sam",
            status = VoipParticipantStatus.PENDING,
        )
        var stage by mutableIntStateOf(0)
        var accepts = 0
        var declines = 0
        var mutes = 0
        var speakers = 0
        var finishes = 0
        var minimizes = 0

        compose.setContent {
            OliveUiTestHost {
                when (stage) {
                    0 -> IncomingCallDialog(
                        invitation = invite,
                        onAccept = { accepts++ },
                        onDecline = { declines++ },
                        onQuickExit = {},
                    )
                    1 -> ActiveCallDialog(
                        state = VoipSessionState.Active(sampleCall, microphoneMuted = false, speakerEnabled = false),
                        currentUid = "owner",
                        onMute = { mutes++ },
                        onSpeaker = { speakers++ },
                        onFinish = { finishes++ },
                        onMinimize = { minimizes++ },
                        onQuickExit = {},
                    )
                    else -> ActiveCallDialog(
                        state = VoipSessionState.Error(sampleCall, "Connection dropped"),
                        currentUid = "owner",
                        onMute = {},
                        onSpeaker = {},
                        onFinish = { finishes++ },
                        onMinimize = {},
                        onQuickExit = null,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("accept_voice_call").performClick()
        compose.onNodeWithTag("decline_voice_call").performClick()
        assertTrue(accepts == 1 && declines == 1)

        stage = 1
        compose.waitForIdle()
        compose.onNodeWithTag("mute_voice_call").performClick()
        compose.onNodeWithTag("speaker_voice_call").performClick()
        compose.onNodeWithTag("finish_voice_call").performClick()
        assertTrue(mutes == 1 && speakers == 1 && finishes == 1)

        stage = 2
        compose.waitForIdle()
    }

    @Test
    fun conversationScreenSearchAndFriendInviteDialogWork() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam"))
            handle.session.runtime.state.incomingFriendRequests = listOf(
                FriendRequest("req-1", "u2", "taylor", "owner", "alex", 1000L),
            )
            handle.session.runtime.state.conversations = listOf(groupChat)
            handle.session.runtime.state.activeConversation = groupChat
            handle.session.runtime.state.chatMessages = listOf(
                ChatMessage("m-1", "owner", "alex", "First test line", 1_700_000_000_000L),
                ChatMessage("m-2", "friend-1", "sam", "Second test line", 1_700_000_010_000L),
            )
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        Column(Modifier.fillMaxSize()) {
                            MessagesScreen(handle.session, exit = {})
                        }
                    } else {
                        FriendInviteDialog(
                            uid = "owner",
                            createInvite = { FriendInvite("INV123", "https://example.com/invite/INV123", 1_800_000_000_000L) },
                            observeUnavailable = { _, _ -> ChatSubscription {} },
                            onDismiss = {},
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("chat_search_toggle").performClick()
            compose.mainClock.advanceTimeBy(100)

            stage = 1
            compose.mainClock.advanceTimeBy(200)
        }
    }
}
