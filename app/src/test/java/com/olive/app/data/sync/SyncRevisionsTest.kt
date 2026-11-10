package com.olive.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRevisionsTest {
    @Test
    fun `strict marker parsing accepts only the matching account`() {
        val valid = marker()
        assertEquals(SyncRevisions(9, 1, 2, 3, 4), syncRevisionsFrom(OWNER, valid))
        assertNull(syncRevisionsFrom("another", valid))
        assertNull(syncRevisionsFrom(OWNER, valid + ("allowedUsers" to listOf(OWNER, "another"))))
        assertNull(syncRevisionsFrom(OWNER, valid + ("mediaRevision" to -1)))
        assertNull(syncRevisionsFrom(OWNER, valid - "conversationRevision"))
    }

    @Test
    fun `push parser rejects payload content and malformed revisions`() {
        assertEquals(
            SyncInvalidation(12),
            syncInvalidationFrom(mapOf("type" to "sync_invalidation", "revision" to "12")),
        )
        assertNull(syncInvalidationFrom(mapOf("type" to "call_invitation", "revision" to "12")))
        assertNull(syncInvalidationFrom(mapOf("type" to "sync_invalidation", "revision" to "-1")))
        assertNull(syncInvalidationFrom(mapOf("type" to "sync_invalidation", "revision" to "NaN")))
        assertNull(syncInvalidationFrom(mapOf("type" to "sync_invalidation", "revision" to "12", "fileName" to "private.pdf")))
    }

    private fun marker() = mapOf<String, Any?>(
        "ownerUid" to OWNER,
        "allowedUsers" to listOf(OWNER),
        "revision" to 9,
        "accountRevision" to 1,
        "safetyRevision" to 2,
        "mediaRevision" to 3,
        "conversationRevision" to 4,
    )

    private companion object { const val OWNER = "alice" }
}

