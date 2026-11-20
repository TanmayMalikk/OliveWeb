package com.olive.app.data.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.support.FirestoreTestDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncInvalidationRepositoryTest {
    private val firestore = FirestoreTestDriver()
    private val repository = SyncInvalidationRepository(firestore.db)
    private val path = "syncInvalidations/owner"
    private val marker = mapOf(
        "ownerUid" to "owner", "allowedUsers" to listOf("owner"), "revision" to 7L,
        "accountRevision" to 2L, "safetyRevision" to 3L, "mediaRevision" to 4L,
        "conversationRevision" to 5L, "urgentRevision" to 6L,
    )
    private val revisions = SyncRevisions(7, 2, 3, 4, 5, 6)

    @Test
    fun serverReadDistinguishesMissingInvalidAndValidMarkers() = runBlocking<Unit> {
        assertNull(repository.get("owner"))
        firestore.records[path] = marker + ("allowedUsers" to listOf("owner", "other"))
        val invalid = runCatching { repository.get("owner") }.exceptionOrNull()
        assertEquals("Invalid reconciliation marker", invalid?.message)
        firestore.records[path] = marker
        assertEquals(revisions, repository.get("owner"))
    }

    @Test
    fun listenerOnlyAppliesValidMarkersAndClosesOnAccountSwitch() {
        val updates = mutableListOf<SyncRevisions>()
        val errors = mutableListOf<Exception>()
        val listener = repository.observe("owner", updates::add, errors::add)
        firestore.emit(path)
        assertTrue(updates.isEmpty())
        assertTrue(errors.isEmpty())

        firestore.records[path] = marker
        firestore.emit(path)
        firestore.records[path] = marker + ("ownerUid" to "other")
        firestore.emit(path)
        val denied = FirebaseFirestoreException("Signed out", FirebaseFirestoreException.Code.UNAUTHENTICATED)
        firestore.listeners.single().receive.onEvent(null, denied)
        assertEquals(listOf(revisions), updates)
        assertEquals("Invalid reconciliation marker", errors.first().message)
        assertSame(denied, errors.last())

        listener.close()
        firestore.records[path] = marker + ("revision" to 8L)
        firestore.emit(path)
        assertEquals(listOf(revisions), updates)
        assertTrue(firestore.listeners.single().closed)
    }
}
