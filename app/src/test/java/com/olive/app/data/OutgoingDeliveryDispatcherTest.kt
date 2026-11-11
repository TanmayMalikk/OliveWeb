package com.olive.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
            resolve = { calls += "resolve"; "unexpected" },
            attachment = { message, mediaId -> calls += "${message.id}:$mediaId" },
        )

        dispatcher.deliver(message("attachment").copy(
            attachment = OutgoingAttachment(mediaId = "published-media"),
        ))

        assertEquals(listOf("attachment:published-media"), calls)
    }

    @Test
    fun `pending upload resolves to published media before delivery`() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(
            resolve = { uploadId -> calls += "resolve:$uploadId"; "published-media" },
            attachment = { _, mediaId -> calls += "deliver:$mediaId" },
        )

        dispatcher.deliver(message("attachment").copy(
            attachment = OutgoingAttachment(uploadJobId = "upload-job"),
        ))

        assertEquals(listOf("resolve:upload-job", "deliver:published-media"), calls)
    }

    @Test
    fun `unfinished upload remains a retryable queue barrier`() {
        val dispatcher = dispatcher(resolve = { null })

        assertThrows(AttachmentDependencyPending::class.java) {
            runBlocking {
                dispatcher.deliver(message("attachment").copy(
                    attachment = OutgoingAttachment(uploadJobId = "upload-job"),
                ))
            }
        }
    }

    private fun dispatcher(
        text: suspend (OutgoingMessage) -> Unit = {},
        resolve: suspend (String) -> String? = { error("Upload resolution was not expected") },
        attachment: suspend (OutgoingMessage, String) -> Unit = { _, _ -> },
    ) = OutgoingDeliveryDispatcher(text, resolve, attachment)

    private fun message(id: String) = OutgoingMessage(
        id = id,
        ownerUid = "owner",
        conversationId = "conversation",
        senderUsername = "owner",
        ciphertext = "ciphertext-$id",
        clientSentAt = 1,
    )
}
