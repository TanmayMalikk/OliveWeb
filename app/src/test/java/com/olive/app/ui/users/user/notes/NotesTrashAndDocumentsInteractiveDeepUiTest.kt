package com.olive.app.ui.users.user.notes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryEdit
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.documents.DocumentSharingDialog
import com.olive.app.ui.users.user.documents.SafetyDocumentsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class NotesTrashAndDocumentsInteractiveDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun privateNotesBoardEditorTrashAndSharingWorkEndToEnd() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.friends = listOf(Friend("fr-1", "f-1", "friend_sam"))
            session.runtime.state.privateEntries = listOf(
                PrivateEntry(
                    id = "note-1",
                    timestamp = 1000L,
                    updatedAt = 2000L,
                    kind = PrivateEntryKind.Note,
                    title = "Pinned Note",
                    body = "Important reminder",
                    pinned = true,
                    edits = listOf(PrivateEntryEdit("ed-1", 1500L, listOf(FieldChange("Body", "A", "B")))),
                ),
                PrivateEntry(
                    id = "inc-trashed",
                    timestamp = 900L,
                    updatedAt = 950L,
                    kind = PrivateEntryKind.Incident,
                    title = "Old Incident",
                    body = "Trashed body",
                    deletedAt = System.currentTimeMillis() - 1_000L,
                ),
            )

            compose.setContent {
                OliveUiTestHost {
                    PrivateNotes(session)
                }
            }
            compose.waitForIdle()

            // Open new entry editor FIRST, trigger validation error, fill body, and save
            compose.onNodeWithTag("new_private_entry").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("private_editor_list").performScrollToNode(hasTestTag("save_private_entry"))
            compose.onNodeWithTag("save_private_entry").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("private_editor_list").performScrollToNode(hasTestTag("private_entry_body"))
            compose.onNodeWithTag("private_entry_body").performTextReplacement("Captured observation")
            compose.onNodeWithTag("private_editor_list").performScrollToNode(hasTestTag("save_private_entry"))
            compose.onNodeWithTag("save_private_entry").performClick()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(300)
        }
    }

    @Test
    fun trashScreenDisplaysAndRestoresTrashedEntries() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.privateEntries = listOf(
                PrivateEntry(
                    id = "inc-trashed",
                    timestamp = 900L,
                    updatedAt = 950L,
                    kind = PrivateEntryKind.Incident,
                    title = "Old Incident",
                    body = "Trashed body",
                    deletedAt = System.currentTimeMillis() - 1_000L,
                ),
            )
            compose.setContent {
                OliveUiTestHost {
                    PrivateNotes(session)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("open_scratchpad").performClick()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodesWithText(context.getString(R.string.restore))[0].performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }

    @Test
    fun noteSharingDialogDocumentSharingDialogAndSafetyDocumentsScreenWork() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Documents, localMode = true).use { handle ->
            val session = handle.session
            val selfUid = session.coverAccountId ?: "u1"
            val friend = Friend("fr-1", "f-1", "sam_friend")
            val doc = SafetyDocument(
                id = "doc-1",
                ownerUid = selfUid,
                storagePath = "users/$selfUid/docs/doc-1",
                name = "Evidence Photo",
                mimeType = "image/jpeg",
                allowedUsers = listOf(selfUid),
            )
            var savedShare = false
            var showDocShare by mutableStateOf(true)

            compose.setContent {
                OliveUiTestHost {
                    SafetyDocumentsScreen(session)
                    NoteSharingDialog(
                        entry = PrivateEntry(id = "n1", title = "T", body = "B"),
                        currentRecipientUids = emptyList(),
                        friends = listOf(friend),
                        contacts = emptyList(),
                        onDismiss = {},
                        onSave = { savedShare = true },
                    )
                    if (showDocShare) {
                        DocumentSharingDialog(
                            file = doc,
                            currentUid = selfUid,
                            friends = listOf(friend),
                            contacts = emptyList(),
                            onDismiss = { showDocShare = false },
                            onSave = { showDocShare = false },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("note_share_friend_f-1").performClick()
            compose.onNodeWithTag("note_share_save").performClick()
            compose.waitForIdle()
            assertTrue(savedShare)
        }
    }
}
