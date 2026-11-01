// All signed-in roles: stores the key tied to the device lock.
package com.olive.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// A separate authentication-bound key guards device-lock entry. Cover storage stays usable while locked.
internal class DeviceLockKey(private val namespace: String) {
    // Device lock key: Prepares the feature data.
    fun prepare(uid: String, saved: JSONObject?): DeviceLockChallenge {
        val keyId = saved?.getString("keyId") ?: UUID.randomUUID().toString()
        require(keyId.matches(Regex("[a-f0-9-]{36}")))
        val alias = "olive.safe-entry.$namespace." + MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) } + ".$keyId"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keys.getKey(alias, null) as? SecretKey) ?: run {
            // Missing keys for an existing enrollment must never silently create a replacement.
            check(saved == null) { "Device lock key unavailable; sign in again to enroll this device." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(true)
                // Only continue with prepare when the Android version is at least 30.
                if (Build.VERSION.SDK_INT >= 30) {
                    spec.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    configureLegacyAuthentication(spec)
                }
                init(spec.build())
                generateKey()
            }
        }
        return try {
            DeviceLockChallenge(uid, key, saved?.let { JSONObject(it.toString()) }, keyId, alias)
        } catch (error: Exception) {
            if (saved == null) keys.deleteEntry(alias)
            throw error
        }
    }

    // Device lock key: returns the configure legacy authentication.
    @Suppress("DEPRECATION")
    private fun configureLegacyAuthentication(spec: KeyGenParameterSpec.Builder) {
        // Android 9/10 require this API; setUserAuthenticationParameters starts at Android 11.
        spec.setUserAuthenticationValidityDurationSeconds(10)
    }
}

class DeviceLockChallenge internal constructor(
    private val uid: String, private val key: SecretKey, private val saved: JSONObject?,
    private val keyId: String, private val alias: String,
) {
    private var committed = false
    // Device lock key: returns the mark enrolled.
    internal fun markEnrolled() {
        committed = true
    }

    // Closes the active feature for device lock key. It uses the account key so private data stays locked.
    internal fun close() {
        // close checks whether enrolling and committed is false before continuing.
        if (enrolling && !committed) runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    val enrolling get() = saved == null
    private val expected get() = "olive-safe-entry-v1:$uid".toByteArray(Charsets.UTF_8)

    // API 30+ binds this exact operation to BiometricPrompt. Legacy devices initialize only after credentials succeed.
    internal val promptCipher: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher() else null
    private var credentialCipher: Cipher? = null
    // returns the legacy cipher for device lock key.
    internal fun legacyCipher(): Cipher = newCipher().also { credentialCipher = it }
    // Device lock key: returns the new cipher.
    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (saved == null) init(Cipher.ENCRYPT_MODE, key)
        else init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(saved.getString("iv"), Base64.NO_WRAP))
        )
    }

    // returns the finish for device lock key.
    internal fun finish(cipher: Cipher): JSONObject? {
        check(cipher === (promptCipher ?: credentialCipher))
        if (saved == null) {
            val encrypted = cipher.doFinal(expected)
            return JSONObject().put("keyId", keyId)
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        val clear = cipher.doFinal(Base64.decode(saved.getString("data"), Base64.NO_WRAP))
        try {
            check(MessageDigest.isEqual(expected, clear))
        } finally {
            clear.fill(0)
        }
        return null
    }
}
