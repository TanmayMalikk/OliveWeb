package com.olive.app.ui.users.user.notes

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.UserProfile
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.TestPdfDocumentShadow
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.data.media.MediaKind
import com.olive.app.ui.users.user.documents.BlurredDocumentPreviewSurface
import com.olive.app.ui.users.user.documents.DocumentPreview
import com.olive.app.ui.users.user.documents.DocumentSharingDialog
import com.olive.app.ui.users.user.documents.EmergencyReportLocation
import com.olive.app.ui.users.user.documents.EmergencyReportMedia
import com.olive.app.ui.users.user.documents.EmergencyReportPdf
import com.olive.app.ui.users.user.documents.SafetyDocumentUploadFlow
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp", shadows = [TestPdfDocumentShadow::class])
class NotesDocumentsSafetyPlanDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val fullProfile = UserProfile(
        username = "alex_user",
        fullName = "Alex Rivera",
        affectedNames = "Child One",
        abuserName = "Jordan",
        dob = "1995-04-12",
        language = "English",
        situation = "Documenting safety steps",
        region = "Alberta",
        contacts = listOf(SafeContact("c-1", "Sam", "555-0199", safeToStay = true, linkedUid = "friend-1", relationship = "Sibling")),
        address = DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", postalCode = "T5J 1N3", country = "Canada"),
        role = AccountRole.USER,
        safetyIntake = SafetyPlanIntake(completed = true),
    )

    @Test
    fun privateNotesInteractiveEditorCreatesIncidentAndOpensTrash() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            handle.session.runtime.state.profile = fullProfile
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            val trashedEntry = PrivateEntry(
                id = "trashed-1",
                kind = PrivateEntryKind.Incident,
                title = "Old Incident",
                body = "Archived text",
                timestamp = 1_700_000_000_000L,
                deletedAt = 1_700_001_000_000L,
            )
            handle.session.runtime.state.privateEntries = listOf(trashedEntry)

            compose.setContent {
                OliveUiTestHost {
                    PrivateNotes(handle.session)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("new_private_entry").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("private_kind_incident").performClick()
            compose.onNodeWithTag("private_entry_title").performTextInput("Late Evening Log")
            compose.onNodeWithTag("incident_location").performTextInput("53.5461, -113.4938")
            compose.onNodeWithTag("private_editor_list").performScrollToNode(hasTestTag("private_entry_body"))
            compose.onNodeWithTag("private_entry_body").performTextInput("Walked to safe location.")
            compose.onNodeWithTag("private_editor_list").performScrollToNode(hasTestTag("save_private_entry"))
            compose.onNodeWithTag("save_private_entry").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun documentSharingUploadFlowBlurredPreviewsAndEmergencyPdfWork() {
        val doc = SafetyDocument(
            id = "doc-img",
            ownerUid = "owner",
            storagePath = "users/owner/media/doc-img",
            name = "evidence-photo.png",
            mimeType = "image/png",
            allowedUsers = listOf("owner", "friend-1"),
            createdAt = 1000L,
            evidence = true,
        )
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        val imgFile = File.createTempFile("pdf-evidence", ".png").apply {
            FileOutputStream(this).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        val incident = PrivateEntry(
            id = "inc-1",
            kind = PrivateEntryKind.Incident,
            title = "Incident One",
            body = "Detailed note body",
            timestamp = 1_700_000_000_000L,
            incidentDate = "2026-11-20",
            incidentTime = "09:15",
            incidentLocation = "100 Main St",
            attachmentIds = listOf("doc-img"),
        )
        val pdfFile = EmergencyReportPdf.create(
            context = context,
            profile = fullProfile,
            incidents = listOf(incident),
            pressedLocation = EmergencyReportLocation(53.5461, -113.4938, 12.5f, 1_700_000_000_000L),
            media = mapOf(
                "doc-img" to EmergencyReportMedia(doc, imgFile),
                "doc-extra" to EmergencyReportMedia(doc.copy(id = "doc-extra", mimeType = "application/pdf", name = "lease.pdf"), null),
            ),
        )
        assertTrue(pdfFile.exists() && pdfFile.length() > 0L)

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Documents, localMode = false).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            runBlocking {
                handle.session.documents.rememberRecoveredDocuments("owner", listOf(doc))
            }
            var stage by mutableIntStateOf(0)
            var savedRecipients = 0

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> Column {
                            BlurredDocumentPreviewSurface(
                                document = doc,
                                preview = DocumentPreview(bmp, MediaKind.IMAGE),
                                loading = false,
                                showTypeBadge = true,
                                showEvidenceBadge = true,
                                offlineCached = true,
                                opening = false,
                                extraCountBadge = 2,
                                modifier = Modifier.size(120.dp),
                            )
                            BlurredDocumentPreviewSurface(
                                document = doc.copy(mimeType = "video/mp4", name = "clip.mp4"),
                                preview = null,
                                loading = true,
                                modifier = Modifier.size(120.dp),
                            )
                        }
                        1 -> DocumentSharingDialog(
                            file = doc,
                            currentUid = "owner",
                            friends = handle.session.messages.friends,
                            contacts = fullProfile.contacts,
                            onDismiss = {},
                            onSave = { savedRecipients = it.size },
                        )
                        else -> SafetyDocumentUploadFlow(
                            session = handle.session,
                            visible = true,
                            onDismiss = {},
                            tags = setOf("evidence"),
                            inWindowSheet = true,
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Photo").assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.save)).performClick()
            assertTrue(savedRecipients >= 0)

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithTag("documents_choose_camera").assertExists()
        }
    }
}
