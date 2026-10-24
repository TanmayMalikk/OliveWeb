// Contains the encryption utils code for this part of Olive.
package com.olive.app.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Groups the data and actions for encryption utils.
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
