package com.olive.app.ui.users.shared.messaging

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.createOliveComposeRule
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.VoipSessionState
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
        compose.activity.setContent {
            OliveTheme {
                ActiveCallDialog(
                    state = VoipSessionState.Active(call, microphoneMuted = false),
                    currentUid = "caller-1",
                    onMute = { muted = it },
                    onSpeaker = { speaker = it },
                    onFinish = { finished = true },
                )
            }
        }

        compose.onNodeWithTag("mute_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("speaker_voice_call").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finish_voice_call").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertTrue(muted)
            assertTrue(speaker)
            assertTrue(finished)
        }
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
