// Tests how long deleted private entries stay in a user's trash.
package com.olive.app.data

import com.olive.app.ui.shared.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateEntryRetentionTest {
    private val deletedAt = 1_000_000L
    private val entry = PrivateEntry(title = "Private", body = "Details", deletedAt = deletedAt)

    @Test
    fun deletedEntryExpiresAtConfiguredTestDelay() {
        assertFalse(entry.trashExpired(deletedAt + PRIVATE_ENTRY_TRASH_RETENTION_MS - 1L))
        assertTrue(entry.trashExpired(deletedAt + PRIVATE_ENTRY_TRASH_RETENTION_MS))
    }

    @Test
    fun remainingSecondsRoundUpForTheTrashLabel() {
        assertEquals(10, entry.trashSecondsRemaining(deletedAt))
        assertEquals(
            1,
            entry.trashSecondsRemaining(deletedAt + PRIVATE_ENTRY_TRASH_RETENTION_MS - 1L)
        )
        assertEquals(0, entry.trashSecondsRemaining(deletedAt + PRIVATE_ENTRY_TRASH_RETENTION_MS))
    }
}
