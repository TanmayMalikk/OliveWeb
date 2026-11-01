// Tests encrypted-upload persistence, legacy defaults, malformed records, and progress boundaries.
package com.olive.app.data

import com.olive.app.data.media.*
import org.junit.Assert.*
import org.junit.Test

class MediaTransferModelsEdgeCaseTest {
    // returns the source for media transfer models edge case test kt. The test gives the code a clear input and checks the expected result.
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
        // returns the progress for media transfer models edge case test kt. The test gives the code a clear input and checks the expected result.
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

    // Inputs: every media kind and OCR review combination. Expected: durable storage preserves both enums exactly.
    @Test
    fun everyMediaKindAndOcrReviewRoundTrips() {
        MediaKind.entries.forEach { kind ->
            OcrReview.entries.forEach { review ->
                val original = MediaUploadJob(
                    id = "${kind.name}-${review.name}",
                    ownerUid = "owner",
                    source = source().copy(kind = kind, ocrReview = review),
                    createdAt = 1L,
                )
                assertEquals(original, MediaUploadJob.fromJson(original.toJson()))
            }
        }
    }

    // Inputs: every durable transfer status and optional mode. Expected: restart recovery restores the exact state.
    @Test
    fun everyTransferStatusAndModeRoundTrips() {
        MediaTransferStatus.entries.forEach { status ->
            listOf(null, MediaUploadMode.SINGLE, MediaUploadMode.MULTIPART).forEach { mode ->
                val original = MediaUploadJob(
                    id = "$status-$mode",
                    ownerUid = "owner",
                    source = source(),
                    status = status,
                    mode = mode,
                    createdAt = 2L,
                )
                assertEquals(original, MediaUploadJob.fromJson(original.toJson()))
            }
        }
    }

    // Inputs: invalid multipart part numbers. Expected: malformed recovery data cannot become an upload job.
    @Test
    fun malformedMultipartPartNumbersFailClosed() {
        val malformed = MediaUploadJob("job", "owner", source()).toJson()
            .put("completedParts", org.json.JSONObject().put("not-a-number", "etag"))
        assertThrows(NumberFormatException::class.java) { MediaUploadJob.fromJson(malformed) }
    }

    // Inputs: blank and populated errors. Expected: blank legacy values restore as null while real errors survive.
    @Test
    fun persistedErrorsDistinguishBlankFromRealFailures() {
        val blank = MediaUploadJob("job", "owner", source()).toJson().put("error", "")
        assertNull(MediaUploadJob.fromJson(blank).error)
        val failed = MediaUploadJob("job", "owner", source(), error = "Network unavailable", createdAt = 1L)
        assertEquals("Network unavailable", MediaUploadJob.fromJson(failed.toJson()).error)
    }

    // Inputs: transferred-byte counters near Long boundaries. Expected: progress remains bounded and finite.
    @Test
    fun progressHandlesLongBoundariesWithoutOverflow() {
        // returns the progress for media transfer models edge case test kt. The test gives the code a clear input and checks the expected result.
        fun progress(done: Long, total: Long) = MediaUploadPresentation(
            "job", "owner", "File", "application/octet-stream",
            MediaTransferStatus.UPLOADING, done, total, "", null, 1L,
        ).progress
        assertEquals(1f, progress(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(0f, progress(Long.MIN_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(1f, progress(Long.MAX_VALUE, 1L)!!, 0f)
    }

    // Inputs: wrong persisted status, mode, and OCR review names. Expected: unknown future or corrupted enums are rejected.
    @Test
    fun unknownPersistedTransferEnumsAreRejected() {
        val base = MediaUploadJob("job", "owner", source()).toJson()
        assertThrows(IllegalArgumentException::class.java) {
            MediaUploadJob.fromJson(org.json.JSONObject(base.toString()).put("status", "PAUSED_FOREVER"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaUploadJob.fromJson(org.json.JSONObject(base.toString()).put("mode", "STREAM"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaUploadJob.fromJson(org.json.JSONObject(base.toString()).put("ocrReview", "MAYBE"))
        }
    }
}
