package com.olive.app.data

import com.olive.app.data.messaging.MessageDeliveryFailure
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.data.messaging.MessageDrainOutcome
import com.olive.app.data.messaging.MessageOutboxStore
import com.olive.app.data.messaging.MessageOutboxVault
import com.olive.app.data.messaging.MessageQueueDrainer
import com.olive.app.data.messaging.OutgoingMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueDrainerTest {
    @Test
    fun `batch drains strictly by persisted sequence`() = runBlocking {
        val store = storeWithMessages(50)
        val delivered = mutableListOf<Long>()
        val outcome = drainer(store) { delivered += it.clientSequence }
            .drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.COMPLETE, outcome)
        assertEquals((1L..50L).toList(), delivered)
        assertTrue(store.all(OWNER).all { it.status == MessageDeliveryStatus.SENT })
    }

    @Test
    fun `temporary failure blocks later messages until retry`() = runBlocking {
        val store = storeWithMessages(5)
        val attempts = mutableListOf<Long>()
        var failedOnce = false
        val first = drainer(store) { message ->
            attempts += message.clientSequence
            if (message.clientSequence == 3L && !failedOnce) {
                failedOnce = true
                throw TemporaryDeliveryFailure()
            }
        }.drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.RETRY, first)
        assertEquals(listOf(1L, 2L, 3L), attempts)
        assertEquals(MessageDeliveryStatus.FAILED_RETRYABLE, store.head(OWNER, CONVERSATION)?.status)
        assertEquals(
            listOf(MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.QUEUED),
            store.all(OWNER).filter { it.clientSequence > 3L }.map(OutgoingMessage::status),
        )

        val second = drainer(store) { attempts += it.clientSequence }
            .drain(OWNER, CONVERSATION)
        assertEquals(MessageDrainOutcome.COMPLETE, second)
        assertEquals(listOf(1L, 2L, 3L, 3L, 4L, 5L), attempts)
    }

    @Test
    fun `permanent failure remains a hard barrier until manual retry`() = runBlocking {
        val store = storeWithMessages(4)
        val attempts = mutableListOf<Long>()
        val first = drainer(store) { message ->
            attempts += message.clientSequence
            if (message.clientSequence == 2L) throw PermanentDeliveryFailure()
        }.drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.BLOCKED, first)
        assertEquals(listOf(1L, 2L), attempts)
        assertEquals(
            MessageDrainOutcome.BLOCKED,
            drainer(store) { attempts += it.clientSequence }.drain(OWNER, CONVERSATION),
        )
        assertEquals(listOf(1L, 2L), attempts)

        val head = checkNotNull(store.head(OWNER, CONVERSATION))
        store.update(head.id) {
            it.copy(
                status = MessageDeliveryStatus.QUEUED,
                attemptCount = 0,
                failureCode = null,
            )
        }
        assertEquals(
            MessageDrainOutcome.COMPLETE,
            drainer(store) { attempts += it.clientSequence }.drain(OWNER, CONVERSATION),
        )
        assertEquals(listOf(1L, 2L, 2L, 3L, 4L), attempts)
    }

    @Test
    fun `restart retries sending head and deterministic id prevents duplicate delivery`() = runBlocking {
        val store = storeWithMessages(3)
        val head = checkNotNull(store.head(OWNER, CONVERSATION))
        store.update(head.id) {
            it.copy(status = MessageDeliveryStatus.SENDING, attemptCount = 1)
        }
        val remoteIds = linkedSetOf(head.id)
        val successfulOrder = mutableListOf<Long>()

        val outcome = drainer(store) { message ->
            if (remoteIds.add(message.id)) successfulOrder += message.clientSequence
        }.drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.COMPLETE, outcome)
        assertEquals(listOf(2L, 3L), successfulOrder)
        assertEquals(3, remoteIds.size)
    }

    @Test
    fun `message added during delivery joins the same sequential drain`() = runBlocking {
        val store = storeWithMessages(1)
        val delivered = mutableListOf<Long>()
        val outcome = drainer(store) { message ->
            delivered += message.clientSequence
            if (message.clientSequence == 1L) store.write(message("late"))
        }.drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.COMPLETE, outcome)
        assertEquals(listOf(1L, 2L), delivered)
    }

    @Test
    fun `unavailable account retries the head without attempting later messages`() = runBlocking {
        val store = storeWithMessages(3)
        val attempted = mutableListOf<Long>()
        val outcome = MessageQueueDrainer(
            store = store,
            activeOwnerUid = { "another-account" },
            deliver = { attempted += it.clientSequence },
            classifyFailure = { MessageDeliveryFailure("TEMPORARY", permanent = false) },
        ).drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.RETRY, outcome)
        assertTrue(attempted.isEmpty())
        assertEquals(MessageDeliveryStatus.FAILED_RETRYABLE, store.head(OWNER, CONVERSATION)?.status)
        assertEquals(0, store.head(OWNER, CONVERSATION)?.attemptCount)
    }

    @Test
    fun `persisted attempt limit converts repeated temporary failure into barrier`() = runBlocking {
        val store = storeWithMessages(2)
        repeat(5) {
            assertEquals(
                MessageDrainOutcome.RETRY,
                drainer(store) { throw TemporaryDeliveryFailure() }.drain(OWNER, CONVERSATION),
            )
        }

        assertEquals(
            MessageDrainOutcome.BLOCKED,
            drainer(store) { throw TemporaryDeliveryFailure() }.drain(OWNER, CONVERSATION),
        )
        val head = checkNotNull(store.head(OWNER, CONVERSATION))
        assertEquals(6, head.attemptCount)
        assertEquals(MessageDeliveryStatus.FAILED_PERMANENT, head.status)
        assertEquals(MessageDeliveryStatus.QUEUED, store.all(OWNER).last().status)
    }

    @Test
    fun `failure reporter receives the classified permanent outcome`() = runBlocking {
        val store = storeWithMessages(1)
        val reports = mutableListOf<String>()
        val outcome = MessageQueueDrainer(
            store = store,
            activeOwnerUid = { OWNER },
            deliver = { throw PermanentDeliveryFailure() },
            classifyFailure = { MessageDeliveryFailure("IDEMPOTENCY_CONFLICT", permanent = true) },
            reportFailure = { message, failure, cause, permanent ->
                reports += "${message.id}:${failure.code}:${cause::class.java.simpleName}:$permanent"
            },
        ).drain(OWNER, CONVERSATION)

        assertEquals(MessageDrainOutcome.BLOCKED, outcome)
        assertEquals(
            listOf("message-0:IDEMPOTENCY_CONFLICT:PermanentDeliveryFailure:true"),
            reports,
        )
    }

    @Test
    fun `conversation drains are independent while each preserves its own sequence`() = runBlocking {
        val store = MessageOutboxStore(DrainTestVault())
        repeat(4) { index ->
            store.write(message("a-$index", "conversation-a"))
            store.write(message("b-$index", "conversation-b"))
        }
        val delivered = mutableListOf<Pair<String, Long>>()

        assertEquals(
            MessageDrainOutcome.COMPLETE,
            drainer(store) { delivered += it.conversationId to it.clientSequence }
                .drain(OWNER, "conversation-a"),
        )
        assertTrue(store.all(OWNER).filter { it.conversationId == "conversation-b" }
            .all { it.status == MessageDeliveryStatus.QUEUED })
        assertEquals(
            MessageDrainOutcome.COMPLETE,
            drainer(store) { delivered += it.conversationId to it.clientSequence }
                .drain(OWNER, "conversation-b"),
        )
        assertEquals(
            (1L..4L).map { "conversation-a" to it } +
                    (1L..4L).map { "conversation-b" to it },
            delivered,
        )
    }

    @Test
    fun `repeated stress batches preserve order across injected failures`() = runBlocking {
        repeat(100) { iteration ->
            val store = storeWithMessages(75)
            val failAt = (iteration * 17 % 75 + 1).toLong()
            var failed = false
            val delivered = mutableListOf<Long>()
            val attempt: suspend (OutgoingMessage) -> Unit = { message ->
                if (message.clientSequence == failAt && !failed) {
                    failed = true
                    throw TemporaryDeliveryFailure()
                }
                delivered += message.clientSequence
            }

            assertEquals(
                MessageDrainOutcome.RETRY,
                drainer(store, attempt).drain(OWNER, CONVERSATION),
            )
            assertEquals(
                MessageDrainOutcome.COMPLETE,
                drainer(store, attempt).drain(OWNER, CONVERSATION),
            )
            assertEquals((1L..75L).toList(), delivered)
        }
    }

    private fun storeWithMessages(count: Int): MessageOutboxStore =
        MessageOutboxStore(DrainTestVault()).also { store ->
            repeat(count) { index -> store.write(message("message-$index")) }
        }

    private fun message(id: String, conversationId: String = CONVERSATION) = OutgoingMessage(
        id = id,
        ownerUid = OWNER,
        conversationId = conversationId,
        senderUsername = "alice",
        ciphertext = "ciphertext-$id",
        clientSentAt = 1L,
    )

    private fun drainer(
        store: MessageOutboxStore,
        deliver: suspend (OutgoingMessage) -> Unit,
    ) = MessageQueueDrainer(
        store = store,
        activeOwnerUid = { OWNER },
        deliver = deliver,
        classifyFailure = { failure ->
            MessageDeliveryFailure(
                code = if (failure is PermanentDeliveryFailure) "PERMANENT" else "TEMPORARY",
                permanent = failure is PermanentDeliveryFailure,
            )
        },
    )

    private class TemporaryDeliveryFailure : RuntimeException()
    private class PermanentDeliveryFailure : RuntimeException()

    private companion object {
        const val OWNER = "alice"
        const val CONVERSATION = "conversation"
    }
}

private class DrainTestVault : MessageOutboxVault {
    private var root = JSONObject()

    @Synchronized
    override fun read() = JSONObject(root.toString())

    @Synchronized
    override fun update(change: (JSONObject) -> Unit) {
        val updated = JSONObject(root.toString())
        change(updated)
        root = updated
    }
}
