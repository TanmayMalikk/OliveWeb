package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
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
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class VoiceMessagesUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun voiceMessageUploadStatusComposerAndBubblesRenderAndHandleActions() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages).use { handle ->
            var retried = false
            var permRequested = false
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> VoiceMessageUploadStatus(
                            upload = MediaUploadPresentation(
                                jobId = "j-1",
                                ownerUid = "owner",
                                name = "voice.m4a",
                                mimeType = "audio/mp4",
                                status = MediaTransferStatus.FAILED,
                                bytesTransferred = 20L,
                                totalBytes = 100L,
                                mediaId = "m-1",
                                error = "Upload failed",
                                createdAt = 1000L,
                                chatOnly = true,
                            ),
                            onRetry = { retried = true },
                        )
                        1 -> VoiceMessageUploadStatus(upload = null, onRetry = null)
                        2 -> VoiceMessageComposer(
                            enabled = true,
                            withMicrophonePermission = { block ->
                                permRequested = true
                                block()
                            },
                            onSend = { _, _ -> true },
                        ) { _, recordButton ->
                            recordButton?.invoke()
                        }
                        3 -> VoiceMessageBubble(
                            mediaId = "m-audio-1",
                            metadata = "4500,135792468",
                            session = handle.session,
                            mine = true,
                        )
                        else -> VoiceMessageBubble(
                            mediaId = "m-audio-2",
                            metadata = "bad",
                            session = handle.session,
                            mine = false,
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.retry)).performClick()
            assertTrue(retried)

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.voice_sending)).assertExists()

            stage = 2
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("record_voice_message", useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(150)
            assertTrue(permRequested)

            compose.mainClock.autoAdvance = true
            stage = 3
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onAllNodesWithContentDescription(context.getString(R.string.voice_play), useUnmergedTree = true)
                .onFirst().performClick()
            compose.mainClock.advanceTimeBy(150)

            stage = 4
            compose.mainClock.advanceTimeBy(150)
        }
    }
}
