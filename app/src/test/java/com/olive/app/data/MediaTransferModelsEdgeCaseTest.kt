// Tests encrypted-upload persistence, legacy defaults, malformed records, and progress boundaries.
package com.olive.app.data

import com.olive.app.data.media.*
import org.junit.Assert.*
import org.junit.Test

class MediaTransferModelsEdgeCaseTest {
    private fun source() = PreparedEncryptedMedia(
        filePath = "/private/ciphertext.bin",
        ciphertextSize = 4096L,
        ciphertextSha256 = "abc123",
        encryptedMetadata = "encrypted metadata",
        wrappedKeys = mapOf("owner" to "wrapped owner", "trusted" to "wrapped trusted"),
        allowedUsers = listOf("owner", "trusted"),
        encryptionVersion = 2,
        kind = MediaKind.DOCUMENT,
        ocrReview = OcrReview.RETAKE_DECLINED,
    )

    // Inputs: the values shown below. Expected: durable upload job round trips every recovery field.
    @Test
    fun durableUploadJobRoundTripsEveryRecoveryField() {
        val original = MediaUploadJob(
            id = "job",
            ownerUid = "owner",
            source = source(),
            status = MediaTransferStatus.UPLOADING,
            mode = MediaUploadMode.MULTIPART,
            mediaId = "media",
            objectKey = "opaque/key",
            uploadId = "upload",
            partSize = 1024L,
            completedParts = mapOf(1 to "etag-one", 3 to "etag-three"),
            error = "temporary error",
            createdAt = 1234L,
        )

        assertEquals(original, MediaUploadJob.fromJson(original.toJson()))
    }

    // Inputs: the values shown below. Expected: older upload jobs receive safe encryption and ocr defaults.
    @Test
    fun olderUploadJobsReceiveSafeEncryptionAndOcrDefaults() {
        val json = MediaUploadJob(id = "job", ownerUid = "owner", source = source()).toJson()
        json.remove("encryptionVersion")
        json.remove("ocrReview")
        json.remove("completedParts")

        val restored = MediaUploadJob.fromJson(json)

        assertEquals(1, restored.source.encryptionVersion)
        assertEquals(OcrReview.NOT_RUN, restored.source.ocrReview)
        assertTrue(restored.completedParts.isEmpty())
    }

    // Inputs: the values shown below. Expected: upload progress is null for unknown totals and clamped for bad counters.
    @Test
    fun uploadProgressIsNullForUnknownTotalsAndClampedForBadCounters() {
        fun progress(transferred: Long, total: Long) = MediaUploadPresentation(
            jobId = "job",
            ownerUid = "owner",
            name = "Document",
            mimeType = "application/pdf",
            status = MediaTransferStatus.UPLOADING,
            bytesTransferred = transferred,
            totalBytes = total,
            mediaId = "",
            error = null,
            createdAt = 1L,
        ).progress

        assertNull(progress(0L, 0L))
        assertNull(progress(10L, -1L))
        assertEquals(0f, progress(-100L, 100L)!!, 0f)
        assertEquals(0.5f, progress(50L, 100L)!!, 0f)
        assertEquals(1f, progress(150L, 100L)!!, 0f)
    }

    // Inputs: the values shown below. Expected: malformed persisted enums and wrong field types fail closed.
    @Test
    fun malformedPersistedEnumsAndWrongFieldTypesFailClosed() {
        val badKind = MediaUploadJob(id = "job", ownerUid = "owner", source = source()).toJson()
            .put("kind", "EXECUTABLE")
        assertThrows(IllegalArgumentException::class.java) { MediaUploadJob.fromJson(badKind) }

        val badKeys = MediaUploadJob(id = "job", ownerUid = "owner", source = source()).toJson()
            .put("wrappedKeys", "not an object")
        assertThrows(Exception::class.java) { MediaUploadJob.fromJson(badKeys) }
    }
}
