package com.olive.app.data

import com.olive.app.security.EncryptionUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentOcrMetadataTest {
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

    @Test
    fun incompleteOrInvalidEncryptedOcrFailsClosed() {
        assertNull(documentOcrMetadataFrom(null))
        assertNull(documentOcrMetadataFrom(JSONObject().put("ocrText", "missing fields")))
        assertNull(documentOcrMetadataFrom(JSONObject()
            .put("ocrText", "invalid confidence")
            .put("ocrConfidence", 1.2)
            .put("ocrSuccessful", true)))
    }
}
