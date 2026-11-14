package com.olive.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Durable record for an ARK copy encrypted under one authentication-bound device key. */
internal data class AccountRecoveryKeyDeviceRecord(
    val accountId: String,
    val protectionId: String,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    init {
        require(validAccountId(accountId)) { "Invalid account ID" }
        require(runCatching { UUID.fromString(protectionId) }.isSuccess) { "Invalid device protection ID" }
        require(decode(ivBase64).size == IV_BYTES) { "Invalid device protection IV" }
        require(decode(ciphertextBase64).size > TAG_BYTES) { "Invalid protected recovery key" }
    }

    internal fun toJson() = JSONObject().apply {
        put("version", VERSION)
        put("accountId", accountId)
        put("protectionId", protectionId)
        put("iv", ivBase64)
        put("ciphertext", ciphertextBase64)
    }

    internal fun aad() = "$VERSION|$accountId|$protectionId".toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

        internal fun fromJson(value: JSONObject): AccountRecoveryKeyDeviceRecord {
            require(value.getInt("version") == VERSION) { "Unsupported device protection record" }
            return AccountRecoveryKeyDeviceRecord(
                value.getString("accountId"), value.getString("protectionId"),
                value.getString("iv"), value.getString("ciphertext"),
            )
        }

        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        private fun decode(value: String) = java.util.Base64.getDecoder().decode(value)
    }
}

/**
 * Android Keystore Device Protection Key boundary for a local ARK copy.
 *
 * The caller must bind [AccountRecoveryKeyDeviceChallenge.promptCipher] to a biometric/device
 * credential prompt before calling [AccountRecoveryKeyDeviceChallenge.enroll] or [unlock].
 */
internal class AccountRecoveryKeyDeviceProtector {
    fun prepare(
        accountId: String,
        saved: AccountRecoveryKeyDeviceRecord?,
    ): AccountRecoveryKeyDeviceChallenge {
        require(saved == null || saved.accountId == accountId) { "Account recovery record mismatch" }
        val protectionId = saved?.protectionId ?: UUID.randomUUID().toString()
        val alias = alias(accountId, protectionId)
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: run {
            check(saved == null) { "Device protection key is unavailable; recover this account again." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(spec(alias))
                generateKey()
            }
        }
        return try {
            AccountRecoveryKeyDeviceChallenge(accountId, key, saved, protectionId, alias)
        } catch (failure: Exception) {
            if (saved == null) runCatching { store.deleteEntry(alias) }
            throw failure
        }
    }

    /** Removes a device key after its encrypted ARK record has been purged. */
    fun forget(record: AccountRecoveryKeyDeviceRecord) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(alias(record.accountId, record.protectionId))
            }
        }
    }

    private fun alias(accountId: String, protectionId: String): String =
        "olive.account.ark.v1." + MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } +
            "." + protectionId

    private fun spec(alias: String) = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(true)
        .apply {
            if (Build.VERSION.SDK_INT >= 30) {
                setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(10)
            }
        }.build()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** One short-lived DPK operation; unfinished enrollment deletes its staged Keystore key. */
internal class AccountRecoveryKeyDeviceChallenge internal constructor(
    private val accountId: String,
    private val key: SecretKey,
    private val saved: AccountRecoveryKeyDeviceRecord?,
    private val protectionId: String,
    private val alias: String,
) {
    private var committed = false
    internal val enrolling get() = saved == null
    internal val promptCipher: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher() else null
    private var legacyCipher: Cipher? = null

    internal fun legacyCipher(): Cipher = newCipher().also { legacyCipher = it }

    internal fun enroll(cipher: Cipher, recoveryKey: AccountRecoveryKey): AccountRecoveryKeyDeviceRecord {
        check(enrolling && cipher === (promptCipher ?: legacyCipher))
        val raw = recoveryKey.encodedCopy()
        return try {
            cipher.updateAAD("1|$accountId|$protectionId".toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(raw)
            AccountRecoveryKeyDeviceRecord(
                accountId, protectionId,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            )
        } finally {
            raw.fill(0)
        }
    }

    internal fun unlock(cipher: Cipher): AccountRecoveryKey {
        val record = checkNotNull(saved)
        check(!enrolling && cipher === (promptCipher ?: legacyCipher))
        cipher.updateAAD(record.aad())
        val raw = cipher.doFinal(Base64.decode(record.ciphertextBase64, Base64.NO_WRAP))
        return try {
            AccountRecoveryKey.fromEncoded(raw)
        } finally {
            raw.fill(0)
        }
    }

    internal fun markStored() {
        committed = true
    }

    internal fun close() {
        if (enrolling && !committed) runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val record = saved
        if (record == null) init(Cipher.ENCRYPT_MODE, key)
        else init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(record.ivBase64, Base64.NO_WRAP)),
        )
    }
}
