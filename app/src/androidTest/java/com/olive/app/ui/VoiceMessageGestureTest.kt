package com.olive.app.ui

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.ui.users.shared.messaging.VoiceMessageComposer
import org.junit.Rule
import org.junit.Test

class VoiceMessageGestureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingMicStartsAndStopsRecordingWithPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    VoiceMessageComposer(
                        enabled = true,
                        withMicrophonePermission = { it() },
                        onSend = { _, _ -> false },
                    ) { voiceOpen, mic ->
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                readOnly = voiceOpen,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { Spacer(Modifier.size(48.dp)) },
                            )
                            Box(Modifier.align(Alignment.CenterEnd)) { mic?.invoke() }
                        }
                    }
                }
            }
        }
        val microphone = compose.onNodeWithTag("record_voice_message")
        val preview = compose.onNodeWithContentDescription(context.getString(R.string.voice_play))
        val stop = compose.onNodeWithContentDescription(context.getString(R.string.voice_stop))
        val discard = compose.onNodeWithContentDescription(context.getString(R.string.cancel))

        microphone.performTouchInput {
            down(center)
            up()
        }
        stop.assertExists()
        preview.assertDoesNotExist()
        SystemClock.sleep(1300)
        microphone.performTouchInput {
            down(center)
            up()
        }
        stop.assertDoesNotExist()
        try {
            preview.assertExists()
        } catch (error: AssertionError) {
            throw AssertionError(compose.onRoot().printToString(), error)
        }
        compose.onNodeWithText("0:", substring = true).assertExists()
        discard.performClick()
        preview.assertDoesNotExist()
    }
}
