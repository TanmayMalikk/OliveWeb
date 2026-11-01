// Tests text encryption, decryption, and invalid-key handling.
package com.olive.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptionUtilsTest {
    // Inputs: the values shown below. Expected: test AES text encryption and decryption.
    @Test
    fun `test AES text encryption and decryption`() {
        val originalText = "My safe word is pineapple."

        val secretKey = EncryptionUtils.generateSymmetricKey()

        val ciphertext = EncryptionUtils.encryptText(originalText, secretKey)

        val decryptedText = EncryptionUtils.decryptText(ciphertext, secretKey)

        assertEquals("The decrypted text should match the original", originalText, decryptedText)
        assertNotEquals(ciphertext, EncryptionUtils.encryptText(originalText, secretKey))
        val tampered = ciphertext.dropLast(2) + "AA"
        assertThrows(Exception::class.java) { EncryptionUtils.decryptText(tampered, secretKey) }
    }

    // Inputs: the values shown below. Expected: test binary encryption and decryption.
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

    // Inputs: the values shown below. Expected: test binary encryption uses a unique IV.
    @Test
    fun `test binary encryption uses a unique IV`() {
        val originalBytes = "private media".toByteArray()
        val secretKey = EncryptionUtils.generateSymmetricKey()

        val firstPayload = EncryptionUtils.encryptBytes(originalBytes, secretKey)
        val secondPayload = EncryptionUtils.encryptBytes(originalBytes, secretKey)

        assertNotEquals(firstPayload.toList(), secondPayload.toList())
    }

    // Inputs: the values shown below. Expected: test tampered binary payload fails authentication.
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

    // Inputs: the values shown below. Expected: test truncated binary payload is rejected.
    @Test
    fun `test truncated binary payload is rejected`() {
        val secretKey = EncryptionUtils.generateSymmetricKey()

        assertThrows(IllegalArgumentException::class.java) {
            EncryptionUtils.decryptBytes(ByteArray(27), secretKey)
        }
    }

    // Inputs: the values shown below. Expected: test empty binary payload round trip.
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
