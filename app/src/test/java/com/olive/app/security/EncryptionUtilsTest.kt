// Tests the encryption utils test behavior.
package com.olive.app.security

import com.olive.app.ui.shared.*

import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `test binary encryption and decryption`() {
        val originalBytes = byteArrayOf(
            0x00,
            0x01,
            0x7F,
            0x80.toByte(),
            0xFF.toByte(),
        )
        val secretKey = EncryptionUtils.generateSymmetricKey()

        val encryptedBytes = EncryptionUtils.encryptBytes(originalBytes, secretKey)
        val decryptedBytes = EncryptionUtils.decryptBytes(encryptedBytes, secretKey)

        assertArrayEquals(originalBytes, decryptedBytes)
    }

    @Test
    fun `test binary encryption uses a unique IV`() {
        val originalBytes = "private media".toByteArray()
        val secretKey = EncryptionUtils.generateSymmetricKey()

        val firstPayload = EncryptionUtils.encryptBytes(originalBytes, secretKey)
        val secondPayload = EncryptionUtils.encryptBytes(originalBytes, secretKey)

        assertNotEquals(firstPayload.toList(), secondPayload.toList())
    }

    @Test
    fun `test tampered binary payload fails authentication`() {
        val secretKey = EncryptionUtils.generateSymmetricKey()
        val encryptedBytes = EncryptionUtils.encryptBytes(byteArrayOf(1, 2, 3), secretKey)
        encryptedBytes[encryptedBytes.lastIndex] =
            (encryptedBytes.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            EncryptionUtils.decryptBytes(encryptedBytes, secretKey)
        }
    }

    @Test
    fun `test truncated binary payload is rejected`() {
        val secretKey = EncryptionUtils.generateSymmetricKey()

        assertThrows(IllegalArgumentException::class.java) {
            EncryptionUtils.decryptBytes(ByteArray(27), secretKey)
        }
    }

    @Test
    fun `test empty binary payload round trip`() {
        val secretKey = EncryptionUtils.generateSymmetricKey()
        val encryptedBytes = EncryptionUtils.encryptBytes(ByteArray(0), secretKey)

        assertArrayEquals(
            ByteArray(0),
            EncryptionUtils.decryptBytes(encryptedBytes, secretKey),
        )
    }
}
