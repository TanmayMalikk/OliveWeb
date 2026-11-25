package com.olive.app.data.safety

import com.google.firebase.storage.FirebaseStorage
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.messaging.prepareOutgoingMessage
import com.olive.app.security.*
import com.olive.app.support.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SafetyDocumentGrantSdkTest {
    private class Fixture:AutoCloseable {
        val keys=TestAndroidKeyStore.install()
        val db=FirestoreTestDriver()
        val media=mock(MediaTicketDataSource::class.java)
        val repo=SafetyDocumentRepository(db.db,mock(FirebaseStorage::class.java),media) { }
        val key=EncryptionUtils.generateSymmetricKey()
        val public=Base64.getEncoder().encodeToString(KeyManager.getOrCreatePublicKey().encoded)
        val doc:SafetyDocument
        init {
            db.records["files/doc"]=mapOf("owner_id" to "owner","object_key" to "encrypted/doc","allowed_users" to listOf("owner"),
                "wrapped_keys" to mapOf("owner" to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey())),"encryption_version" to KeyManager.CURRENT_KEY_WRAP_VERSION.toLong(),
                "encrypted_metadata" to EncryptionUtils.encryptText("{\"name\":\"Synthetic.pdf\",\"mimeType\":\"application/pdf\"}",key))
            db.records["messageDirectory/friend_fixture"]=mapOf("uid" to "friend","publicKey" to public)
            db.records["messageDirectory/friend_fixture/devices/phone"]=mapOf("uid" to "friend","deviceId" to "phone","publicKey" to public,"mgf1Digest" to "SHA-1")
            db.records["accountKeyDirectories/owner"]=accountDirectory("owner")
            db.records["accountKeyDirectories/friend"]=accountDirectory("friend")
            db.records["accountKeyDirectories/retained"]=accountDirectory("retained")
            doc=runBlocking { repo.getFor("owner","doc") }
        }
        private fun accountDirectory(uid:String)=mapOf("ownerUid" to uid,
            "keyId" to "123e4567-e89b-42d3-a456-426614174000","epoch" to 1L,
            "algorithm" to "RSA-OAEP-256","publicKey" to public)
        override fun close(){ keys.close() }
    }
    @Test
    fun directSharingWrapsTheDocumentKeyForAccountAndRegisteredDevices()=runBlocking<Unit> {
        Fixture().use { f ->
            f.repo.share(f.doc,"owner",mapOf("friend" to "friend_fixture"))
            val update=mockingDetails(f.media).invocations.single { it.method.name=="updateAccess" }
            val accounts=update.arguments[1] as Map<*,*>;val devices=update.arguments[2] as Map<*,*>
            assertEquals(setOf("owner","friend"),accounts.keys)
            assertArrayEquals(f.key.encoded,KeyManager.unwrapKey(accounts["friend"] as String).encoded)
            assertArrayEquals(f.key.encoded,KeyManager.unwrapKey(devices["friend--phone"] as String).encoded)
            assertEquals(setOf("owner","friend"),(update.arguments[3] as Map<*,*>).keys)
            assertEquals(listOf("friend"),update.arguments[4])
        }
    }
    @Test
    fun independentAndPlanGrantsUseSeparateAuthorizationSources()=runBlocking<Unit> {
        Fixture().use { f ->
            f.repo.grantAccess(f.doc,"owner","friend","friend_fixture",true)
            assertEquals(1,mockingDetails(f.media).invocations.count { it.method.name=="updatePlanAccess" })
            f.repo.grantAccess(f.doc,"owner","friend","friend_fixture",false)
            val independent=mockingDetails(f.media).invocations.single { it.method.name=="updateAccess" }
            assertEquals(listOf("friend"),independent.arguments[4])
            f.db.records["files/doc"]=f.db.records.getValue("files/doc")+mapOf("allowed_users" to listOf("owner","friend"),"device_wrapped_keys" to mapOf("friend--phone" to "existing"))
            clearInvocations(f.media)
            f.repo.grantAccess(f.doc,"owner","friend","friend_fixture",true)
            verifyNoInteractions(f.media)
        }
    }
    @Test
    fun chatDeliveryKeepsItsOutboxIdentityAndRejectsARecipientWithNoRegisteredDevices()=runBlocking<Unit> {
        Fixture().use { f ->
            val outgoing=prepareOutgoingMessage("chat",AccountIdentity("owner","fixture_owner"),"SHARED_MEDIA:doc:Synthetic.pdf",f.key)
            f.repo.shareInChat(f.doc,outgoing,mapOf("friend" to "friend_fixture"))
            val sent=mockingDetails(f.media).invocations.single { it.method.name=="shareInChat" }
            assertEquals(outgoing,sent.arguments[1]);assertEquals(listOf("friend"),sent.arguments[2])
            f.db.records.remove("messageDirectory/friend_fixture/devices/phone")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.repo.share(f.doc,"owner",mapOf("friend" to "friend_fixture")) } }
            clearInvocations(f.media)
            f.repo.share(f.doc,"owner",emptyMap())
            val revoked=mockingDetails(f.media).invocations.single { it.method.name=="updateAccess" }
            assertEquals(setOf("owner"),(revoked.arguments[1] as Map<*,*>).keys)

            // Blank username preserves pre-existing wrappedKeys for that recipient
            val currentDocRecord = f.db.records.getValue("files/doc")
            @Suppress("UNCHECKED_CAST")
            val existingWrapped = currentDocRecord["wrapped_keys"] as Map<String, String>
            f.db.records["files/doc"] = currentDocRecord + ("wrapped_keys" to (existingWrapped + ("retained" to "wrapped-retained")))
            val retainedDoc = f.doc.copy(wrappedKeys = f.doc.wrappedKeys + ("retained" to "wrapped-retained"))
            f.repo.share(retainedDoc, "owner", mapOf("retained" to ""))
            val retainedCall = mockingDetails(f.media).invocations.last { it.method.name == "updateAccess" }
            assertEquals("wrapped-retained", (retainedCall.arguments[1] as Map<*, *>)["retained"])
            assertTrue((retainedCall.arguments[3] as Map<*, *>).containsKey("retained"))

            // Unreadable device keys still parse account-keyset wrappers and fall back cleanly when invalid
            f.db.records["files/recovered"] = mapOf(
                "owner_id" to "owner", "object_key" to "encrypted/recovered",
                "allowed_users" to listOf("owner"),
                "wrapped_keys" to mapOf("other" to "unreadable"),
                "account_wrapped_keys" to mapOf(
                    "owner" to mapOf(
                        "keyId" to "123e4567-e89b-42d3-a456-426614174000",
                        "epoch" to 1L,
                        "algorithm" to "RSA-OAEP-256",
                        "wrappedKey" to "opaque-wrapper",
                    ),
                ),
                "encryption_version" to 2L,
                "encrypted_metadata" to EncryptionUtils.encryptText("""{"name":"Recovered.pdf","mimeType":"application/pdf"}""", f.key),
            )
            f.db.records["files/failed"] = mapOf(
                "owner_id" to "owner", "object_key" to "encrypted/failed",
                "allowed_users" to listOf("owner"),
                "wrapped_keys" to mapOf("other" to "unreadable"),
                "account_wrapped_keys" to mapOf("owner" to mapOf("keyId" to "invalid")),
                "encryption_version" to 2L,
                "encrypted_metadata" to "unreadable-cipher",
            )
            val listed = f.repo.listFor("owner").documents
            val recoveredDoc = listed.single { it.id == "recovered" }
            assertEquals("Encrypted document", recoveredDoc.name)
            assertTrue(recoveredDoc.accountWrappedKeys.containsKey("owner"))
            assertTrue(listed.any { it.id == "failed" && it.name == "Encrypted document" && it.accountWrappedKeys.isEmpty() })

            // Recipients without an accountKeyDirectories record (e.g. SAFE_CONTACT / STAFF / ADMIN) can still receive chat and direct shares
            f.db.records["messageDirectory/safe_fixture"] = mapOf("uid" to "safe_contact", "publicKey" to f.public)
            f.db.records["messageDirectory/safe_fixture/devices/phone"] = mapOf("uid" to "safe_contact", "deviceId" to "phone", "publicKey" to f.public, "mgf1Digest" to "SHA-1")
            clearInvocations(f.media)
            f.repo.shareInChat(f.doc, outgoing, mapOf("safe_contact" to "safe_fixture"))
            val safeChatSent = mockingDetails(f.media).invocations.single { it.method.name == "shareInChat" }
            assertEquals(setOf("owner", "safe_contact"), (safeChatSent.arguments[3] as Map<*, *>).keys)
            assertEquals(setOf("owner"), (safeChatSent.arguments[5] as Map<*, *>).keys)
        }
    }
}
