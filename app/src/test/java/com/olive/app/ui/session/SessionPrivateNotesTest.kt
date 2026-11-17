package com.olive.app.ui.session

import com.olive.app.data.account.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SafePage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionPrivateNotesTest {
    private fun save(f: SessionRuntimeFixture, title: String, body: String, id: String? = null, kind: PrivateEntryKind = PrivateEntryKind.Note) {
        f.runtime.notes.savePrivateEntry(id, kind, title, body, "2025-06-10", "12:30", "Synthetic location", 53.0, -113.0, listOf("file", "file"))
    }

    @Test
    fun noteCreationTrimsTextClearsIncidentOnlyFieldsAndStoresUniqueAttachments() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            f.runtime.state.page = SafePage.Notes
            save(f, "", "  Private synthetic text  ")
            f.await { !f.runtime.state.busy }
            val note = f.runtime.state.privateEntries.single()
            assertEquals("", note.title)
            assertEquals("Private synthetic text", note.body)
            assertEquals("", note.incidentDate)
            assertNull(note.incidentLatitude)
            assertEquals(listOf("file"), note.attachmentIds)
            assertEquals(note, f.runtime.store.privateEntries("owner").single())
            f.runtime.notes.clearPrivateEntryStatus()
            assertNull(f.runtime.state.message)
        }
    }

    @Test
    fun incidentEditsRecordChangedFieldsAndPinningDoesNotCreateContentRevisions() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            f.runtime.state.page = SafePage.Notes
            save(f, "Incident", "First", kind = PrivateEntryKind.Incident)
            f.await { !f.runtime.state.busy }
            val original = f.runtime.state.privateEntries.single()
            assertEquals("2025-06-10", original.incidentDate)
            assertEquals(53.0, original.incidentLatitude)
            save(f, "Revised", "Changed", original.id, PrivateEntryKind.Incident)
            f.await { !f.runtime.state.busy }
            val revised = f.runtime.state.privateEntries.single()
            assertEquals(original.timestamp, revised.timestamp)
            assertEquals(1, revised.edits.size)
            assertTrue(revised.edits.single().changes.any { it.field == "Details" })
            f.runtime.notes.togglePrivateEntryPin(original.id)
            f.await { !f.runtime.state.busy }
            assertTrue(f.runtime.state.privateEntries.single().pinned)
            assertEquals(1, f.runtime.state.privateEntries.single().edits.size)
            save(f, "Revised", "Changed", original.id, PrivateEntryKind.Incident)
            assertEquals("no_changes_to_save", f.runtime.state.message)
        }
    }

    @Test
    fun trashAndRestoreKeepTheOriginalBodyAndRemovePinnedPlacement() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            f.runtime.state.page = SafePage.Notes
            save(f, "Restore", "Retained body")
            f.await { !f.runtime.state.busy }
            val id = f.runtime.state.privateEntries.single().id
            f.runtime.notes.togglePrivateEntryPin(id)
            f.await { !f.runtime.state.busy }
            f.runtime.notes.trashPrivateEntry(id)
            f.await { !f.runtime.state.busy }
            assertNotNull(f.runtime.state.privateEntries.single().deletedAt)
            assertFalse(f.runtime.state.privateEntries.single().pinned)
            f.runtime.notes.restorePrivateEntry(id)
            f.await { !f.runtime.state.busy }
            assertNull(f.runtime.state.privateEntries.single().deletedAt)
            assertEquals("Retained body", f.runtime.state.privateEntries.single().body)
        }
    }

    @Test
    fun emptyOverlongAndStaleScreenSubmissionsNeverWritePrivateEntries() {
        SessionRuntimeFixture().use { f ->
            f.runtime.state.page = SafePage.Notes
            save(f, "Title", " ")
            assertEquals("write_something_before_saving", f.runtime.state.message)
            save(f, "x".repeat(121), "body")
            assertEquals("use_up_to_120_title_characters_and_10_000_detail_characters", f.runtime.state.message)
            f.runtime.state.page = SafePage.Home
            save(f, "Title", "Body")
            assertTrue(f.runtime.store.privateEntries("owner").isEmpty())
            assertFalse(f.runtime.state.busy)
        }
    }
}
