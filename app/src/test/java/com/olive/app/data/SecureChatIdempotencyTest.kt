package com.olive.app.data

import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.messaging.existingMessageMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChatIdempotencyTest {
    @Test
    fun `exact persisted message is an idempotent retry`() {
        assertTrue(existingMessageMatches(OWNER, CIPHERTEXT, SENT_AT, outgoing()))
    }

    @Test
    fun `different sender cannot reuse deterministic message id`() {
        assertFalse(existingMessageMatches("another-owner", CIPHERTEXT, SENT_AT, outgoing()))
    }

    @Test
    fun `different ciphertext cannot reuse deterministic message id`() {
        assertFalse(existingMessageMatches(OWNER, "different-ciphertext", SENT_AT, outgoing()))
    }

    @Test
    fun `different timestamp cannot reuse deterministic message id`() {
        assertFalse(existingMessageMatches(OWNER, CIPHERTEXT, SENT_AT + 1, outgoing()))
    }

    @Test
    fun `missing persisted identity fields cannot pass idempotency check`() {
        assertFalse(existingMessageMatches(null, CIPHERTEXT, SENT_AT, outgoing()))
        assertFalse(existingMessageMatches(OWNER, null, SENT_AT, outgoing()))
        assertFalse(existingMessageMatches(OWNER, CIPHERTEXT, null, outgoing()))
    }

    private fun outgoing() = OutgoingMessage(
        id = "message-1",
        ownerUid = OWNER,
        conversationId = "conversation-1",
        senderUsername = "alice",
        ciphertext = CIPHERTEXT,
        clientSentAt = SENT_AT,
    )

    private companion object {
        const val OWNER = "owner-1"
        const val CIPHERTEXT = "ciphertext-1"
        const val SENT_AT = 123L
    }
}
