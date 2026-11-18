package com.olive.app.ui.users.shared.messaging

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.olive.app.R
import com.olive.app.data.messaging.VoipInvitation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.createOliveComposeRule
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.data.messaging.VoipCallStatus
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceCallScreenUiTest {
    @get:Rule
    val compose = createOliveComposeRule()

    @Test
    fun activeCallUsesFullScreenControlsForMuteSpeakerAndHangUp() {
        var muted = false
        var speaker = false
        var finished = false
        var quickExited = false
        var minimized = false
        compose.activity.setContent {
            OliveTheme {
                ActiveCallDialog(
                    state = VoipSessionState.Active(call, microphoneMuted = false),
                    currentUid = "caller-1",
                    onMute = { muted = it },
                    onSpeaker = { speaker = it },
                    onFinish = { finished = true },
                    onMinimize = { minimized = true },
                    onQuickExit = { quickExited = true },
                )
            }
        }

        compose.onNodeWithTag("mute_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("speaker_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("minimize_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call_quick_exit").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finish_voice_call").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertTrue(muted)
            assertTrue(speaker)
            assertTrue(minimized)
            assertTrue(quickExited)
            assertTrue(finished)
        }
    }

    @Test
    fun minimizedCallBubbleReturnsToTheCallScreenWhenTapped() {
        var opened = false
        compose.activity.setContent {
            OliveTheme {
                DraggableCallBubble(
                    state = VoipSessionState.Active(call, microphoneMuted = false),
                    onOpen = { opened = true },
                )
            }
        }

        compose.onNodeWithTag("minimized_voice_call").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun callerCanHangUpWhileTheCallIsStillConnecting() {
        var finished = false
        compose.activity.setContent {
            OliveTheme {
                ActiveCallDialog(
                    state = VoipSessionState.Connecting(call),
                    currentUid = "caller-1",
                    onMute = {},
                    onSpeaker = {},
                    onFinish = { finished = true },
                    onMinimize = {},
                    onQuickExit = {},
                )
            }
        }

        compose.onNodeWithTag("finish_voice_call").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test
    fun callerRemainsCallingUntilAnotherParticipantJoinsTheRoom() {
        compose.activity.setContent {
            OliveTheme {
                ActiveCallDialog(
                    state = VoipSessionState.Active(
                        call,
                        microphoneMuted = false,
                        remoteParticipantCount = 0,
                    ),
                    currentUid = "caller-1",
                    onMute = {},
                    onSpeaker = {},
                    onFinish = {},
                    onMinimize = {},
                    onQuickExit = {},
                )
            }
        }

        compose.onNodeWithText("Callingâ€¦").assertIsDisplayed()
        compose.onNodeWithTag("finish_voice_call").assertIsDisplayed()
    }

    @Test
    fun missedCallAppearsAsAChatHistoryEvent() {
        compose.activity.setContent {
            OliveTheme {
                CallHistoryRow(
                    call.copy(
                        status = VoipCallStatus.MISSED,
                        conversationId = "conversation-1",
                        createdAt = 1_000,
                        endedAt = 21_000,
                    )
                )
            }
        }

        compose.onNodeWithText("Missed call").assertIsDisplayed()
        compose.onNodeWithTag("call_history_call-1").assertIsDisplayed()
    }

    @Test
    fun incomingAndGroupCallControlsKeepAcceptDeclineAndLeaveSeparate() {
        val stages = mutableStateOf(0)
        val invitation = VoipInvitation(call.callId, call.callerId, "", VoipParticipantStatus.PENDING)
        val group = call.copy(name = "Synthetic group",
            participants = call.participants + VoipParticipant("third-person", VoipParticipantStatus.ACCEPTED))
        var declined = false
        var left = false
        var dismissed = false
        compose.activity.setContent {
            OliveTheme {
                when (stages.value) {
                    0 -> IncomingCallDialog(invitation, onAccept = { stages.value = 1 },
                        onDecline = { declined = true }, onQuickExit = null)
                    1, 2 -> ActiveCallDialog(
                        if (stages.value == 1) VoipSessionState.Active(
                            group, microphoneMuted = true, speakerEnabled = true, remoteParticipantCount = 2,
                        ) else VoipSessionState.Error(group, "Synthetic connection error"),
                        currentUid = call.callerId, onMute = {}, onSpeaker = {},
                        onFinish = {
                            if (stages.value == 1) {
                                left = true
                                stages.value = 2
                            } else dismissed = true
                        },
                        onMinimize = {}, onQuickExit = null,
                    )
                }
            }
        }
        compose.onNodeWithTag("decline_voice_call").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(declined) }
        compose.onNodeWithTag("accept_voice_call").performClick()
        compose.onNodeWithText("Synthetic group").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.ui_leave_call)).assertIsDisplayed()
        compose.onNodeWithTag("finish_voice_call").performClick()
        compose.runOnIdle { assertTrue(left) }
        compose.onNodeWithText("Synthetic connection error").assertIsDisplayed()
        compose.onNodeWithTag("finish_voice_call").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    companion object {
        private val call = VoipCall(
            callId = "call-1",
            name = "Safe Contact",
            callerId = "caller-1",
            participants = listOf(
                VoipParticipant("caller-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("user-2", VoipParticipantStatus.ACCEPTED),
            ),
        )
    }
}
