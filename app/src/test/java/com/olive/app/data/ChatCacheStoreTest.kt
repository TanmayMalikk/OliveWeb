package com.olive.app.data

import com.olive.app.data.messaging.ChatCacheException
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.messaging.ChatCacheVault
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.EncryptedChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatCacheStoreTest {
    @Test
    fun `ciphertext cache caps diverse message batches at 250 newest records`() {
        listOf(0, 1, 249, 250, 251, 500).forEach { count ->
            val store = ChatCacheStore(InMemoryChatCacheVault())
            val conversation = conversation("chat-$count")
            store.saveAccess(ALICE, conversation, "wrapped", DEVICE, NOW)
            store.saveMessages(ALICE, conversation.id, messages(count), NOW + 1)

            val cached = store.open(ALICE, conversation.id, DEVICE, NOW + 2).messages
            assertEquals(minOf(count, 250), cached.size)
            if (count > 250) assertEquals("message-${count - 250}", cached.first().id)
        }
    }

    @Test
    fun `lease accepts its exact boundary and rejects expiry and clock rollback`() {
        val store = ChatCacheStore(InMemoryChatCacheVault())
        val conversation = conversation()
        store.saveAccess(ALICE, conversation, "wrapped", DEVICE, NOW)

        store.open(ALICE, conversation.id, DEVICE, NOW + ChatCacheStore.REVALIDATION_LEASE_MILLIS)
        expectCacheFailure {
            store.open(ALICE, conversation.id, DEVICE, NOW + ChatCacheStore.REVALIDATION_LEASE_MILLIS + 1)
        }
        expectCacheFailure { store.open(ALICE, conversation.id, DEVICE, NOW - 1) }
    }

    @Test
    fun `account device and participant boundaries fail closed`() {
        val store = ChatCacheStore(InMemoryChatCacheVault())
        val conversation = conversation()
        store.saveAccess(ALICE, conversation, "wrapped", DEVICE, NOW)

        expectCacheFailure { store.open(BOB, conversation.id, DEVICE, NOW) }
        expectCacheFailure { store.open(ALICE, conversation.id, "other-device", NOW) }
        assertEquals(listOf(conversation.id), store.conversations(ALICE).map(ChatConversation::id))
        assertTrue(store.conversations(BOB).isEmpty())
    }

    @Test
    fun `authoritative reconciliation removes revoked access but preserves other accounts`() {
        val vault = InMemoryChatCacheVault()
        val store = ChatCacheStore(vault)
        val retained = conversation("retained")
        val revoked = conversation("revoked")
        val bobConversation = conversation("bob", listOf(BOB, "carol"))
        store.saveAccess(ALICE, retained, "wrapped-a", DEVICE, NOW)
        store.saveAccess(ALICE, revoked, "wrapped-b", DEVICE, NOW)
        store.saveAccess(BOB, bobConversation, "wrapped-c", DEVICE, NOW)

        store.replaceIndex(ALICE, listOf(retained), NOW + 1)

        assertEquals(listOf("retained"), store.conversations(ALICE).map(ChatConversation::id))
        expectCacheFailure { store.open(ALICE, revoked.id, DEVICE, NOW + 2) }
        assertEquals(listOf("bob"), store.conversations(BOB).map(ChatConversation::id))
    }

    @Test
    fun `cache survives store recreation and explicit purge removes only the signed out account`() {
        val vault = InMemoryChatCacheVault()
        ChatCacheStore(vault).run {
            val conversation = conversation()
            saveAccess(ALICE, conversation, "wrapped", DEVICE, NOW)
            saveMessages(ALICE, conversation.id, messages(3), NOW)
        }

        val restored = ChatCacheStore(vault)
        assertEquals(3, restored.open(ALICE, "chat", DEVICE, NOW).messages.size)
        restored.purge(ALICE)
        assertTrue(restored.conversations(ALICE).isEmpty())
    }

    @Test
    fun `corrupt access records do not expose cached payloads`() {
        val vault = InMemoryChatCacheVault()
        val store = ChatCacheStore(vault)
        store.saveAccess(ALICE, conversation(), "wrapped", DEVICE, NOW)
        vault.update { root ->
            root.getJSONObject("accounts").getJSONObject(ALICE)
                .getJSONObject("conversations").getJSONObject("chat")
                .put("wrappedRoomKey", JSONObject.NULL)
        }

        expectCacheFailure { store.open(ALICE, "chat", DEVICE, NOW) }
    }

    @Test
    fun `size pressure repeatedly evicts ciphertext before encrypted index metadata`() {
        val store = ChatCacheStore(InMemoryChatCacheVault(), maxAccountBytes = 1_600)
        repeat(40) { index ->
            val conversation = conversation("chat-$index")
            store.saveAccess(ALICE, conversation, "wrapped-$index", DEVICE, NOW + index)
            runCatching {
                store.saveMessages(
                    ALICE,
                    conversation.id,
                    listOf(message(index, "x".repeat(2_000))),
                    NOW + index,
                )
            }
        }

        assertTrue(store.conversations(ALICE).size < 40)
        assertTrue(store.conversations(ALICE).isNotEmpty())
    }

    private fun expectCacheFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected cached access to fail securely")
        } catch (_: ChatCacheException) {
        }
    }

    private fun conversation(id: String = "chat", participants: List<String> = listOf(ALICE, BOB)) =
        ChatConversation(
            id = id,
            friendshipId = id,
            participantIds = participants,
            participantUsernames = participants.associateWith { it },
            updatedAt = NOW,
        )

    private fun messages(count: Int) = List(count) { message(it, "ciphertext-$it") }

    private fun message(index: Int, ciphertext: String) = EncryptedChatMessage(
        id = "message-$index",
        senderUid = ALICE,
        senderUsername = "alice",
        ciphertext = ciphertext,
        sentAt = index.toLong(),
        deleted = index % 17 == 0,
    )

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val DEVICE = "device"
        const val NOW = 1_000_000L
    }
}

private class InMemoryChatCacheVault : ChatCacheVault {
    private var root = JSONObject()

    override fun read() = JSONObject(root.toString())

    override fun update(change: (JSONObject) -> Unit) {
        val updated = JSONObject(root.toString())
        change(updated)
        root = updated
    }
}
