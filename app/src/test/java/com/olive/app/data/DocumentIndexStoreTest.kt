package com.olive.app.data

import android.content.Context
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.data.safety.DocumentIndexStore
import com.olive.app.data.safety.DocumentListSnapshot
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.IndexedSafetyDocumentRepository
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
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
    fun `evidence changes pass through the index wrapper and survive export rereads`() = runBlocking {
        val index = DocumentIndexStore(FakeJsonVault())
        val remote = FakeRemote()
        val original = document("standalone-evidence", ALICE).copy(evidence = false)
        remote.documents = listOf(original)
        val repository = IndexedSafetyDocumentRepository(remote, index, deviceId = { "phone" })
        repository.listFor(ALICE)
        repository.setEvidence(original, ALICE, true)
        assertTrue(repository.cachedFor(ALICE, original.id)!!.evidence)
        val exportDocuments = repository.listFor(ALICE).documents
        assertTrue(exportDocuments.single().evidence)
        assertTrue(repository.cachedFor(ALICE, original.id)!!.evidence)
        val media = exportDocuments.map {
            com.olive.app.ui.users.user.documents.EmergencyReportMedia(it, null)
        }
        assertEquals(listOf(original.id), com.olive.app.ui.users.user.documents.unattachedEmergencyEvidence(emptyList(), media).map { it.document.id })
        repository.setEvidence(exportDocuments.single(), ALICE, false)
        assertFalse(repository.listFor(ALICE).documents.single().evidence)
    }
    @Test
    fun `index isolates accounts and retains only viewer wrapped keys`() {
        val store = DocumentIndexStore(FakeJsonVault())
        store.replace(ALICE, listOf(document("one", ALICE)), 100)
        store.replace(BOB, listOf(document("two", BOB)), 200)

        val alice = store.read(ALICE)
        assertEquals(listOf("one"), alice.documents.map { it.id })
        assertEquals(setOf(ALICE), alice.documents.single().wrappedKeys.keys)
        assertEquals(setOf(ALICE), alice.documents.single().accountWrappedKeys.keys)
        assertTrue(alice.documents.single().deviceWrappedKeys.keys.all { it.startsWith("$ALICE--") })
        assertFalse(alice.documents.single().deviceWrappedKeys.keys.any { it.startsWith("$BOB--") })
        assertTrue(alice.documents.single().evidence)
        assertEquals(listOf("Safety Plan"), alice.documents.single().tags)

        store.purge(ALICE)
        assertTrue(store.read(ALICE).documents.isEmpty())
        assertEquals(listOf("two"), store.read(BOB).documents.map { it.id })
    }

    @Test
    fun `index preserves chatOnly flag across reads`() {
        val store = DocumentIndexStore(FakeJsonVault())
        store.replace(
            ALICE,
            listOf(document("doc", ALICE), document("voice", ALICE).copy(chatOnly = true)),
            100,
        )
        val byId = store.read(ALICE).documents.associateBy(SafetyDocument::id)
        assertFalse(byId.getValue("doc").chatOnly)
        assertTrue(byId.getValue("voice").chatOnly)
    }

    @Test
    fun `firestore cache cannot purge but authoritative server snapshot can`() {
        val index = DocumentIndexStore(FakeJsonVault())
        index.replace(ALICE, listOf(document("kept", ALICE), document("revoked", ALICE)), 100)
        val remote = FakeRemote()
        val repository = IndexedSafetyDocumentRepository(remote, index, deviceId = { "phone" }) { 500 }
        val observed = mutableListOf<DocumentListSnapshot>()
        repository.observeFor(ALICE, observed::add) { error(it) }

        remote.emit(DocumentListSnapshot(emptyList(), DocumentListSource.FIRESTORE_CACHE))
        assertEquals(setOf("kept", "revoked"), observed.last().documents.mapTo(mutableSetOf()) { it.id })

        remote.emit(
            DocumentListSnapshot(
                listOf(document("kept", ALICE)),
                DocumentListSource.SERVER,
                400
            )
        )
        assertEquals(listOf("kept"), index.read(ALICE).documents.map { it.id })
        assertEquals(400L, index.read(ALICE).lastSyncedAt)
    }

    @Test
    fun `recovered metadata and current device wrapper survive a stale server snapshot`() = runBlocking {
        val index = DocumentIndexStore(FakeJsonVault())
        val remote = FakeRemote()
        val encrypted = document("recovered", ALICE).copy(
            name = "Encrypted document",
            mimeType = "application/octet-stream",
            deviceWrappedKeys = emptyMap(),
        )
        remote.documents = listOf(encrypted)
        val repository = IndexedSafetyDocumentRepository(
            remote = remote,
            index = index,
            clock = { 500 },
            deviceId = { "new-device" },
        )
        repository.listFor(ALICE)
        repository.rememberRecovered(
            ALICE,
            encrypted.copy(
                name = "recovered.pdf",
                mimeType = "application/pdf",
                deviceWrappedKeys = mapOf("$ALICE--new-device" to "local-device-wrapper"),
            ),
        )

        val refreshed = repository.listFor(ALICE).documents.single()

        assertEquals("recovered.pdf", refreshed.name)
        assertEquals("application/pdf", refreshed.mimeType)
        assertEquals("local-device-wrapper", refreshed.deviceWrappedKeys["$ALICE--new-device"])
    }

    @Test
    fun `authorization failure never falls back through getFor`() = runBlocking {
        val index = DocumentIndexStore(FakeJsonVault())
        index.replace(ALICE, listOf(document("cached", ALICE)), 100)
        val repository = IndexedSafetyDocumentRepository(
            FakeRemote(getFailure = SecurityException("revoked")),
            index,
            deviceId = { "phone" },
        )

        assertEquals("cached", repository.cachedFor(ALICE, "cached")?.id)
        val failure = runCatching { repository.getFor(ALICE, "cached") }.exceptionOrNull()
        assertTrue(failure is SecurityException)
    }

    @Test
    fun `offline reads preserve visible access until an authoritative deletion`() = runBlocking<Unit> {
        val index = DocumentIndexStore(FakeJsonVault())
        val remote = FakeRemote()
        val original = document("cached", ALICE)
        val repository = IndexedSafetyDocumentRepository(remote, index, deviceId = { "phone" })
        remote.documents = listOf(original)
        repository.listFor(ALICE)
        val observed = mutableListOf<DocumentListSnapshot>()
        val errors = mutableListOf<String>()
        val observation = repository.observeFor(ALICE, observed::add, errors::add)
        val offline = IllegalStateException("Offline")
        remote.listFailure = offline
        remote.interrupt("Offline")
        assertTrue(errors.isEmpty())
        assertEquals(DocumentListSource.LOCAL_INDEX, observed.last().source)
        assertEquals(listOf(original.id), repository.listFor(ALICE).documents.map { it.id })

        repository.delete(original, ALICE)
        assertNull(repository.cachedFor(ALICE, original.id))
        remote.interrupt("Offline after deletion")
        assertEquals(listOf("Offline after deletion"), errors)
        assertTrue(runCatching { repository.listFor(ALICE) }.exceptionOrNull() === offline)
        observation.close()
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
        accountWrappedKeys = mapOf(
            ALICE to accountWrapper("123e4567-e89b-42d3-a456-426614174000", "account-a"),
            BOB to accountWrapper("223e4567-e89b-42d3-a456-426614174000", "account-b"),
        ),
        evidence = true,
        tags = listOf("Safety Plan"),
    ).also { require(viewer in it.allowedUsers) }

    private fun accountWrapper(keyId:String,wrappedKey:String)=AccountKeysetWrappedKey(
        keyId,1,"RSA-OAEP-256",wrappedKey,
    )

    private class FakeRemote(private val getFailure: Exception? = null) : SafetyDocumentDataSource {
        var documents = emptyList<SafetyDocument>()
        var listFailure: Exception? = null
        private var observer: ((DocumentListSnapshot) -> Unit)? = null
        private var errorObserver: ((String) -> Unit)? = null
        fun emit(value: DocumentListSnapshot) = checkNotNull(observer).invoke(value)
        fun interrupt(message: String) = checkNotNull(errorObserver).invoke(message)
        override suspend fun listFor(uid: String): DocumentListSnapshot {
            listFailure?.let { throw it }
            return DocumentListSnapshot(documents, DocumentListSource.SERVER, 1)
        }
        override suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) {
            require(document.ownerUid == ownerUid)
            documents = documents.map { if (it.id == document.id) it.copy(evidence = evidence) else it }
        }
        override fun observeFor(uid: String, onUpdate: (DocumentListSnapshot) -> Unit, onError: (String) -> Unit): ChatSubscription {
            observer = onUpdate
            errorObserver = onError
            return ChatSubscription {
                observer = null
                errorObserver = null
            }
        }
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
