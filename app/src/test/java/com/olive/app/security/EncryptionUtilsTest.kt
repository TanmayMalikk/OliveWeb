// Tests the encryption utils test behavior.
package com.olive.app.security

import com.olive.app.ui.shared.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Groups the data and actions for encryption utils test.
class EncryptionUtilsTest {
    @Test
    // Runs the test that checks AES text encryption and decryption.
    fun `test AES text encryption and decryption`() {
        val originalText = "My safe word is pineapple."

        // 1. Generate Key
        val secretKey = EncryptionUtils.generateSymmetricKey()

        // 2. Encrypt
        val ciphertext = EncryptionUtils.encryptText(originalText, secretKey)

        // 3. Decrypt
        val decryptedText = EncryptionUtils.decryptText(ciphertext, secretKey)

        // 4. Verify
        assertEquals("The decrypted text should match the original", originalText, decryptedText)
        assertNotEquals(ciphertext, EncryptionUtils.encryptText(originalText, secretKey))
        val tampered = ciphertext.dropLast(2) + "AA"
        assertThrows(Exception::class.java) { EncryptionUtils.decryptText(tampered, secretKey) }
    }
}
