package com.olive.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageOutboxStoreTest {
    @Test
    fun `records remain isolated by account and purge removes only its owner`() {
        val store = MessageOutboxStore(InMemoryOutboxVault())
        store.write(message("alice-1", "alice"))
        store.write(message("bob-1", "bob"))

        assertEquals(listOf("alice-1"), store.all("alice").map(OutgoingMessage::id))
        assertEquals(listOf("bob-1"), store.all("bob").map(OutgoingMessage::id))

        store.purge("alice")
        assertTrue(store.all("alice").isEmpty())
        assertEquals("bob-1", store.get("bob-1")?.id)
    }

    @Test
    fun `status updates preserve ciphertext identity and attempts`() {
        val store = MessageOutboxStore(InMemoryOutboxVault())
        val queued = message("message", "alice")
        store.write(queued)

        val sending = store.update(queued.id) {
            it.copy(status = MessageDeliveryStatus.SENDING, attemptCount = it.attemptCount + 1)
        }
        val retrying = store.update(queued.id) {
            it.copy(status = MessageDeliveryStatus.FAILED_RETRYABLE, failureCode = "UNAVAILABLE")
        }

        assertEquals(queued.ciphertext, sending.ciphertext)
        assertEquals(1, retrying.attemptCount)
        assertEquals("UNAVAILABLE", retrying.failureCode)
    }

    @Test
    fun `corrupted records are skipped while valid records remain available`() {
        val vault = InMemoryOutboxVault()
        val store = MessageOutboxStore(vault)
        store.write(message("valid", "alice"))
        vault.update { root ->
            root.getJSONObject("accounts").getJSONObject("alice")
                .getJSONObject("messages").put("corrupt", JSONObject().put("id", "corrupt"))
        }

        assertEquals(listOf("valid"), store.all("alice").map(OutgoingMessage::id))
        assertNull(store.get("corrupt"))
    }

    @Test
    fun `large repeated queue remains ordered and observable`() = runBlocking {
        val store = MessageOutboxStore(InMemoryOutboxVault())
        repeat(150) { index -> store.write(message("id-$index", "alice", index.toLong())) }

        val snapshot = store.observe("alice").first()
        assertEquals(150, snapshot.size)
        assertEquals((0L until 150L).toList(), snapshot.map(OutgoingMessage::clientSentAt))
        assertEquals((1L..150L).toList(), snapshot.map(OutgoingMessage::clientSequence))
    }

    @Test
    fun `sequences increase independently for each conversation`() {
        val store = MessageOutboxStore(InMemoryOutboxVault())
        val firstA = store.write(message("a-1", "alice", conversation = "a"))
        val firstB = store.write(message("b-1", "alice", conversation = "b"))
        val secondA = store.write(message("a-2", "alice", conversation = "a"))

        assertEquals(1L, firstA.clientSequence)
        assertEquals(1L, firstB.clientSequence)
        assertEquals(2L, secondA.clientSequence)
    }

    @Test
    fun `legacy records receive deterministic sequences before a new message`() {
        val vault = InMemoryOutboxVault()
        vault.update { root ->
            val messages = JSONObject()
                .put("later", message("later", "alice", 20L).toJson().apply { remove("clientSequence") })
                .put("earlier", message("earlier", "alice", 10L).toJson().apply { remove("clientSequence") })
            root.put(
                "accounts",
                JSONObject().put(
                    "alice",
                    JSONObject().put("messages", messages),
                ),
            )
        }
        val store = MessageOutboxStore(vault)

        val newest = store.write(message("new", "alice", 30L))

        assertEquals(
            listOf("earlier" to 1L, "later" to 2L, "new" to 3L),
            store.all("alice").map { it.id to it.clientSequence },
        )
        assertEquals(3L, newest.clientSequence)
    }

    private fun message(
        id: String,
        owner: String,
        sentAt: Long = 1L,
        conversation: String = "conversation",
    ) = OutgoingMessage(
        id = id,
        ownerUid = owner,
        conversationId = conversation,
        senderUsername = owner,
        ciphertext = "encrypted-$id",
        clientSentAt = sentAt,
    )
}

private class InMemoryOutboxVault : MessageOutboxVault {
    private var root = JSONObject()

    override fun read() = JSONObject(root.toString())

    override fun update(change: (JSONObject) -> Unit) {
        val updated = JSONObject(root.toString())
        change(updated)
        root = updated
    }
}
