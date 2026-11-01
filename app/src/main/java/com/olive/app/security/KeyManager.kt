// All signed-in roles: creates and opens keys for encrypted media.
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
import java.security.GeneralSecurityException
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.MessageDigest
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object KeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val RSA_MODE = "RSA/ECB/OAEPPadding"
    const val CURRENT_KEY_WRAP_VERSION = 2

    // The alias used to locate the user's RSA keys securely inside the Android hardware
    private const val USER_KEY_ALIAS = "olive.user.rsa.v2"
    private const val LEGACY_KEY_ALIAS = "olive.user.rsa.v1"

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
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512,
                KeyProperties.DIGEST_SHA1
            )
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

    // Secure messaging: identifies this installation without exposing its private key.
    fun deviceId(publicKey: PublicKey = getOrCreatePublicKey()): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return android.util.Base64.encodeToString(
            digest.copyOfRange(0, 18),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    /**
     * Retrieves the user's Private Key to decrypt AES keys pulled from Firestore.
     */
    private fun getPrivateKeys(): List<PrivateKey> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return listOf(USER_KEY_ALIAS, LEGACY_KEY_ALIAS).mapNotNull { alias ->
            keyStore.getKey(alias, null) as? PrivateKey
        }
    }

    /**
     * Wraps (Encrypts) the AES SecretKey using an RSA Public Key.
     * Returns a Base64 string safe for Firestore.
     */
    fun wrapKey(secretKey: SecretKey, publicKey: PublicKey): String {
        return wrapKey(secretKey, publicKey, MGF1ParameterSpec.SHA1)
    }

    // Secure messaging: uses the mask digest declared by the receiving device.
    fun wrapKey(secretKey: SecretKey, publicKey: PublicKey, mgf1Digest: MGF1ParameterSpec): String {
        val cipher = Cipher.getInstance(RSA_MODE)
        val spec = oaepSpec(mgf1Digest)
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
    fun unwrapKey(wrappedKeyBase64: String, keyWrapVersion: Int? = null): SecretKey {
        val decodedBytes = android.util.Base64.decode(wrappedKeyBase64, android.util.Base64.NO_WRAP)
        val privateKeys = getPrivateKeys()
        if (privateKeys.isEmpty()) error("Private key missing")
        val candidates = when {
            keyWrapVersion != null && keyWrapVersion >= CURRENT_KEY_WRAP_VERSION ->
                listOf(MGF1ParameterSpec.SHA1)
            keyWrapVersion == 1 ->
                listOf(MGF1ParameterSpec.SHA256)
            else ->
                listOf(MGF1ParameterSpec.SHA1, MGF1ParameterSpec.SHA256)
        }
        var lastFailure: GeneralSecurityException? = null
        privateKeys.forEach { privateKey ->
            candidates.forEach { digest ->
                try {
                    val cipher = Cipher.getInstance(RSA_MODE)
                    // Bypass UNWRAP_MODE entirely and manually rebuild the AES key.
                    cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec(digest))
                    val decryptedBytes = cipher.doFinal(decodedBytes)
                    return SecretKeySpec(decryptedBytes, "AES")
                } catch (failure: GeneralSecurityException) {
                    lastFailure = failure
                }
            }
        }
        throw checkNotNull(lastFailure) { "No key-wrap algorithm was attempted" }
    }

    /**
     * Android Keystore uses SHA-1 as OAEP's MGF1 digest unless API 35+ keys explicitly opt into
     * another value. The OAEP content digest remains SHA-256; SHA-1 is used only by the mask
     * generation function for cross-device Keystore compatibility.
     */
    private fun oaepSpec(mgf1Digest: MGF1ParameterSpec) = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        mgf1Digest,
        PSource.PSpecified.DEFAULT
    )
}
