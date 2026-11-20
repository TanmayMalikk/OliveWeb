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
            doc=runBlocking { repo.getFor("owner","doc") }
        }
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
            assertEquals(listOf("friend"),update.arguments[3])
        }
    }
    @Test
    fun independentAndPlanGrantsUseSeparateAuthorizationSources()=runBlocking<Unit> {
        Fixture().use { f ->
            f.repo.grantAccess(f.doc,"owner","friend","friend_fixture",true)
            assertEquals(1,mockingDetails(f.media).invocations.count { it.method.name=="updatePlanAccess" })
            f.repo.grantAccess(f.doc,"owner","friend","friend_fixture",false)
            val independent=mockingDetails(f.media).invocations.single { it.method.name=="updateAccess" }
            assertEquals(listOf("friend"),independent.arguments[3])
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

            // Recovery-ready listFor decrypts unreadable metadata via recoverDocumentKey and tolerates failures
            f.db.records["files/recovered"] = mapOf(
                "owner_id" to "owner", "object_key" to "encrypted/recovered",
                "allowed_users" to listOf("owner"),
                "wrapped_keys" to mapOf("other" to "unreadable"),
                "encryption_version" to 2L,
                "account_recovery_ready" to true,
                "encrypted_metadata" to EncryptionUtils.encryptText("""{"name":"Recovered.pdf","mimeType":"application/pdf"}""", f.key),
            )
            f.db.records["files/failed"] = mapOf(
                "owner_id" to "owner", "object_key" to "encrypted/failed",
                "allowed_users" to listOf("owner"),
                "wrapped_keys" to mapOf("other" to "unreadable"),
                "encryption_version" to 2L,
                "account_recovery_ready" to true,
                "encrypted_metadata" to "unreadable-cipher",
            )
            `when`(f.media.recoverDocumentKey("recovered")).thenReturn(f.key)
            `when`(f.media.recoverDocumentKey("failed")).thenThrow(IllegalStateException("Unavailable"))
            val listed = f.repo.listFor("owner").documents
            assertTrue(listed.any { it.id == "recovered" && it.name == "Recovered.pdf" })
            assertTrue(listed.any { it.id == "failed" && it.name == "Encrypted document" })
        }
    }
}
