package com.olive.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object KeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val RSA_MODE = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    // The alias used to locate the user's RSA keys securely inside the Android hardware
    private const val USER_KEY_ALIAS = "olive.user.rsa.v1"

    /**
     * Generates a persistent RSA Public/Private KeyPair in the Android Keystore.
     * This should be called once when the user first creates their account.
     */
    fun generateUserKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            USER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setKeySize(2048)
            .build()

        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Retrieves the user's Public Key to encrypt AES keys before sending to Firestore.
     */
    fun getPublicKey(): PublicKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val certificate = keyStore.getCertificate(USER_KEY_ALIAS)
        return certificate?.publicKey
    }

    /**
     * Retrieves the user's Private Key to decrypt AES keys pulled from Firestore.
     */
    private fun getPrivateKey(): PrivateKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(USER_KEY_ALIAS, null) as? PrivateKey
    }

    /**
     * Wraps (Encrypts) the AES SecretKey using an RSA Public Key.
     * Returns a Base64 string safe for Firestore.
     */
    fun wrapKey(aesKey: SecretKey, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance(RSA_MODE)
        cipher.init(Cipher.WRAP_MODE, publicKey)

        val wrappedBytes = cipher.wrap(aesKey)
        return Base64.getEncoder().encodeToString(wrappedBytes)
    }

    /**
     * Unwraps (Decrypts) the Base64 string back into a usable AES SecretKey.
     * Uses the user's secure hardware Private Key.
     */
    fun unwrapKey(wrappedBase64: String): SecretKey {
        val privateKey = getPrivateKey()
            ?: throw IllegalStateException("Private Key not found in Keystore. User must re-authenticate.")

        val wrappedBytes = Base64.getDecoder().decode(wrappedBase64)

        val cipher = Cipher.getInstance(RSA_MODE)
        cipher.init(Cipher.UNWRAP_MODE, privateKey)

        // Unwraps the bytes back into an AES SecretKey
        return cipher.unwrap(wrappedBytes, "AES", Cipher.SECRET_KEY) as SecretKey
    }
}

