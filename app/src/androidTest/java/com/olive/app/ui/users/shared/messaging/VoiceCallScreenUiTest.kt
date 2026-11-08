package com.olive.app.ui.users.shared.messaging

import androidx.activity.compose.setContent
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
        compose.activity.setContent {
            OliveTheme {
                ActiveCallDialog(
                    state = VoipSessionState.Active(call, microphoneMuted = false),
                    currentUid = "caller-1",
                    onMute = { muted = it },
                    onSpeaker = { speaker = it },
                    onFinish = { finished = true },
                    onQuickExit = { quickExited = true },
                )
            }
        }

        compose.onNodeWithTag("mute_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("speaker_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call_quick_exit").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finish_voice_call").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertTrue(muted)
            assertTrue(speaker)
            assertTrue(quickExited)
            assertTrue(finished)
        }
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
                    onQuickExit = {},
                )
            }
        }

        compose.onNodeWithText("Calling…").assertIsDisplayed()
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
