package com.olive.app.data

import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutgoingDeliveryDispatcherTest {
    @Test
    fun `ordinary ciphertext keeps the existing delivery path`() = runBlocking {
        val delivered = mutableListOf<String>()
        val dispatcher = dispatcher(
            text = { delivered += "text:${it.id}" },
            attachment = { message, mediaId -> delivered += "media:${message.id}:$mediaId" },
        )

        dispatcher.deliver(message("text"))

        assertEquals(listOf("text:text"), delivered)
    }

    @Test
    fun `published media bypasses upload resolution`() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(
            attachment = { message, mediaId -> calls += "${message.id}:$mediaId" },
        )

        dispatcher.deliver(message("attachment").copy(
            attachment = OutgoingAttachment(mediaId = "published-media"),
        ))

        assertEquals(listOf("attachment:published-media"), calls)
    }

    @Test
    fun `rejected published media has a permanent retry classification`() {
        assertEquals(
            MessageDeliveryFailure("ATTACHMENT_NOT_FOUND", permanent = true),
            messageDeliveryFailure(AttachmentDeliveryRejected("ATTACHMENT_NOT_FOUND")),
        )
    }

    @Test
    fun `revoked attachment access is permanent and does not wait for connectivity`() {
        assertEquals(
            MessageDeliveryFailure("ATTACHMENT_ACCESS_REVOKED", permanent = true),
            messageDeliveryFailure(MediaAccessException(MediaAccessError.ACCESS_REVOKED)),
        )
    }

    @Test
    fun `temporary attachment network loss remains retryable`() {
        assertEquals(
            MessageDeliveryFailure("ATTACHMENT_NETWORK_REQUIRED", permanent = false),
            messageDeliveryFailure(MediaAccessException(MediaAccessError.NETWORK_REQUIRED)),
        )
    }

    @Test
    fun `deterministic message conflict is permanent`() {
        assertEquals(
            MessageDeliveryFailure("IDEMPOTENCY_CONFLICT", permanent = true),
            messageDeliveryFailure(MessageDeliveryRejected("IDEMPOTENCY_CONFLICT")),
        )
    }

    @Test
    fun `delivery diagnostic excludes encrypted payload`() {
        val message = message("message-1")
        val line = messageDeliveryLogLine(
            messageId = message.id,
            failure = MessageDeliveryFailure("UNAVAILABLE", permanent = false),
            cause = TemporaryDeliveryFailure("ciphertext-message-1"),
            permanent = false,
        )

        assertEquals(
            "messageId=message-1 code=UNAVAILABLE permanent=false exception=TemporaryDeliveryFailure",
            line,
        )
        assertFalse(line.contains(message.ciphertext))
    }

    private fun dispatcher(
        text: suspend (OutgoingMessage) -> Unit = {},
        attachment: suspend (OutgoingMessage, String) -> Unit = { _, _ -> },
    ) = OutgoingDeliveryDispatcher(text, attachment)

    private fun message(id: String) = OutgoingMessage(
        id = id,
        ownerUid = "owner",
        conversationId = "conversation",
        senderUsername = "owner",
        ciphertext = "ciphertext-$id",
        clientSentAt = 1,
    )

    private class TemporaryDeliveryFailure(message: String) : RuntimeException(message)

}
