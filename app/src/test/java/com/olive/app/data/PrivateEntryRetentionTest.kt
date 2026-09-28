package com.olive.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateEntryRetentionTest {
    private val deletedAt=1_000_000L
    private val entry=PrivateEntry(title="Private",body="Details",deletedAt=deletedAt)

    @Test fun deletedEntryExpiresAtThirtyDays() {
        assertFalse(entry.trashExpired(deletedAt+PRIVATE_ENTRY_TRASH_RETENTION_MS-1L))
        assertTrue(entry.trashExpired(deletedAt+PRIVATE_ENTRY_TRASH_RETENTION_MS))
    }

    @Test fun remainingDaysRoundUpForTheScratchPadLabel() {
        assertEquals(30,entry.trashDaysRemaining(deletedAt))
        assertEquals(1,entry.trashDaysRemaining(deletedAt+PRIVATE_ENTRY_TRASH_RETENTION_MS-1L))
        assertEquals(0,entry.trashDaysRemaining(deletedAt+PRIVATE_ENTRY_TRASH_RETENTION_MS))
    }
}
