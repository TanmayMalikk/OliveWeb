package com.olive.app.data.messaging

import com.olive.app.data.account.AccountIdentity
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SecureChatRepositorySdkTest {
    private val transport = FirestoreTestDriver()
    private val repository = SecureChatRepository(transport.db)
    private val conversation = ChatConversation("chat", "pair", listOf("owner", "friend"),
        mapOf("owner" to "owner_fixture", "friend" to "friend_fixture"))

    @Test
    fun roomKeySurvivesAddingAParticipantAndHistoryPreventsUnapprovedKeyReplacement() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val own = KeyManager.getOrCreatePublicKey()
            val peer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            transport.records["conversations/chat"] = mapOf("participantIds" to conversation.participantIds)
            val key = repository.ensureRoomKey(conversation, "owner", mapOf("owner" to own, "friend" to peer))
            val first = transport.records.getValue("conversations/chat")
            val wrapped = first["roomKeys"] as Map<*, *>
            assertArrayEquals(key.encoded, KeyManager.unwrapKey(wrapped["owner"] as String).encoded)
            assertNotEquals(wrapped["owner"], wrapped["friend"])
            assertEquals(setOf("owner", "friend"), (first["participantPublicKeys"] as Map<*, *>).keys)
            assertArrayEquals(key.encoded, repository.existingRoomKey("chat", "owner")?.encoded)
            assertArrayEquals(key.encoded, repository.readyRoomKey(conversation, "owner")?.secretKey?.encoded)
            assertNull(repository.readyRoomKey(conversation.copy(participantIds=listOf("owner")), "owner"))

            val extra = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            val expanded = conversation.copy(participantIds = conversation.participantIds + "new",
                participantUsernames = conversation.participantUsernames + ("new" to "new_fixture"))
            val updated = repository.ensureRoomKey(expanded, "owner", mapOf("owner" to own, "friend" to peer, "new" to extra))
            assertArrayEquals(key.encoded, updated.encoded)
            assertEquals(setOf("owner", "friend", "new"), (transport.records.getValue("conversations/chat")["roomKeys"] as Map<*, *>).keys)

            transport.records["conversations/chat"] = transport.records.getValue("conversations/chat") + mapOf(
                "roomKeys" to mapOf("owner" to "invalid-old-envelope"), "lastMessageId" to "history",
            )
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.ensureRoomKey(conversation, "owner", mapOf("owner" to own, "friend" to peer)) }
            }
        }
    }

    @Test
    fun deliveringAnOutboxRecordIsIdempotentAndRejectsAnotherMessageWithTheSameId() = runBlocking<Unit> {
        val key = EncryptionUtils.generateSymmetricKey()
        val outgoing = prepareOutgoingMessage("chat", AccountIdentity("owner", "owner_fixture"), "Private sample", key,
            id = "stable-id", now = 100)
        repository.deliver(outgoing)
        val saved = transport.records.getValue("conversations/chat/messages/stable-id")
        assertEquals(outgoing.ciphertext, saved["ciphertext"])
        assertFalse(saved.values.contains("Private sample"))
        assertEquals("stable-id", transport.records.getValue("conversations/chat")["lastMessageId"])
        val writes = transport.mutations.size
        repository.deliver(outgoing)
        assertEquals(writes, transport.mutations.size)
        assertThrows(MessageDeliveryRejected::class.java) {
            runBlocking { repository.deliver(outgoing.copy(ciphertext = "another-message")) }
        }
        assertEquals(saved, transport.records.getValue("conversations/chat/messages/stable-id"))
    }

    @Test
    fun senderCanUnsendTheirOwnMessageWithoutAlteringTheEarlierMessages() = runBlocking<Unit> {
        val key = EncryptionUtils.generateSymmetricKey()
        repository.send("chat", AccountIdentity("owner", "owner_fixture"), "First sample", key)
        val firstId = transport.records.getValue("conversations/chat")["lastMessageId"] as String
        repository.send("chat", AccountIdentity("owner", "owner_fixture"), "Second sample", key)
        val secondId = transport.records.getValue("conversations/chat")["lastMessageId"] as String
        val message = ChatMessage(secondId, "owner", "owner_fixture", "Second sample", 1)
        repository.unsend("chat", message, "owner")
        assertEquals(true, transport.records.getValue("conversations/chat/messages/$secondId")["deleted"])
        assertEquals("Message unsent", transport.records.getValue("conversations/chat/messages/$secondId")["ciphertext"])
        assertEquals(false, transport.records.getValue("conversations/chat/messages/$firstId")["deleted"])
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.unsend("chat", message, "other") }
        }
    }

    @Test
    fun registeredDevicesReceiveIndependentEnvelopesAndUnknownDevicesAreExcluded() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val key = EncryptionUtils.generateSymmetricKey()
            val own = KeyManager.getOrCreatePublicKey()
            val encoded = Base64.getEncoder().encodeToString(own.encoded)
            transport.records["messageDirectory/owner_fixture/devices/valid"] = mapOf(
                "uid" to "owner", "deviceId" to "browser", "publicKey" to encoded, "mgf1Digest" to "SHA-256",
            )
            transport.records["messageDirectory/owner_fixture/devices/unknown"] = mapOf(
                "uid" to "other", "deviceId" to "intruder", "publicKey" to encoded,
            )
            transport.records["messageDirectory/friend_fixture/devices/invalid"] = mapOf(
                "uid" to "friend", "deviceId" to "broken", "publicKey" to "malformed",
            )
            repository.shareWithRegisteredDevices(conversation, key)
            val saved = transport.records.getValue("conversations/chat/deviceKeys/owner--browser")
            assertEquals("owner", saved["uid"])
            assertArrayEquals(key.encoded, KeyManager.unwrapKey(saved["wrappedKey"] as String).encoded)
            assertFalse(transport.records.containsKey("conversations/chat/deviceKeys/other--intruder"))
            assertFalse(transport.records.containsKey("conversations/chat/deviceKeys/friend--broken"))
        }
    }

    @Test
    fun registeredKeyChangesHaveStablePathsAndStopWhenTheConversationCloses() {
        TestAndroidKeyStore.install().use {
            val keys=mutableListOf<Pair<String,String>>()
            val devices=mutableListOf<Pair<String,String>>()
            val subscription=repository.observeKeyChanges(conversation,"owner",{path,key -> keys+=path to key},{path,value -> devices+=path to value})
            transport.records["messageDirectory/owner_fixture"]=mapOf("publicKey" to "owner-key")
            transport.records["messageDirectory/owner_fixture/devices/browser"]=mapOf("publicKey" to "browser-key","mgf1Digest" to "SHA-256")
            transport.emit("messageDirectory/owner_fixture")
            transport.emit("messageDirectory/owner_fixture/devices")
            assertEquals(listOf("messageDirectory/owner_fixture" to "owner-key"),keys)
            assertTrue(devices.single().second.contains("browser:browser-key:SHA-256"))
            subscription.close()
            assertTrue(transport.listeners.all { it.closed })
            transport.emit("messageDirectory/owner_fixture")
            assertEquals(1,keys.size)
        }
    }

    @Test
    fun messageObserverAuthenticatesPayloadsPreservesUnsentMarkersAndRejectsCorruptCiphertext() {
        val key=EncryptionUtils.generateSymmetricKey()
        var messages:List<ChatMessage>?=null
        val errors=mutableListOf<String>()
        var confirmed=0
        val path="conversations/chat/messages"
        val subscription=repository.observeMessages("chat",key,{messages=it},{errors+=it},{confirmed++})
        transport.records["$path/received"]=mapOf("senderUid" to "friend","senderUsername" to "friend_fixture","ciphertext" to EncryptionUtils.encryptText("Synthetic private text",key),"clientSentAt" to 10L)
        fun await(check:()->Boolean) {
            val until=System.nanoTime()+5_000_000_000
            while(!check() && System.nanoTime()<until) {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertTrue("Observer callback did not finish",check())
        }
        transport.emit(path)
        await {messages!=null}
        assertEquals("Synthetic private text",messages!!.single().body)
        assertEquals(1,confirmed)
        transport.records["$path/received"]=transport.records.getValue("$path/received")+mapOf("deleted" to true,"ciphertext" to "Message unsent")
        transport.emit(path)
        await {messages!!.single().unsent}
        assertEquals("",messages!!.single().body)
        transport.records["$path/received"]=transport.records.getValue("$path/received")+mapOf("deleted" to false,"ciphertext" to "corrupted")
        transport.emit(path)
        await {errors.isNotEmpty()}
        assertTrue(errors.single().contains("authentication failed"))
        subscription.close()
        assertTrue(transport.listeners.all {it.closed})
    }

}
