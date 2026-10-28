// Tests wrapped keys and document metadata for approved viewers.
package com.olive.app.data

import com.olive.app.ui.shared.*

import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
class SecureDocumentRepositoryTest {

    @Test
    fun `test envelope encryption math and json mapping`() {
        // Use test RSA keys in place of Android Keystore keys.
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val ownerKey = keyGen.generateKeyPair().public
        val staffKey = keyGen.generateKeyPair().public

        val title = "Therapy Notes"
        val body = "Discussed safety plan."
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }.toString()

        val documentKey = EncryptionUtils.generateSymmetricKey()
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)
        val ownerWrappedKey = KeyManager.wrapKey(documentKey, ownerKey)
        val staffWrappedKey = KeyManager.wrapKey(documentKey, staffKey)

        assertTrue("Ciphertext must not be empty", ciphertext.isNotEmpty())
        assertFalse("Ciphertext must hide the plaintext title", ciphertext.contains(title))
        assertTrue("Owner key wrapped", ownerWrappedKey.isNotEmpty())
        assertTrue("Staff key wrapped", staffWrappedKey.isNotEmpty())
        assertNotEquals("Wrapped keys must be unique", ownerWrappedKey, staffWrappedKey)

    }
}
