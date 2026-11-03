// Tests malformed, old, deleted, and changed account-history records for every account role.
package com.olive.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountActivityHistoryEdgeCaseTest {
    // Builds a complete sample profile for this test.
    private fun profile() = UserProfile(
        username = "test_user",
        fullName = "Test User",
        affectedNames = "",
        abuserName = "",
        dob = "2000-01-01",
        language = "English",
        situation = "",
        region = "Edmonton",
        triggerHash = "hash",
        address = DeliveryAddress(city = "Edmonton"),
    )

    // Inputs: the values shown below. Expected: older private entries use their original timestamp and ignore unknown kinds.
    @Test
    fun olderPrivateEntriesUseTheirOriginalTimestampAndIgnoreUnknownKinds() {
        val restored = PrivateEntry.fromJson(
            JSONObject()
                .put("id", "entry")
                .put("timestamp", 1234L)
                .put("kind", "NOT_A_REAL_KIND")
                .put("title", "Title")
                .put("body", "Body")
        )

        assertEquals(1234L, restored.updatedAt)
        assertNull(restored.kind)
        assertFalse(restored.pinned)
        assertTrue(restored.edits.isEmpty())
    }

    // Inputs: the values shown below. Expected: obsolete pinned edit rows are removed without removing real changes.
    @Test
    fun obsoletePinnedEditRowsAreRemovedWithoutRemovingRealChanges() {
        val pinned = PrivateEntryEdit(
            id = "pinned",
            timestamp = 10L,
            changes = listOf(FieldChange("Pinned", "false", "true")),
        )
        val title = PrivateEntryEdit(
            id = "title",
            timestamp = 11L,
            changes = listOf(
                FieldChange("Pinned", "false", "true"),
                FieldChange("Title", "Old", "New"),
            ),
        )
        val entry = PrivateEntry(
            id = "entry",
            timestamp = 1L,
            kind = PrivateEntryKind.Note,
            title = "New",
            body = "Body",
            edits = listOf(pinned, title),
        )

        val restored = PrivateEntry.fromJson(entry.toJson())

        assertEquals(1, restored.edits.size)
        assertEquals(listOf(FieldChange("Title", "Old", "New")), restored.edits.single().changes)
    }

    // Tries missing, future, boundary, and expired trash dates; only expired items should be removed.
    @Test
    fun trashRetentionHandlesMissingFutureAndExactBoundaryTimes() {
        val active = PrivateEntry(title = "Active", body = "Body")
        assertFalse(active.trashExpired(now = 100L))
        assertEquals(0, active.trashDaysRemaining(now = 100L))
        assertEquals(0, active.trashSecondsRemaining(now = 100L))

        val deleted = active.copy(deletedAt = 5_000L)
        assertFalse(deleted.trashExpired(now = 4_000L))
        assertFalse(deleted.trashExpired(now = 5_000L + PRIVATE_ENTRY_TRASH_RETENTION_MS - 1L))
        assertTrue(deleted.trashExpired(now = 5_000L + PRIVATE_ENTRY_TRASH_RETENTION_MS))
        assertEquals(0, deleted.trashSecondsRemaining(5_000L + PRIVATE_ENTRY_TRASH_RETENTION_MS))
    }

    // Inputs: Long.MIN_VALUE and Long.MAX_VALUE timestamps. Expected: trash expiry clamps safely instead of wrapping time backward.
    @Test
    fun trashRetentionDoesNotOverflowAtLongBoundaries() {
        val ancientDeletion = PrivateEntry(title = "Old", body = "Body", deletedAt = Long.MIN_VALUE)
        assertTrue(ancientDeletion.trashExpired(Long.MAX_VALUE))
        assertEquals(0, ancientDeletion.trashDaysRemaining(Long.MAX_VALUE))
        assertEquals(0, ancientDeletion.trashSecondsRemaining(Long.MAX_VALUE))

        val futureDeletion = ancientDeletion.copy(deletedAt = Long.MAX_VALUE)
        assertFalse(futureDeletion.trashExpired(Long.MIN_VALUE))
        assertTrue(futureDeletion.trashDaysRemaining(Long.MIN_VALUE) > 0)
    }

    // Inputs: the values shown below. Expected: profile history records only fields that actually changed.
    @Test
    fun profileHistoryRecordsOnlyFieldsThatActuallyChanged() {
        val before = profile()
        assertTrue(profileChanges(before, before.copy()).isEmpty())

        val after = before.copy(
            fullName = "Updated Name",
            triggerHash = "new hash",
            contacts = listOf(SafeContact("safe", "Trusted", "555-0100", true, "uid")),
        )
        val changes = profileChanges(before, after)

        assertEquals(setOf("Full name", "Trigger word", "Safe contacts"), changes.map { it.field }.toSet())
        assertEquals("Hidden", changes.first { it.field == "Trigger word" }.before)
        assertFalse(changes.any { it.before.contains("new hash") || it.after.contains("new hash") })
    }

    // Inputs: the values shown below. Expected: required history fields fail instead of silently creating corrupt records.
    @Test
    fun requiredHistoryFieldsFailInsteadOfSilentlyCreatingCorruptRecords() {
        assertThrows(Exception::class.java) {
            PrivateEntry.fromJson(JSONObject().put("id", "missing-body").put("timestamp", 1L))
        }
        assertThrows(Exception::class.java) {
            FieldChange.fromJson(JSONObject().put("before", "old").put("after", "new"))
        }
    }
}
