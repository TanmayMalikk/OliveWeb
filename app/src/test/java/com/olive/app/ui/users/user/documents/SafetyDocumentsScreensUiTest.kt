package com.olive.app.ui.users.user.documents

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyDocumentsScreensUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val sampleDocs = listOf(
        SafetyDocument(
            id = "doc-img",
            ownerUid = "owner",
            storagePath = "users/owner/media/doc-img",
            name = "evidence-photo.jpg",
            mimeType = "image/jpeg",
            allowedUsers = listOf("owner", "friend-1"),
            createdAt = 1000L,
            evidence = true,
        ),
        SafetyDocument(
            id = "doc-pdf",
            ownerUid = "friend-1",
            storagePath = "users/friend-1/media/doc-pdf",
            name = "lease.pdf",
            mimeType = "application/pdf",
            allowedUsers = listOf("owner", "friend-1"),
            createdAt = 2000L,
            evidence = false,
        ),
    )

    @Test
    fun safetyDocumentsScreenAndCardsRenderActionsAndUploads() {
        createUiTestSession(role = AccountRole.USER, localMode = true).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f1", "friend-1", "sam"))
            var opened = 0
            var shared = 0
            var deleted = 0
            var offlineToggles = 0
            var evidenceChanged: Boolean? = null
            var retried = 0

            val queued = MediaUploadPresentation("j1", "owner", "notes.txt", "text/plain", MediaTransferStatus.UPLOADING, 50L, 100L, "m1", null, 1L)
            val failed = MediaUploadPresentation("j2", "owner", "scan.png", "image/png", MediaTransferStatus.FAILED, 0L, 100L, "m2", "Network timeout", 2L)
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        SafetyDocumentsScreen(handle.session)
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PendingUploadCard(upload = queued, onOpen = {}, onRetry = {})
                            PendingUploadCard(upload = failed, onRetry = { retried++ })
                            SafetyDocumentCard(
                                file = sampleDocs[0],
                                currentUid = "owner",
                                friends = handle.session.messages.friends,
                                onOpen = { opened++ },
                                offline = OfflineMediaAvailability.AVAILABLE,
                                onShare = { shared++ },
                                onDelete = { deleted++ },
                                onMakeOffline = { offlineToggles++ },
                                onRemoveOffline = { offlineToggles++ },
                                opening = false,
                                evidenceUpdating = false,
                                onEvidenceChange = { evidenceChanged = it },
                            )
                            SafetyDocumentCard(
                                file = sampleDocs[1],
                                currentUid = "owner",
                                friends = handle.session.messages.friends,
                                onOpen = { opened++ },
                                offline = OfflineMediaAvailability.NOT_CACHED,
                                onShare = { shared++ },
                                onDelete = { deleted++ },
                                onMakeOffline = { offlineToggles++ },
                                onRemoveOffline = { offlineToggles++ },
                                opening = true,
                                evidenceUpdating = false,
                                onEvidenceChange = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("documents_upload").assertExists()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.retry)).performClick()
            assertEquals(1, retried)
            compose.onNodeWithTag("document_doc-img").assertExists()
            compose.onNodeWithTag("document_doc-pdf").assertExists()
            assertTrue(opened == 0 && shared == 0 && deleted == 0 && offlineToggles == 0 && evidenceChanged == null)
        }
    }

    @Test
    fun safetyDocumentViewerHandlesTextImageAndUnsupportedAndEmergencyHelpers() {
        val txtFile = File.createTempFile("olive-doc", ".txt").apply { writeText("Hello safety doc\nLine 2") }
        val imgFile = File.createTempFile("olive-img", ".png").apply {
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            FileOutputStream(this).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        val audioFile = File.createTempFile("olive-aud", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var backClicks = 0
        var mode by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost {
                when (mode) {
                    0 -> SafetyDocumentViewer(
                        document = sampleDocs[0].copy(name = "notes.txt", mimeType = "text/plain"),
                        file = txtFile,
                        onBack = { backClicks++ },
                    )
                    1 -> SafetyDocumentViewer(
                        document = sampleDocs[0],
                        file = imgFile,
                        onBack = { backClicks++ },
                    )
                    else -> SafetyDocumentViewer(
                        document = sampleDocs[0].copy(name = "voice.m4a", mimeType = "audio/mp4"),
                        file = audioFile,
                        onBack = { backClicks++ },
                    )
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hello safety doc", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backClicks)

        mode = 1
        compose.waitForIdle()
        compose.onNodeWithText("evidence-photo.jpg").assertIsDisplayed()

        mode = 2
        compose.waitForIdle()
        compose.onNodeWithText("voice.m4a").assertIsDisplayed()

        // EmergencyReportPdf pure helpers
        assertEquals("Not provided", emergencyIncidentDate(""))
        val profile = UserProfile(
            username = "u1", fullName = "Alex", phone = "",
            dob = "1999-01-01", language = "English", situation = "", affectedNames = "",
            address = DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", country = "Canada"),
        )
        assertTrue(emergencyHomeAddress(profile).contains("Edmonton"))
        val incidents = listOf(
            PrivateEntry(id = "n1", kind = PrivateEntryKind.Note, title = "N", body = "B", timestamp = 1L),
            PrivateEntry(id = "i1", kind = PrivateEntryKind.Incident, title = "Inc", body = "Desc", timestamp = 2L, incidentDate = "2026-11-20", incidentLocation = "Park", attachmentIds = listOf("doc-img")),
        )
        assertEquals(1, emergencyIncidents(incidents).size)
        assertEquals("Park", emergencyIncidentLocation(incidents[1]))
        val extraEvidence = sampleDocs[0].copy(id = "doc-img-2", evidence = true)
        assertEquals(
            1,
            unattachedEmergencyEvidence(
                incidents,
                listOf(
                    EmergencyReportMedia(sampleDocs[0], null),
                    EmergencyReportMedia(sampleDocs[1], null),
                    EmergencyReportMedia(extraEvidence, null),
                ),
            ).size,
        )
    }
}
