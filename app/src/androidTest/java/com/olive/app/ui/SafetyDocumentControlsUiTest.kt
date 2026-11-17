package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.account.SafeContact
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.documents.DocumentSharingDialog
import com.olive.app.ui.users.user.documents.SafetyDocumentCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SafetyDocumentControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun file(owner: String = "owner") = SafetyDocument(
        id = "sample", ownerUid = owner, storagePath = "test/media/sample",
        name = "Sample document", mimeType = "image/jpeg", objectKey = "encrypted/sample",
        ocrConfidence = .85f, ocrText = "Synthetic document text",
        allowedUsers = listOf("owner", "existing", "hidden"),
    )

    @Test
    fun ownerCanTagEvidenceAndRemoveOfflineCopyAfterRevalidation() {
        var file by mutableStateOf(file())
        var availability by mutableStateOf(OfflineMediaAvailability.REVALIDATION_REQUIRED)
        var updating by mutableStateOf(false)
        var opened = 0
        var removed = 0
        compose.setContent {
            OliveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SafetyDocumentCard(
                        file = file, currentUid = "owner", friends = emptyList(), onOpen = { opened++ },
                        offline = availability, onShare = {}, onDelete = {}, onMakeOffline = {},
                        onRemoveOffline = { removed++; availability = OfflineMediaAvailability.SAVING },
                        opening = false, evidenceUpdating = updating,
                        onEvidenceChange = { file = file.copy(evidence = it); updating = true },
                    )
                }
            }
        }
        compose.onNodeWithTag("document_ocr_sample").assertTextEquals("Synthetic document text")
        val evidence = compose.onNodeWithTag("document_evidence_sample")
        evidence.assertIsOff().performClick()
        evidence.assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { updating = false }
        evidence.assertIsEnabled()
        compose.onNodeWithTag("document_open_sample").performClick()
        compose.onNodeWithText(text(R.string.documents_offline_revalidation_required)).assertExists()
        compose.onNodeWithTag("document_offline_sample").performScrollTo().performClick()
        compose.onNodeWithTag("document_offline_sample").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, removed)
        }
    }

    @Test
    fun sharedRecipientCannotChangeEvidenceOrShareTheOwnersDocument() {
        val friend = Friend("friendship", "sender", "Trusted sender")
        compose.setContent {
            OliveTheme {
                SafetyDocumentCard(
                    file = file("sender").copy(mimeType = "video/mp4"),
                    currentUid = "recipient", friends = listOf(friend), onOpen = {},
                    offline = OfflineMediaAvailability.RECENT_AVAILABLE, onShare = {}, onDelete = {},
                    onMakeOffline = {}, onRemoveOffline = {}, opening = true, evidenceUpdating = false,
                    onEvidenceChange = { error("Viewer cannot change metadata") },
                )
            }
        }
        compose.onNodeWithTag("document_evidence_sample").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.share)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.documents_recently_cached)).assertExists()
        compose.onNodeWithTag("document_open_sample").assertIsNotEnabled()
    }

    @Test
    fun editingSharePreservesAnExistingRecipientOutsideTheFriendList() {
        val friends = listOf(Friend("first", "existing", "Old friend"), Friend("second", "new", "New friend"))
        var grant: Map<String, String>? = null
        compose.setContent {
            OliveTheme {
                DocumentSharingDialog(
                    file(), "owner", friends, listOf(SafeContact("contact", "Trusted name", "", linkedUid = "existing")),
                    onDismiss = {}, onSave = { grant = it },
                )
            }
        }
        compose.onNodeWithText("Trusted name").assertIsDisplayed()
        val checkboxes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        checkboxes[0].assertIsOn().performClick()
        checkboxes[1].assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(mapOf("new" to "New friend", "hidden" to ""), grant) }
    }
}
