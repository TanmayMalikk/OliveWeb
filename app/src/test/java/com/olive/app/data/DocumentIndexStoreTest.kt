package com.olive.app.data

import android.content.Context
import com.olive.app.security.JsonVault
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIndexStoreTest {
    @Test
    fun `index isolates accounts and retains only viewer wrapped keys`() {
        val store = DocumentIndexStore(FakeJsonVault())
        store.replace(ALICE, listOf(document("one", ALICE)), 100)
        store.replace(BOB, listOf(document("two", BOB)), 200)

        val alice = store.read(ALICE)
        assertEquals(listOf("one"), alice.documents.map { it.id })
        assertEquals(setOf(ALICE), alice.documents.single().wrappedKeys.keys)
        assertTrue(alice.documents.single().deviceWrappedKeys.keys.all { it.startsWith("$ALICE--") })
        assertFalse(alice.documents.single().deviceWrappedKeys.keys.any { it.startsWith("$BOB--") })

        store.purge(ALICE)
        assertTrue(store.read(ALICE).documents.isEmpty())
        assertEquals(listOf("two"), store.read(BOB).documents.map { it.id })
    }

    @Test
    fun `firestore cache cannot purge but authoritative server snapshot can`() {
        val index = DocumentIndexStore(FakeJsonVault())
        index.replace(ALICE, listOf(document("kept", ALICE), document("revoked", ALICE)), 100)
        val remote = FakeRemote()
        val repository = IndexedSafetyDocumentRepository(remote, index) { 500 }
        val observed = mutableListOf<DocumentListSnapshot>()
        repository.observeFor(ALICE, observed::add) { error(it) }

        remote.emit(DocumentListSnapshot(emptyList(), DocumentListSource.FIRESTORE_CACHE))
        assertEquals(setOf("kept", "revoked"), observed.last().documents.mapTo(mutableSetOf()) { it.id })

        remote.emit(DocumentListSnapshot(listOf(document("kept", ALICE)), DocumentListSource.SERVER, 400))
        assertEquals(listOf("kept"), index.read(ALICE).documents.map { it.id })
        assertEquals(400L, index.read(ALICE).lastSyncedAt)
    }

    @Test
    fun `authorization failure never falls back through getFor`() = runBlocking {
        val index = DocumentIndexStore(FakeJsonVault())
        index.replace(ALICE, listOf(document("cached", ALICE)), 100)
        val repository = IndexedSafetyDocumentRepository(FakeRemote(getFailure = SecurityException("revoked")), index)

        assertEquals("cached", repository.cachedFor(ALICE, "cached")?.id)
        val failure = runCatching { repository.getFor(ALICE, "cached") }.exceptionOrNull()
        assertTrue(failure is SecurityException)
    }

    @Test
    fun `wide repeated index replacements remain deterministic`() {
        val store = DocumentIndexStore(FakeJsonVault())
        repeat(40) { round ->
            val values = List((round % 17) + 1) { document("doc-$round-$it", ALICE) }
            store.replace(ALICE, values + values.first(), round + 1L)
            assertEquals(values.map { it.id }.toSet(), store.read(ALICE).documents.map { it.id }.toSet())
        }
        assertNull(store.read("unrelated").lastSyncedAt)
    }

    private fun document(id: String, viewer: String) = SafetyDocument(
        id = id,
        ownerUid = ALICE,
        storagePath = "",
        name = "$id.pdf",
        mimeType = "application/pdf",
        allowedUsers = listOf(ALICE, BOB),
        createdAt = id.hashCode().toLong(),
        objectKey = "objects/$id",
        encryptedMetadata = "encrypted",
        wrappedKeys = mapOf(ALICE to "wrapped-a", BOB to "wrapped-b"),
        ciphertextSize = 64,
        ciphertextSha256 = "a".repeat(64),
        encryptionVersion = 2,
        deviceWrappedKeys = mapOf("$ALICE--phone" to "device-a", "$BOB--phone" to "device-b"),
    ).also { require(viewer in it.allowedUsers) }

    private class FakeRemote(private val getFailure: Exception? = null) : SafetyDocumentDataSource {
        private var observer: ((DocumentListSnapshot) -> Unit)? = null
        fun emit(value: DocumentListSnapshot) = checkNotNull(observer).invoke(value)
        override suspend fun listFor(uid: String) = DocumentListSnapshot(emptyList(), DocumentListSource.SERVER, 1)
        override fun observeFor(uid: String, onUpdate: (DocumentListSnapshot) -> Unit, onError: (String) -> Unit) =
            ChatSubscription { observer = null }.also { observer = onUpdate }
        override suspend fun getFor(uid: String, id: String): SafetyDocument {
            getFailure?.let { throw it }
            error("unused")
        }
        override suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>) = Unit
        override suspend fun delete(document: SafetyDocument, ownerUid: String) = Unit
        override suspend fun download(context: Context, document: SafetyDocument): File = error("unused")
        override suspend fun approveCurrentAccountDevices(document: SafetyDocument, currentUid: String, username: String) = Unit
    }

    private class FakeJsonVault : JsonVault {
        private var root = JSONObject()
        override fun read() = JSONObject(root.toString())
        override fun update(change: (JSONObject) -> Unit) { root = read().also(change) }
        override fun clear() { root = JSONObject() }
    }

    private companion object { const val ALICE = "alice"; const val BOB = "bob" }
}
