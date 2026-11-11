package com.olive.app.data

import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadJob
import com.olive.app.data.media.OcrReview
import com.olive.app.data.media.PreparedEncryptedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentUploadResolutionTest {
    @Test
    fun `every in-progress upload state remains a queue barrier`() {
        listOf(
            MediaTransferStatus.QUEUED,
            MediaTransferStatus.CREATING_SESSION,
            MediaTransferStatus.UPLOADING,
            MediaTransferStatus.FINALIZING,
        ).forEach { status ->
            assertNull(resolveAttachmentUpload("owner", job(status = status)))
        }
    }

    @Test
    fun `completed upload returns only its published media id`() {
        assertEquals(
            "published-media",
            resolveAttachmentUpload(
                "owner",
                job(status = MediaTransferStatus.COMPLETE, mediaId = "published-media"),
            ),
        )
        assertFailure("ATTACHMENT_NOT_FOUND") {
            resolveAttachmentUpload("owner", job(status = MediaTransferStatus.COMPLETE))
        }
    }

    @Test
    fun `missing failed cancelled and cross-account uploads fail permanently`() {
        assertFailure("ATTACHMENT_NOT_FOUND") { resolveAttachmentUpload("owner", null) }
        listOf(MediaTransferStatus.FAILED, MediaTransferStatus.CANCELLED).forEach { status ->
            assertFailure("ATTACHMENT_UPLOAD_FAILED") {
                resolveAttachmentUpload("owner", job(status = status))
            }
        }
        assertFailure("ACCOUNT_MISMATCH") {
            resolveAttachmentUpload("another-owner", job(status = MediaTransferStatus.UPLOADING))
        }
    }

    private fun assertFailure(code: String, action: () -> Unit) {
        val failure = assertThrows(AttachmentDeliveryRejected::class.java, action)
        assertEquals(code, failure.failureCode)
    }

    private fun job(
        status: MediaTransferStatus,
        mediaId: String = "",
    ) = MediaUploadJob(
        id = "upload-job",
        ownerUid = "owner",
        source = PreparedEncryptedMedia(
            filePath = "ciphertext.olive",
            ciphertextSize = 64,
            ciphertextSha256 = "0".repeat(64),
            encryptedMetadata = "encrypted",
            wrappedKeys = mapOf("owner" to "wrapped"),
            allowedUsers = listOf("owner"),
            kind = MediaKind.DOCUMENT,
            ocrReview = OcrReview.NOT_RUN,
        ),
        status = status,
        mediaId = mediaId,
    )
}
