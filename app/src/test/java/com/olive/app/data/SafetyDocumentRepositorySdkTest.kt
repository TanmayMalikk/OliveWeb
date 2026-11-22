package com.olive.app.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.FileDownloadTask
import com.olive.app.security.KeyManager
import org.robolectric.RuntimeEnvironment
import java.io.File
import com.google.firebase.storage.StorageReference
import com.olive.app.data.media.*
import com.olive.app.data.safety.*
import com.olive.app.security.EncryptionUtils
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate

@RunWith(RobolectricTestRunner::class)
class SafetyDocumentRepositorySdkTest {
    private val firestore = mock(FirebaseFirestore::class.java)
    private val storage = mock(FirebaseStorage::class.java)
    private val media = mock(MediaTicketDataSource::class.java)
    private val repository = SafetyDocumentRepository(firestore, storage, media) { }
    private val key = EncryptionUtils.generateSymmetricKey()

    @Test
    fun serverDocumentListUsesAuthoritativeMembershipAndReturnsNewestFirst() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val files = mock(CollectionReference::class.java)
            val query = mock(Query::class.java)
            val result = mock(QuerySnapshot::class.java)
            `when`(firestore.collection("files")).thenReturn(files)
            `when`(files.whereArrayContains("allowed_users", "owner")).thenReturn(query)
            val base = mapOf("owner_id" to "owner", "file_type" to "text/plain", "allowed_users" to listOf("owner"))
            val older = row("earlier", base + ("file_name" to "Earlier fixture"))
            val newer = row("latest", base + ("file_name" to "Latest fixture"))
            val invalid = row("invalid", emptyMap())
            `when`(older.getTimestamp("created_at")).thenReturn(com.google.firebase.Timestamp(java.util.Date(1L)))
            `when`(newer.getTimestamp("created_at")).thenReturn(com.google.firebase.Timestamp(java.util.Date(2L)))
            `when`(result.documents).thenReturn(listOf(older, invalid, newer))
            `when`(query.get(Source.SERVER)).thenReturn(Tasks.forResult(result))
            val snapshot = repository.listFor("owner")
            assertEquals(DocumentListSource.SERVER, snapshot.source)
            assertEquals(listOf("latest", "earlier"), snapshot.documents.map(SafetyDocument::id))
            assertNotNull(snapshot.lastSyncedAt)
            verify(query).get(Source.SERVER)
        }
    }

    @Test
    fun recoveredMetadataAndEvidenceAreReadFromAnAuthorizedServerDocument() = withDeviceKey {
        runBlocking {
            val fields = encryptedFields()
            val reference = reference("file", fields)
            val document = repository.getFor("owner", "file")
            assertEquals("Private sample.pdf", document.name)
            assertEquals("application/pdf", document.mimeType)
            assertTrue(document.evidence)
            assertEquals(listOf("evidence", "safety"), document.tags)
            assertEquals("Recognized sample", document.ocrText)
            assertEquals(.8f, document.ocrConfidence)
            verify(reference).get(Source.SERVER)
            assertTrue(document.deviceWrappedKeys.keys.single().startsWith("owner--"))
        }
    }

    @Test
    fun downloadAccessFailuresMapToRevocationAndConnectivityAndNeverReturnTheDocument() = withDeviceKey {
        runBlocking {
            val ref = reference("file", encryptedFields() + ("allowed_users" to listOf("different-user")))
            assertThrows(MediaAccessException::class.java) { runBlocking { repository.getFor("owner", "file") } }
            for ((code, reason) in listOf(
                FirebaseFirestoreException.Code.PERMISSION_DENIED to MediaAccessError.ACCESS_REVOKED,
                FirebaseFirestoreException.Code.NOT_FOUND to MediaAccessError.ACCESS_REVOKED,
                FirebaseFirestoreException.Code.UNAVAILABLE to MediaAccessError.NETWORK_REQUIRED,
            )) {
                `when`(ref.get(Source.SERVER)).thenReturn(Tasks.forException(FirebaseFirestoreException("Failure", code)))
                val failure = assertThrows(MediaAccessException::class.java) { runBlocking { repository.getFor("owner", "file") } }
                assertEquals(reason, failure.reason)
            }
            `when`(ref.get(Source.SERVER)).thenReturn(Tasks.forException(FirebaseFirestoreException("Invalid", FirebaseFirestoreException.Code.INVALID_ARGUMENT)))
            assertThrows(FirebaseFirestoreException::class.java) { runBlocking { repository.getFor("owner", "file") } }
        }
    }

    @Test
    fun removingIndependentAccessRetainsOnlyAuthorizedAccountAndDeviceEnvelopes() = withDeviceKey {
        runBlocking {
            reference("file", encryptedFields() + mapOf(
                "wrapped_keys" to mapOf("owner" to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey()), "trusted" to "trusted-envelope", "other" to "other-envelope"),
                "device_wrapped_keys" to mapOf("trusted--phone" to "trusted-device", "other--phone" to "other-device"),
                "account_wrapped_keys" to mapOf("owner" to accountWrapperMap(), "trusted" to accountWrapperMap(), "other" to accountWrapperMap()),
            ))
            repository.revokeAccess("file", "owner", "trusted")
            val call = mockingDetails(media).invocations.single { it.method.name == "updateAccess" }
            assertEquals("file", call.arguments[0])
            val retained = call.arguments[1] as Map<*, *>
            assertEquals(setOf("owner", "other"), retained.keys)
            assertEquals("other-envelope", retained["other"])
            assertArrayEquals(key.encoded, KeyManager.unwrapKey(retained["owner"] as String).encoded)
            @Suppress("UNCHECKED_CAST")
            val devices = call.arguments[2] as Map<String, String>
            assertEquals("other-device", devices["other--phone"])
            assertFalse(devices.keys.any { it.startsWith("trusted--") })
            assertEquals(setOf("owner", "other"), (call.arguments[3] as Map<*, *>).keys)
            assertEquals(emptyList<String>(), call.arguments[4])
            assertEquals(listOf("trusted"), call.arguments[5])
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.revokeAccess("file", "owner", "owner") } }
        }
    }

    @Test
    fun r2AndLegacyEvidenceAndDeletionUseTheirRespectiveStorageContracts() {
        runBlocking {
            val document = SafetyDocument("file", "owner", "legacy/file", "Sample.pdf", "application/pdf", listOf("owner"))
            val ref = reference("file", emptyMap())
            `when`(ref.update("evidence", true)).thenReturn(Tasks.forResult(null))
            repository.setEvidence(document, "owner", true)
            verify(ref).update("evidence", true)

            val r2 = document.copy(objectKey = "encrypted/file")
            repository.setEvidence(r2, "owner", false)
            verify(media).updateEvidence("file", false)
            repository.delete(r2, "owner")
            verify(media).deleteMedia("file")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.setEvidence(r2, "viewer", true) } }

            val root = mock(StorageReference::class.java)
            val objectRef = mock(StorageReference::class.java)
            `when`(storage.reference).thenReturn(root)
            `when`(root.child("legacy/file")).thenReturn(objectRef)
            `when`(objectRef.delete()).thenReturn(Tasks.forResult(null))
            `when`(ref.delete()).thenReturn(Tasks.forResult(null))
            repository.delete(document, "owner")
            val order = inOrder(objectRef, ref)
            order.verify(objectRef).delete()
            order.verify(ref).delete()
        }
    }

    @Test
    fun cachedFileSnapshotsStayReadableAndSubscriptionCanBeClosed() = TestAndroidKeyStore.install().use {
        val files = mock(CollectionReference::class.java)
        val query = mock(Query::class.java)
        val registration = mock(ListenerRegistration::class.java)
        `when`(firestore.collection("files")).thenReturn(files)
        `when`(files.whereArrayContains("allowed_users", "owner")).thenReturn(query)
        `when`(query.addSnapshotListener(eq(MetadataChanges.INCLUDE), any<EventListener<QuerySnapshot>>())).thenReturn(registration)
        val updates = mutableListOf<DocumentListSnapshot>()
        val errors = mutableListOf<String>()
        val subscription = repository.observeFor("owner", updates::add, errors::add)
        val capture = ArgumentCaptor.captor<EventListener<QuerySnapshot>>()
        verify(query).addSnapshotListener(eq(MetadataChanges.INCLUDE), capture.capture())
        val snapshot = mock(QuerySnapshot::class.java)
        val metadata = mock(SnapshotMetadata::class.java)
        `when`(snapshot.metadata).thenReturn(metadata)
        `when`(metadata.isFromCache).thenReturn(true)
        val legacy = row("legacy", mapOf("owner_id" to "owner", "file_name" to "Legacy.txt", "file_type" to "text/plain", "allowed_users" to listOf("owner")))
        val encrypted = row("encrypted", encryptedFields())
        `when`(snapshot.documents).thenReturn(listOf(legacy, encrypted))
        capture.value.onEvent(snapshot, null)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (updates.isEmpty() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val names = updates.single().documents.map(SafetyDocument::name).toSet()
        assertEquals(setOf("Legacy.txt", "Private sample.pdf"), names)
        assertEquals(DocumentListSource.FIRESTORE_CACHE, updates.single().source)
        capture.value.onEvent(null, FirebaseFirestoreException("No access", FirebaseFirestoreException.Code.PERMISSION_DENIED))
        assertEquals(listOf("No access"), errors)
        subscription.close()
        verify(registration).remove()
    }

    @Test
    fun legacyShareRollsBackStoragePermissionsWhenFirestoreRejectsTheUpdate() = withDeviceKey { runBlocking<Unit> {
        val legacy = mapOf("owner_id" to "owner", "file_name" to "Legacy.pdf",
            "file_type" to "application/pdf", "storage_path" to "legacy/file",
            "allowed_users" to listOf("owner", "old"))
        val reference = reference("file", legacy)
        val root = mock(StorageReference::class.java)
        val objectRef = mock(StorageReference::class.java)
        `when`(storage.reference).thenReturn(root)
        `when`(root.child(anyString())).thenReturn(objectRef)
        val metadata = mutableListOf<StorageMetadata>()
        `when`(objectRef.updateMetadata(any<StorageMetadata>())).thenAnswer {
            val update = it.getArgument<StorageMetadata>(0)
            metadata += update
            Tasks.forResult(update)
        }
        val denied = FirebaseFirestoreException("Synthetic update rejected", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(reference.update("allowed_users", listOf("owner", "new"))).thenReturn(Tasks.forException(denied))
        val document = SafetyDocument("file", "owner", "legacy/file", "Legacy.pdf",
            "application/pdf", listOf("owner", "old"))

        assertSame(denied, runCatching { repository.share(document, "owner", mapOf("new" to "fixture_new")) }.exceptionOrNull())
        assertEquals(2, metadata.size)
        assertEquals("true", metadata.first().getCustomMetadata("access_new"))
        assertTrue(metadata.first().getCustomMetadata("access_old").isNullOrEmpty())
        assertEquals("true", metadata.last().getCustomMetadata("access_old"))
        assertTrue(metadata.last().getCustomMetadata("access_new").isNullOrEmpty())
    } }

    @Test
    fun failedRecoveryPublishesKeysOnlyForVerifiedNewDevices() = withDeviceKey {
        runBlocking {
            val document = SafetyDocument(
                "file", "owner", "", "Private sample.pdf", "application/pdf", listOf("owner"),
                objectKey = "encrypted/file",
                wrappedKeys = mapOf("owner" to KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey())),
                deviceWrappedKeys = mapOf("owner--existing" to "existing-envelope"),
            )
            val directory = mock(CollectionReference::class.java)
            val account = mock(DocumentReference::class.java)
            val devices = mock(CollectionReference::class.java)
            val snapshot = mock(QuerySnapshot::class.java)
            `when`(firestore.collection("messageDirectory")).thenReturn(directory)
            `when`(directory.document("fixture_owner")).thenReturn(account)
            val owner = row("owner", mapOf("uid" to "owner"))
            `when`(account.get()).thenReturn(Tasks.forResult(owner))
            `when`(account.collection("devices")).thenReturn(devices)
            `when`(devices.get()).thenReturn(Tasks.forResult(snapshot))
            val publicKey = java.util.Base64.getEncoder().encodeToString(KeyManager.getOrCreatePublicKey().encoded)
            val valid = mapOf("uid" to "owner", "deviceId" to "new", "publicKey" to publicKey, "mgf1Digest" to "SHA-256")
            val registered = listOf(
                row("new", valid),
                row("legacy", valid + mapOf("deviceId" to "legacy", "mgf1Digest" to "SHA-1")),
                row("existing", valid + ("deviceId" to "existing")),
                row("foreign", valid + ("uid" to "other")),
                row("incomplete", valid - "deviceId"),
                row("invalid", valid + mapOf("deviceId" to "invalid", "publicKey" to "bad-key")),
            )
            `when`(snapshot.documents).thenReturn(registered)

            repository.approveCurrentAccountDevices(document, "owner", " Fixture_Owner ")

            val invocations = mockingDetails(media).invocations
            val update = invocations.single { it.method.name == "updateDeviceKeys" }
            assertEquals("file", update.arguments[0])
            val wrapped = update.arguments[1] as Map<*, *>
            assertEquals(setOf("owner--new", "owner--legacy"), wrapped.keys)
            for (envelope in wrapped.values) {
                assertArrayEquals(key.encoded, KeyManager.unwrapKey(envelope as String).encoded)
            }
            repository.approveCurrentAccountDevices(document.copy(accountRecoveryReady = true), "owner", "fixture_owner")
            repository.approveCurrentAccountDevices(document, "other", "fixture_owner")
            verify(devices, times(2)).get()
        }
    }

    @Test
    fun legacyGrantAndRevokeKeepStoragePermissionsAlignedWithFirestore() = withDeviceKey { runBlocking<Unit> {
        val document = SafetyDocument("file", "owner", "legacy/file", "Sample.pdf", "application/pdf", listOf("owner"))
        val fields = mapOf("owner_id" to "owner", "storage_path" to "legacy/file", "allowed_users" to listOf("owner"))
        val reference = reference("file", fields)
        val root = mock(StorageReference::class.java)
        val objectRef = mock(StorageReference::class.java)
        `when`(storage.reference).thenReturn(root)
        `when`(root.child("legacy/file")).thenReturn(objectRef)
        val writes = mutableListOf<StorageMetadata>()
        `when`(objectRef.updateMetadata(any<StorageMetadata>())).thenAnswer {
            Tasks.forResult(it.getArgument<StorageMetadata>(0).also(writes::add))
        }
        `when`(reference.update(eq("allowed_users"), any<FieldValue>())).thenReturn(Tasks.forResult(null))

        val unsupported = runCatching { repository.grantAccess(document, "owner", "friend", "fixture_friend", forPlan = true) }
        assertTrue(unsupported.exceptionOrNull() is IllegalStateException)
        assertTrue(writes.isEmpty())
        repository.grantAccess(document, "owner", "friend", "fixture_friend")
        assertEquals("true", writes.single().getCustomMetadata("access_friend"))

        val shared = row("file", fields + ("allowed_users" to listOf("owner", "friend")))
        `when`(reference.get(Source.SERVER)).thenReturn(Tasks.forResult(shared))
        val denied = FirebaseFirestoreException("Synthetic revoke denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        `when`(reference.update(eq("allowed_users"), any<FieldValue>())).thenReturn(Tasks.forException(denied), Tasks.forResult(null))
        assertSame(denied, runCatching { repository.revokeAccess("file", "owner", "friend") }.exceptionOrNull())
        assertTrue(writes[1].getCustomMetadata("access_friend").isNullOrEmpty())
        assertEquals("true", writes[2].getCustomMetadata("access_friend"))

        repository.revokeAccess("file", "owner", "friend")
        assertTrue(writes.last().getCustomMetadata("access_friend").isNullOrEmpty())
        verify(reference, times(3)).update(eq("allowed_users"), any<FieldValue>())
    } }

    @Test
    fun legacyDownloadPreservesSafeExtensionsAndRemovesExpiredPreviewFiles() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val cache = File(context.cacheDir, "opened-documents").apply { mkdirs() }
        val old = File(cache, "expired.pdf").apply { writeText("Synthetic expired preview"); setLastModified(1) }
        val root = mock(StorageReference::class.java)
        val file = mock(StorageReference::class.java)
        val task = mock(FileDownloadTask::class.java)
        `when`(storage.reference).thenReturn(root)
        `when`(root.child("legacy/file")).thenReturn(file)
        `when`(task.isComplete).thenReturn(true)
        `when`(file.getFile(any<File>())).thenReturn(task)
        val document = SafetyDocument("file", "owner", "legacy/file", "Legacy.pdf",
            "application/pdf", listOf("owner"))
        assertEquals("file.pdf", repository.download(context, document).name)
        assertFalse(old.exists())
        assertEquals("file", repository.download(context, document.copy(name = "Legacy.very-long-ext")).name)
        assertTrue(runCatching { repository.download(context, document.copy(objectKey = "encrypted/file")) }.isFailure)
    }

    private fun encryptedFields(): Map<String, Any> {
        val metadata = JSONObject().put("name", "Private sample.pdf").put("mimeType", "application/pdf")
            .put("tags", org.json.JSONArray(listOf("evidence", "safety")))
            .put("ocrText", "Recognized sample").put("ocrConfidence", .8).put("ocrSuccessful", true)
        return mapOf(
            "owner_id" to "owner", "object_key" to "encrypted/file", "allowed_users" to listOf("owner", "trusted"),
            "account_recovery_ready" to true, "evidence" to true, "encrypted_metadata" to EncryptionUtils.encryptText(metadata.toString(), key),
            "wrapped_keys" to mapOf("owner" to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey())),
            "device_wrapped_keys" to mapOf("owner--${KeyManager.deviceId()}" to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey())),
        )
    }

    private fun reference(id: String, fields: Map<String, Any>): DocumentReference {
        val collection = mock(CollectionReference::class.java)
        val reference = mock(DocumentReference::class.java)
        `when`(firestore.collection("files")).thenReturn(collection)
        `when`(collection.document(id)).thenReturn(reference)
        val snapshot = row(id, fields)
        `when`(reference.get(Source.SERVER)).thenReturn(Tasks.forResult(snapshot))
        return reference
    }

    private fun row(id: String, fields: Map<String, Any>): DocumentSnapshot =
        mock(DocumentSnapshot::class.java).also { row ->
            `when`(row.id).thenReturn(id)
            `when`(row.get(anyString())).thenAnswer { fields[it.getArgument<String>(0)] }
            `when`(row.getString(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? String }
            `when`(row.getBoolean(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Boolean }
            `when`(row.getLong(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Long }
        }

    private fun record() = CloudMediaRecord("file", "owner", "encrypted/file", "", emptyMap(),
        listOf("owner"), 20, "checksum", 2, 1)

    private fun accountWrapperMap() = mapOf(
        "keyId" to "123e4567-e89b-42d3-a456-426614174000",
        "epoch" to 1L,
        "algorithm" to "RSA-OAEP-256",
        "wrappedKey" to "opaque-wrapper",
    )

    private fun withDeviceKey(work: () -> Unit) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = mock(Certificate::class.java)
        val store = mock(KeyStore::class.java)
        `when`(certificate.publicKey).thenReturn(pair.public)
        `when`(store.getCertificate(anyString())).thenReturn(certificate)
        `when`(store.getKey(anyString(), isNull())).thenReturn(pair.private)
        mockStatic(KeyStore::class.java).use { keys ->
            keys.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(store)
            work()
        }
    }
}
