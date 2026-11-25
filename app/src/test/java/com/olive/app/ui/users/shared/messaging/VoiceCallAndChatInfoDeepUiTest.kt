package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.Friend
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class VoiceCallAndChatInfoDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun groupCall() = VoipCall(
        callId = "call-1",
        name = "Support Group",
        callerId = "u1",
        participants = listOf(
            VoipParticipant("u1", VoipParticipantStatus.ACCEPTED),
            VoipParticipant("u2", VoipParticipantStatus.ACCEPTED),
            VoipParticipant("u3", VoipParticipantStatus.PENDING),
        ),
        callerName = "Jordan",
        status = VoipCallStatus.ACTIVE,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun incomingCallDialogAcceptsAndDeclines() {
        var accepted = false
        var declined = false
        compose.setContent {
            OliveUiTestHost {
                IncomingCallDialog(
                    invitation = VoipInvitation("call-1", "u2", "Jordan", VoipParticipantStatus.PENDING),
                    onAccept = { accepted = true },
                    onDecline = { declined = true },
                    onQuickExit = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("accept_voice_call").performClick()
        compose.onNodeWithTag("decline_voice_call").performClick()
        assertTrue(accepted && declined)
    }

    @Test
    fun activeCallDialogCoversActiveErrorAndLeavingStates() {
        compose.mainClock.autoAdvance = false
        val call = groupCall()
        var muted = false
        var speaker = false
        var minimized = false
        var callState by mutableStateOf<VoipSessionState>(
            VoipSessionState.Active(call, microphoneMuted = false, speakerEnabled = false, remoteParticipantCount = 1),
        )
        compose.setContent {
            OliveUiTestHost {
                ActiveCallDialog(
                    state = callState,
                    currentUid = "u1",
                    onMute = { muted = it },
                    onSpeaker = { speaker = it },
                    onFinish = {},
                    onMinimize = { minimized = true },
                    onQuickExit = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_150)
        compose.onNodeWithTag("mute_voice_call").performClick()
        compose.onNodeWithTag("speaker_voice_call").performClick()
        compose.onNodeWithTag("minimize_voice_call").performClick()
        compose.runOnUiThread { callState = VoipSessionState.Error(call, "Network lost") }
        compose.mainClock.advanceTimeBy(250)
        compose.runOnUiThread { callState = VoipSessionState.Leaving(call) }
        compose.mainClock.advanceTimeBy(250)
        compose.runOnUiThread { callState = VoipSessionState.Connecting(call) }
        compose.mainClock.advanceTimeBy(250)
        assertTrue(muted && speaker && minimized)
    }

    @Test
    fun callHistoryRowsCoverAllStatusesAndJoin() {
        val call = groupCall()
        var joined = false
        compose.setContent {
            OliveUiTestHost {
                Column {
                    CallHistoryRow(call.copy(callId = "c-missed", status = VoipCallStatus.MISSED), canJoin = false)
                    CallHistoryRow(call.copy(callId = "c-declined", status = VoipCallStatus.DECLINED), canJoin = false)
                    CallHistoryRow(call.copy(callId = "c-ended", status = VoipCallStatus.ENDED, endedAt = 1_700_000_050_000L), canJoin = false)
                    CallHistoryRow(call.copy(callId = "c-active"), canJoin = true, onJoin = { joined = true })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("join_call_c-active").performClick()
        assertTrue(joined)
    }

    @Test
    fun chatInfoDialogRendersMembersSharedItemsAndAddPeoplePicker() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "u1"
            val conv = ChatConversation(
                id = "conv-info-1",
                friendshipId = "fs-1",
                participantIds = listOf(selfUid, "friend-1"),
                participantUsernames = mapOf(selfUid to "me", "friend-1" to "jordan"),
                type = "SUPPORT",
                ownerUid = selfUid,
                staffUid = "staff-1",
            )
            session.runtime.state.friends = listOf(
                Friend("fs-1", "friend-1", "jordan"),
                Friend("fs-2", "friend-2", "sam_candidate"),
            )
            session.runtime.state.activeConversation = conv
            session.runtime.state.chatMessages = listOf(
                ChatMessage("m1", selfUid, "me", "SHARED_MEDIA:doc1:Photo.jpg", 1000L),
                ChatMessage("m2", "friend-1", "jordan", "SHARED_VOICE:v1:1200,123", 1100L),
                ChatMessage("m3", selfUid, "me", "SHARED_NOTE:n1:Observation", 1200L),
            )

            compose.setContent {
                OliveUiTestHost {
                    ChatInfoDialog(
                        session = session,
                        currentUid = selfUid,
                        onClose = {},
                        onOpenMedia = { _, _ -> },
                    )
                }
            }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithTag("chat_info_back").performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }
}
