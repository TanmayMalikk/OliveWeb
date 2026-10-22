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
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

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
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_SHA1)
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

    fun getOrCreatePublicKey(): PublicKey = getPublicKey() ?: generateUserKeyPair().public

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
    fun wrapKey(secretKey: SecretKey, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance(RSA_MODE)
        val spec = javax.crypto.spec.OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT
        )
        // Bypass WRAP_MODE entirely
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, spec)
        val wrappedBytes = cipher.doFinal(secretKey.encoded)
        return android.util.Base64.encodeToString(wrappedBytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Converts a Base64 string from Firestore back into an RSA PublicKey.
     */
    fun decodePublicKey(base64Key: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /**
     * Unwraps (Decrypts) the Base64 string back into a usable AES SecretKey.
     * Uses the user's secure hardware Private Key.
     */
    fun unwrapKey(wrappedKeyBase64: String): SecretKey {
        val decodedBytes = android.util.Base64.decode(wrappedKeyBase64, android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance(RSA_MODE)
        val privateKey = getPrivateKey() ?: throw Exception("Private key missing")
        val spec = javax.crypto.spec.OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT
        )
        // Bypass UNWRAP_MODE entirely and manually rebuild the AES key
        cipher.init(Cipher.DECRYPT_MODE, privateKey, spec)
        val decryptedBytes = cipher.doFinal(decodedBytes)
        return SecretKeySpec(decryptedBytes, "AES")
    }
}
