package com.olive.app.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    /**
     * Generates a secure symmetric key.
     * Used for encrypting notes or generating the Group Chat Room Key.
     */
    fun generateSymmetricKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_SIZE, SecureRandom())
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a plaintext string and returns a Base64 encoded string.
     * Safe to store directly in Firestore NoSQL documents.
     */
    fun encryptText(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)

        // GCM requires a unique Initialization Vector (IV) for every single encryption
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend the IV to the ciphertext so we know how to decrypt it later
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts a Base64 encoded string back into readable plaintext.
     */
    fun decryptText(encryptedBase64: String, key: SecretKey): String {
        val combined = Base64.getDecoder().decode(encryptedBase64)

        // Extract the IV from the first 12 bytes
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
        val plaintextBytes = cipher.doFinal(ciphertext)

        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Encrypts binary data with AES-GCM.
     *
     * The returned payload contains the random IV followed by the authenticated ciphertext:
     * [12-byte IV][ciphertext and 16-byte authentication tag].
     */
    fun encryptBytes(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    /**
     * Decrypts and authenticates a payload produced by [encryptBytes].
     */
    fun decryptBytes(encryptedPayload: ByteArray, secretKey: SecretKey): ByteArray {
        val minimumPayloadLength = IV_LENGTH + TAG_LENGTH / Byte.SIZE_BITS
        require(encryptedPayload.size >= minimumPayloadLength) { "Encrypted payload is too short" }

        val iv = encryptedPayload.copyOfRange(0, IV_LENGTH)
        val ciphertext = encryptedPayload.copyOfRange(IV_LENGTH, encryptedPayload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Helper functions to convert the raw AES key into a string
     * so it can be securely wrapped/stored later.
     */
    fun encodeKeyToString(key: SecretKey): String {
        return Base64.getEncoder().encodeToString(key.encoded)
    }

    fun decodeStringToKey(base64Key: String): SecretKey {
        val decoded = Base64.getDecoder().decode(base64Key)
        return SecretKeySpec(decoded, ALGORITHM)
    }
}
