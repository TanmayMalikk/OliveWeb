package com.olive.app.ui.users.user.notes

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryEdit
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.safety.SharedPrivateEntry
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
class UserNotesAndTrashUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())

    @Test
    fun privateNotesRendersEntries() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            val now = System.currentTimeMillis()
            val entries = listOf(
                PrivateEntry(
                    id = "note-1",
                    kind = PrivateEntryKind.Note,
                    title = "Important passwords",
                    body = "Backup safe details",
                    timestamp = now,
                    pinned = true,
                    edits = listOf(PrivateEntryEdit(timestamp = now - 5000L, changes = listOf(FieldChange("Title", "Initial note", "Important passwords")))),
                ),
            )
            handle.session.runtime.state.privateEntries = entries
            handle.session.runtime.state.sharedPrivateEntries = listOf(
                SharedPrivateEntry(entry = entries[0], ownerUid = "friend-1"),
            )
            compose.setContent {
                OliveUiTestHost { PrivateNotes(handle.session) }
            }
            compose.waitForIdle()
            compose.onAllNodesWithText("Important passwords").assertCountEquals(2)
        }
    }

    @Test
    fun trashCardsAndEditLogRenderAndInteract() {
        compose.mainClock.autoAdvance = false
        val now = System.currentTimeMillis()
        val entry = PrivateEntry(
            id = "note-1",
            kind = PrivateEntryKind.Note,
            title = "Important passwords",
            body = "Backup safe details",
            timestamp = now,
            pinned = true,
            edits = listOf(PrivateEntryEdit(timestamp = now - 5000L, changes = listOf(FieldChange("Title", "Initial note", "Important passwords")))),
        )
        val trashed = PrivateEntry(
            id = "inc-1",
            kind = PrivateEntryKind.Incident,
            title = "Park altercation",
            body = "Spoke with witness",
            timestamp = now - 10_000L,
            incidentDate = "2026-11-20",
            incidentTime = "14:30",
            incidentLocation = "Central Park",
            deletedAt = now,
        )
        var restoredId = ""
        compose.setContent {
            OliveUiTestHost {
                Trash(
                    entries = listOf(trashed),
                    formatter = formatter,
                    restore = { restoredId = it },
                    close = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("Park altercation").assertExists()
        compose.onNodeWithText(context.getString(R.string.restore)).performClick()
        assertEquals("inc-1", restoredId)
    }

    @Test
    fun noteCardsAndEditLogRenderAndTriggerCallbacks() {
        val now = System.currentTimeMillis()
        val entry = PrivateEntry(
            id = "note-1",
            kind = PrivateEntryKind.Note,
            title = "Important passwords",
            body = "Backup safe details",
            timestamp = now,
            pinned = true,
            edits = listOf(PrivateEntryEdit(timestamp = now - 5000L, changes = listOf(FieldChange("Title", "Initial note", "Important passwords")))),
        )
        val shared = SharedPrivateEntry(entry = entry, ownerUid = "friend-1")
        var openedCount = 0
        var pinnedCount = 0
        var logCount = 0
        var stage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost {
                if (stage == 0) {
                    Column {
                        PrivateNoteCard(
                            entry = entry,
                            formatter = formatter,
                            open = { openedCount++ },
                            pin = { pinnedCount++ },
                            openLog = { logCount++ },
                        )
                        SharedPrivateNoteCard(
                            item = shared,
                            ownerDisplayName = "sam",
                            formatter = formatter,
                            onOpen = {},
                        )
                    }
                } else {
                    PrivateEntryEditLog(entry = entry, formatter = formatter, close = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("private_entry_row").performClick()
        assertEquals(1, openedCount)

        stage = 1
        compose.waitForIdle()
        compose.onNodeWithText("Initial note", substring = true).assertExists()
        assertTrue(pinnedCount == 0 && logCount == 0)
    }
}
