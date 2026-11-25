package com.olive.app.ui.users.user.documents

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class BlurredNoteAttachmentsDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun noteEditorAndSharedEntryAttachmentsRenderGridOpenAndRemoveActions() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "u1"
            val docs = listOf(
                SafetyDocument("att-1", selfUid, "p/1", "evidence_photo.jpg", "image/jpeg", listOf(selfUid), evidence = true),
                SafetyDocument("att-2", selfUid, "p/2", "police_report.pdf", "application/pdf", listOf(selfUid), evidence = false),
                SafetyDocument("att-3", selfUid, "p/3", "voice_note.m4a", "audio/mp4", listOf(selfUid), evidence = true),
            )
            var openedDoc: SafetyDocument? = null
            var removedId = ""

            compose.setContent {
                OliveUiTestHost {
                    Column {
                        NoteEditorAttachmentsSection(
                            selectedAttachments = setOf("att-1", "att-2", "att-3"),
                            documents = docs,
                            controller = session.documents,
                            onOpenDocument = { openedDoc = it },
                            onRemoveAttachment = { removedId = it },
                        )
                        SharedEntryAttachmentsSection(
                            attachmentIds = listOf("att-1", "att-2"),
                            viewerUid = selfUid,
                            controller = session.documents,
                            onOpenMedia = { doc, _ -> openedDoc = doc },
                        )
                    }
                }
            }
            compose.waitForIdle()

            compose.onNodeWithTag("note_attachment_card_att-1").performClick()
            compose.onAllNodesWithContentDescription(context.getString(R.string.remove))[0].performClick()
            compose.waitForIdle()
            assertEquals("att-1", removedId)
        }
    }
}
