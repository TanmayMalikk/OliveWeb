// Tests that OCR text stays in encrypted document metadata for safe-app users.
package com.olive.app.data

import com.olive.app.security.EncryptionUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentOcrMetadataTest {
    // Inputs: the values shown below. Expected: encrypted metadata restores ocr only after client decryption.
    @Test
    fun encryptedMetadataRestoresOcrOnlyAfterClientDecryption() {
        val key = EncryptionUtils.generateSymmetricKey()
        val encrypted = EncryptionUtils.encryptText(
            JSONObject()
                .put("ocrText", "private recognized text")
                .put("ocrConfidence", 0.82)
                .put("ocrSuccessful", true)
                .toString(),
            key,
        )

        val metadata = documentOcrMetadataFrom(JSONObject(EncryptionUtils.decryptText(encrypted, key)))

        assertEquals("private recognized text", metadata?.text)
        assertEquals(0.82f, metadata?.confidence)
        assertEquals(true, metadata?.successful)
    }

    // Inputs: the values shown below. Expected: incomplete or invalid encrypted ocr fails closed.
    @Test
    fun incompleteOrInvalidEncryptedOcrFailsClosed() {
        assertNull(documentOcrMetadataFrom(null))
        assertNull(documentOcrMetadataFrom(JSONObject().put("ocrText", "missing fields")))
        assertNull(documentOcrMetadataFrom(JSONObject()
            .put("ocrText", "invalid confidence")
            .put("ocrConfidence", 1.2)
            .put("ocrSuccessful", true)))
    }

    // Inputs: the values shown below. Expected: wrong json types non finite confidence and oversized text fail closed.
    @Test
    fun wrongJsonTypesNonFiniteConfidenceAndOversizedTextFailClosed() {
        assertNull(documentOcrMetadataFrom(JSONObject()
            .put("ocrText", 123)
            .put("ocrConfidence", 0.8)
            .put("ocrSuccessful", true)))
        assertNull(documentOcrMetadataFrom(JSONObject()
            .put("ocrText", "text")
            .put("ocrConfidence", "0.8")
            .put("ocrSuccessful", true)))
        assertNull(documentOcrMetadataFrom(JSONObject()
            .put("ocrText", "x".repeat(50_001))
            .put("ocrConfidence", 0.8)
            .put("ocrSuccessful", true)))
    }
}
