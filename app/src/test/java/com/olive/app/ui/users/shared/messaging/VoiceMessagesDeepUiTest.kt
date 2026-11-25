package com.olive.app.ui.users.shared.messaging

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
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
class VoiceMessagesDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun voiceComposerBubbleAndUploadStatusCoverAllBranches() {
        compose.mainClock.autoAdvance = false
        var retried = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, localMode = true).use { handle ->
            val session = handle.session
            compose.setContent {
                OliveUiTestHost {
                    Column {
                        VoiceMessageUploadStatus(upload = null, onRetry = null)
                        VoiceMessageUploadStatus(
                            upload = MediaUploadPresentation(
                                jobId = "j1",
                                ownerUid = "u1",
                                name = "voice.m4a",
                                mimeType = "audio/mp4",
                                status = MediaTransferStatus.FAILED,
                                bytesTransferred = 0L,
                                totalBytes = 100L,
                                mediaId = "m1",
                                error = "Upload failed",
                                createdAt = 1000L,
                            ),
                            onRetry = { retried = true },
                        )
                        VoiceMessageBubble(
                            mediaId = "media-1",
                            metadata = "4000,15942",
                            session = session,
                            mine = true,
                        )
                        VoiceMessageBubble(
                            mediaId = "media-2",
                            metadata = "",
                            session = session,
                            mine = false,
                        )
                        VoiceMessageComposer(
                            enabled = true,
                            withMicrophonePermission = { action -> action() },
                            onSend = { _, _ -> true },
                        ) { active, controls ->
                            Text(if (active) "rec-active" else "rec-idle")
                            controls?.invoke()
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(250)

            compose.onNodeWithText(context.getString(R.string.retry)).performClick()
            assertTrue(retried)

            compose.onAllNodes(hasContentDescription(context.getString(R.string.voice_play)))[0].performClick()
            compose.mainClock.advanceTimeBy(150)

            compose.onNodeWithTag("record_voice_message").performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }
}
