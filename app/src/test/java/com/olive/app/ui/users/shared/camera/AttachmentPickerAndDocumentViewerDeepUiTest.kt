package com.olive.app.ui.users.shared.camera

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.safety.DocumentListSnapshot
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AttachmentPickerAndDocumentViewerDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findVideoView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Test
    fun attachmentPickerCoversNotesIncidentsMediaSafetyPlanAndLocation() {
        compose.mainClock.autoAdvance = false
        val doc = SafetyDocument(
            id = "d-1",
            ownerUid = "owner",
            storagePath = "users/owner/media/d-1",
            name = "scan.jpg",
            mimeType = "image/jpeg",
            allowedUsers = listOf("owner"),
            createdAt = 1000L,
            objectKey = "users/owner/media/d-1",
        )
        val docsOverride = mock(SafetyDocumentDataSource::class.java)
        doAnswer { call ->
            val onUpdate = call.getArgument<(DocumentListSnapshot) -> Unit>(1)
            onUpdate(DocumentListSnapshot(listOf(doc), DocumentListSource.SERVER, 100L))
            ChatSubscription {}
        }.`when`(docsOverride).observeFor(
            anyString(),
            org.mockito.ArgumentMatchers.any<(DocumentListSnapshot) -> Unit>() ?: {},
            org.mockito.ArgumentMatchers.any<(String) -> Unit>() ?: {},
        )
        createUiTestSession(
            role = AccountRole.USER,
            screen = SessionScreen.Safe,
            page = SafePage.Messages,
            localMode = false,
            documentsOverride = docsOverride,
        ).use { handle ->
            handle.session.documents.load("owner", "test_user", false)
            handle.session.runtime.state.activeConversation = ChatConversation(
                id = "chat-sup",
                friendshipId = "",
                participantIds = listOf("owner", "staff-1"),
                participantUsernames = mapOf("owner" to "alex", "staff-1" to "staff"),
                type = "SUPPORT",
                ownerUid = "owner",
                staffUid = "staff-1",
            )
            handle.session.runtime.state.privateEntries = listOf(
                PrivateEntry(id = "n-1", kind = PrivateEntryKind.Note, title = "Safety Note", body = "Checkpoints", timestamp = 1000L),
                PrivateEntry(id = "i-1", kind = PrivateEntryKind.Incident, title = "Incident Report", body = "Details", timestamp = 2000L, incidentDate = "2026-11-20"),
            )
            var pickerInstance by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost(locationGranted = true) {
                    key(pickerInstance) {
                        if (pickerInstance in 0..4) {
                            AttachmentPicker(session = handle.session, onDismiss = {})
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("attachment_category_notes").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithText("Safety Note").performClick()

            pickerInstance = 1
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.incident_reports)).performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithText("Incident Report").performClick()

            pickerInstance = 2
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.saved_media)).performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("chat_picker_media_d-1").performClick()

            pickerInstance = 3
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.shared_safety_plan)).performClick()

            pickerInstance = 4
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.start_sharing_location)).performClick()
            compose.mainClock.advanceTimeBy(150)
        }
    }

    @Test
    fun safetyDocumentViewerMountsVideoAndPdfErrorStates() {
        compose.mainClock.autoAdvance = false
        val videoFile = File.createTempFile("clip", ".mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 24)) }
        val badPdf = File.createTempFile("broken", ".pdf").apply { writeText("not a valid pdf") }
        val videoDoc = SafetyDocument("d-vid", "owner", "path", "clip.mp4", "video/mp4", listOf("owner"), 1000L)
        val pdfDoc = SafetyDocument("d-pdf", "owner", "path", "report.pdf", "application/pdf", listOf("owner"), 1000L)

        var rootView: View? = null
        var stage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost {
                rootView = LocalView.current.rootView
                if (stage == 0) {
                    SafetyDocumentViewer(videoDoc, videoFile) {}
                } else {
                    SafetyDocumentViewer(pdfDoc, badPdf) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val videoView = rootView?.let(::findVideoView)
        assertNotNull(videoView)

        stage = 1
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(300)
    }
}
