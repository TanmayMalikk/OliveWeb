package com.olive.app.data

import com.olive.app.security.EncryptionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMessageModelsTest {
    @Test
    fun `preparation encrypts normalized text without retaining plaintext`() {
        val key = EncryptionUtils.generateSymmetricKey()
        val outgoing = prepareOutgoingMessage(
            conversationId = "conversation",
            sender = AccountIdentity("owner", "Alice"),
            text = "  private\u0000 message\n  ",
            roomKey = key,
            id = "message-id",
            now = 42L,
        )

        assertEquals("message-id", outgoing.id)
        assertEquals("alice", outgoing.senderUsername)
        assertEquals("private message", EncryptionUtils.decryptText(outgoing.ciphertext, key))
        assertFalse(outgoing.toJson().toString().contains("private message"))
    }

    @Test
    fun `normalization accepts representative unicode multiline and repeated valid inputs`() {
        val samples = listOf(
            "hello",
            "مرحبا بالعالم",
            "你好，世界 👋",
            "first line\nsecond line",
            "column\tvalue",
            "a".repeat(2_000),
        )
        repeat(20) {
            samples.forEach { sample -> assertEquals(sample, sanitizedMessageBody("  $sample  ")) }
        }
    }

    @Test
    fun `normalization rejects blank control-only and oversized inputs`() {
        listOf("", "   ", "\u0000\u0007").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { sanitizedMessageBody(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            sanitizedMessageBody("x".repeat(2_001))
        }
    }

    @Test
    fun `json round trip covers every delivery status`() {
        MessageDeliveryStatus.entries.forEachIndexed { index, status ->
            val expected = outgoing("id-$index", status, index)
            assertEquals(expected, OutgoingMessage.fromJson(expected.toJson()))
        }
    }

    @Test
    fun `failure classification separates permanent and retryable firebase classes`() {
        val permanent = setOf(
            "INVALID_ARGUMENT",
            "PERMISSION_DENIED",
            "NOT_FOUND",
            "FAILED_PRECONDITION",
        )
        val retryable = setOf(
            "UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "ABORTED",
            "RESOURCE_EXHAUSTED",
            "UNAUTHENTICATED",
        )

        assertTrue(permanent.all(::isPermanentMessageFailure))
        assertTrue(retryable.none(::isPermanentMessageFailure))
    }

    private fun outgoing(id: String, status: MessageDeliveryStatus, attempts: Int) = OutgoingMessage(
        id = id,
        ownerUid = "owner",
        conversationId = "conversation",
        senderUsername = "alice",
        ciphertext = "ciphertext-$id",
        clientSentAt = attempts.toLong(),
        status = status,
        attemptCount = attempts,
        failureCode = if (status.name.startsWith("FAILED")) "TEST_FAILURE" else null,
    )
}
