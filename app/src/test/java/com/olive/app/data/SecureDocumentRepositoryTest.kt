// Tests wrapped keys and document metadata for approved viewers.
package com.olive.app.data

import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.safety.SecureDocumentRepository
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
class SecureDocumentRepositoryTest {

    // Inputs: the values shown below. Expected: test envelope encryption math and json mapping.
    @Test
    fun `test envelope encryption math and json mapping`() {
        // Use test RSA keys in place of Android Keystore keys.
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val ownerKey = keyGen.generateKeyPair().public
        val staffKey = keyGen.generateKeyPair().public

        val title = "Therapy Notes"
        val body = "Discussed safety plan."
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }.toString()

        val documentKey = EncryptionUtils.generateSymmetricKey()
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)
        val ownerWrappedKey = KeyManager.wrapKey(documentKey, ownerKey)
        val staffWrappedKey = KeyManager.wrapKey(documentKey, staffKey)

        assertTrue("Ciphertext must not be empty", ciphertext.isNotEmpty())
        assertFalse("Ciphertext must hide the plaintext title", ciphertext.contains(title))
        assertTrue("Owner key wrapped", ownerWrappedKey.isNotEmpty())
        assertTrue("Staff key wrapped", staffWrappedKey.isNotEmpty())
        assertNotEquals("Wrapped keys must be unique", ownerWrappedKey, staffWrappedKey)

    }

    @Test
    fun anEditedShareKeepsExistingReadersAndRoundTripsPrivateIncidentFields() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val database = FirestoreTestDriver()
            val repository = SecureDocumentRepository(database.db)
            val owner = KeyManager.getOrCreatePublicKey()
            val guest = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            val entry = PrivateEntry(id = "incident", title = "Synthetic title", body = "First version", kind = PrivateEntryKind.Incident)
            val document = "privateEntries/${entry.id}"
            repository.saveMultiRecipientEntry(entry, "owner", mapOf("owner" to owner, "guest" to guest))
            val original = database.records.getValue(document)
            val originalEnvelope = checkNotNull((original["wrappedKeys"] as Map<*, *>)["guest"])
            assertFalse(original.getValue("ciphertext").toString().contains(entry.body))
            assertEquals(entry, repository.loadAndDecryptEntry(entry.id, "owner"))

            val updated = entry.copy(body = "Updated synthetic details")
            repository.saveMultiRecipientEntry(updated, "owner", mapOf("owner" to owner))
            val revised = database.records.getValue(document)
            assertEquals(originalEnvelope, (revised["wrappedKeys"] as Map<*, *>)["guest"])
            assertEquals(listOf("owner", "guest"), revised["allowedUsers"])
            assertEquals(original["createdAt"], revised["createdAt"])
            assertEquals(updated, repository.loadAndDecryptEntry(entry.id, "owner"))

            val missingKey = runCatching { repository.loadAndDecryptEntry(entry.id, "outsider") }.exceptionOrNull()
            assertEquals("You do not have the cryptographic key to read this document.", missingKey?.message)
            val missingDocument = runCatching { repository.loadAndDecryptEntry("missing", "owner") }.exceptionOrNull()
            assertEquals("Document not found.", missingDocument?.message)
        }
    }

    @Test
    fun loadAndDecryptEntryRecoversViaAccountKeysetAcrossDevices() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val database = FirestoreTestDriver()
            val repository = SecureDocumentRepository(database.db)
            val ownerPub = KeyManager.getOrCreatePublicKey()
            val staleGuestPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val guestRecovery = AccountRecoveryKey.generate()
            val guestKeyset = AccountKeysetEnvelope.create("guest", guestRecovery)
            database.records["accountKeyDirectories/guest"] = mapOf(
                "ownerUid" to "guest",
                "keyId" to guestKeyset.keyId,
                "epoch" to guestKeyset.epoch,
                "algorithm" to "RSA-OAEP-256",
                "publicKey" to guestKeyset.publicKeyBase64,
            )

            val entry = PrivateEntry(
                id = "shared-recover",
                title = "Cross-device note",
                body = "Visible after account recovery",
                kind = PrivateEntryKind.Note,
            )
            repository.saveMultiRecipientEntry(
                entry,
                "owner",
                mapOf("owner" to ownerPub, "guest" to staleGuestPair.public),
            )
            val stored = database.records.getValue("privateEntries/${entry.id}")
            assertTrue((stored["accountWrappedKeys"] as? Map<*, *>)?.containsKey("guest") == true)

            // Fails on a new device without unlocker because guest's wrappedKey is for the old device.
            val withoutUnlocker = runCatching { repository.loadAndDecryptEntry(entry.id, "guest") }.exceptionOrNull()
            assertNotNull(withoutUnlocker)

            // Succeeds with accountKeysetUnlocker and caches a wrapper for the new device.
            val recovered = repository.loadAndDecryptEntry(entry.id, "guest") {
                guestKeyset.unlock("guest", guestRecovery)
            }
            assertEquals(entry, recovered)
        }
    }
}
