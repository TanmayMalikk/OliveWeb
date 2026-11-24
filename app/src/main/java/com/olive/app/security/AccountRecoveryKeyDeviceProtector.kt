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

    /**
     * Serializes this device-protected ARK record into a [JSONObject].
     *
     * @return A JSON object containing the serialized fields.
     */
    internal fun toJson() = JSONObject().apply {
        put("version", VERSION)
        put("accountId", accountId)
        put("protectionId", protectionId)
        put("iv", ivBase64)
        put("ciphertext", ciphertextBase64)
    }

    /**
     * Returns the AES-GCM additional authenticated data binding version, account, and protection IDs.
     *
     * @return The UTF-8 bytes supplied as additional authenticated data for AES-GCM.
     */
    internal fun aad() = "$VERSION|$accountId|$protectionId".toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

        /**
         * Deserializes and validates an [AccountRecoveryKeyDeviceRecord] from a [JSONObject].
         *
         * @param value The serialized JSON record to parse.
         * @return The decoded device protection record.
         */
        internal fun fromJson(value: JSONObject): AccountRecoveryKeyDeviceRecord {
            require(value.getInt("version") == VERSION) { "Unsupported device protection record" }
            return AccountRecoveryKeyDeviceRecord(
                value.getString("accountId"), value.getString("protectionId"),
                value.getString("iv"), value.getString("ciphertext"),
            )
        }

        /**
         * Checks that [value] is a non-empty URL/path-safe account UID string.
         *
         * @param value The candidate account identifier to validate.
         * @return True when the identifier has 1–128 letters, digits, underscores, or hyphens.
         */
        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /**
         * Decodes a Base64 string into raw bytes.
         *
         * @param value The Base64 string to decode.
         * @return The decoded bytes.
         */
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
    /**
     * Prepares an Android Keystore key and cipher challenge for enrolling or unlocking a local ARK.
     *
     * @param accountId The account ID that owns the key or record.
     * @param saved The existing saved record, if one is available.
     * @return The prepared Keystore challenge used for enrollment or recovery.
     */
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

    /**
     * Removes a device key after its encrypted ARK record has been purged.
     *
     * @param record The stored record used for the operation.
     */
    fun forget(record: AccountRecoveryKeyDeviceRecord) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(alias(record.accountId, record.protectionId))
            }
        }
    }

    /**
     * Derives the Keystore key alias from [accountId] and [protectionId].
     *
     * @param accountId The account ID that owns the key or record.
     * @param protectionId The ID of this device-bound recovery key record.
     * @return The deterministic Android Keystore alias for the account and protection ID.
     */
    private fun alias(accountId: String, protectionId: String): String =
        "olive.account.ark.v1." + MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } +
            "." + protectionId

    /**
     * Builds an authentication-bound AES-GCM Android Keystore key specification for [alias].
     *
     * @param alias The Android Keystore alias for the key.
     * @return The Android Keystore specification for an authentication-bound AES-GCM key.
     */
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
) : DeviceAuthenticationChallenge {
    private var committed = false
    override val enrolling get() = saved == null
    override val promptCipher: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher() else null
    private var legacyCipher: Cipher? = null

    /**
     * Allocates and remembers an unlocked AES-GCM cipher for legacy pre-API-30 prompts.
     *
     * @return The initialized cipher for the legacy Android authentication prompt.
     */
    override fun legacyCipher(): Cipher = newCipher().also { legacyCipher = it }

    /**
     * Encrypts [recoveryKey] with the authenticated Keystore [cipher] into a device record.
     *
     * @param cipher The authenticated cipher used for the cryptographic operation.
     * @param recoveryKey The account recovery key used to protect or open the private keyset.
     * @return The device record containing the encrypted account recovery key and its metadata.
     */
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

    /**
     * Decrypts the stored device record using the authenticated Keystore [cipher].
     *
     * @param cipher The authenticated cipher used for the cryptographic operation.
     * @return The account recovery key decrypted from the supplied record.
     */
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

    /** Marks the newly enrolled Keystore entry as committed so [close] preserves it. */
    internal fun markStored() {
        committed = true
    }

    /** Deletes any uncommitted newly generated Keystore key if enrollment did not finish. */
    internal fun close() {
        if (enrolling && !committed) runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    /**
     * Initializes an AES-GCM cipher for encrypting a new ARK or decrypting [saved].
     *
     * @return The AES-GCM cipher initialized for enrollment or recovery as appropriate.
     */
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
