package com.olive.app.data

import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.messaging.AttachmentDeliveryRejected
import com.olive.app.data.messaging.MessageDeliveryFailure
import com.olive.app.data.messaging.MessageDeliveryRejected
import com.olive.app.data.messaging.OutgoingAttachment
import com.olive.app.data.messaging.OutgoingDeliveryDispatcher
import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.messaging.isDurableChatAttachment
import com.olive.app.data.messaging.messageDeliveryFailure
import com.olive.app.data.messaging.messageDeliveryLogLine
import com.olive.app.data.messaging.outgoingRoomKey
import com.olive.app.data.messaging.attachmentKeyUnavailable
import com.olive.app.data.safety.SafetyDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.InvalidKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.SecretKeySpec

class OutgoingDeliveryDispatcherTest {
    @Test
    fun `active room key queues offline attachment without loading remote key`() = runBlocking {
        val active = key(1)
        var remoteLoads = 0

        val selected = outgoingRoomKey(active) {
            remoteLoads += 1
            key(2)
        }

        assertEquals(active, selected)
        assertEquals(0, remoteLoads)
    }

    @Test
    fun `missing active room key uses existing recovery path once`() = runBlocking {
        val recovered = key(2)
        var remoteLoads = 0

        val selected = outgoingRoomKey(null) {
            remoteLoads += 1
            recovered
        }

        assertEquals(recovered, selected)
        assertEquals(1, remoteLoads)
    }

    @Test
    fun `only owner published ciphertext qualifies for durable chat sharing`() {
        assertTrue(isDurableChatAttachment(document(objectKey = "r2/object"), "owner"))
        assertFalse(isDurableChatAttachment(document(objectKey = ""), "owner"))
        assertFalse(isDurableChatAttachment(document(objectKey = "r2/object"), "another-owner"))
        assertFalse(isDurableChatAttachment(document(objectKey = "r2/object"), null))
    }

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
    fun `attachment key failures are permanent and retain their cause`() {
        listOf(
            IllegalBlockSizeException("incompatible wrapped key"),
            BadPaddingException("wrong OAEP profile"),
            InvalidKeyException("device key unavailable"),
        ).forEach { cause ->
            val rejection = attachmentKeyUnavailable(cause)

            assertEquals(cause, rejection.cause)
            assertEquals(
                MessageDeliveryFailure("ATTACHMENT_KEY_UNAVAILABLE", permanent = true),
                messageDeliveryFailure(rejection),
            )
        }
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

    private fun document(objectKey: String) = SafetyDocument(
        id = "media-1",
        ownerUid = "owner",
        storagePath = "",
        name = "document.pdf",
        mimeType = "application/pdf",
        allowedUsers = listOf("owner"),
        objectKey = objectKey,
    )

    private fun key(value: Byte) = SecretKeySpec(ByteArray(32) { value }, "AES")

    private class TemporaryDeliveryFailure(message: String) : RuntimeException(message)

}
