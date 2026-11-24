package com.olive.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Durable record for an encrypted local ARK copy backed by an Android Keystore key. */
internal data class AccountRecoveryKeyDeviceRecord(
    val accountId: String,
    val protectionId: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val version: Int = VERSION_APP_MANAGED,
) {
    init {
        require(version == VERSION_DEVICE_AUTH || version == VERSION_APP_MANAGED) {
            "Unsupported device protection record version"
        }
        require(validAccountId(accountId)) { "Invalid account ID" }
        require(runCatching { UUID.fromString(protectionId) }.isSuccess) { "Invalid device protection ID" }
        require(decode(ivBase64).size == IV_BYTES) { "Invalid device protection IV" }
        require(decode(ciphertextBase64).size > TAG_BYTES) { "Invalid protected recovery key" }
    }

    internal fun toJson() = JSONObject().apply {
        put("version", version)
        put("accountId", accountId)
        put("protectionId", protectionId)
        put("iv", ivBase64)
        put("ciphertext", ciphertextBase64)
    }

    internal fun aad() = "$version|$accountId|$protectionId".toByteArray(Charsets.UTF_8)

    companion object {
        internal const val VERSION_DEVICE_AUTH = 1
        internal const val VERSION_APP_MANAGED = 2
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

        internal fun fromJson(value: JSONObject): AccountRecoveryKeyDeviceRecord {
            val version = value.getInt("version")
            require(version == VERSION_DEVICE_AUTH || version == VERSION_APP_MANAGED) {
                "Unsupported device protection record"
            }
            return AccountRecoveryKeyDeviceRecord(
                value.getString("accountId"),
                value.getString("protectionId"),
                value.getString("iv"),
                value.getString("ciphertext"),
                version = version,
            )
        }

        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        private fun decode(value: String) = Base64.getDecoder().decode(value)
    }
}

/**
 * Hardware-backed Android Keystore protector for the local Account Recovery Key (ARK) copy.
 *
 * Uses app-managed AES-256-GCM keys without requiring biometric or device lock prompts, while
 * preserving per-encryption random IVs, AAD binding, and legacy v1 key cleanup.
 */
internal class AccountRecoveryKeyDeviceProtector {

    /**
     * Encrypts [recoveryKey] with a newly generated app-managed Android Keystore AES-256-GCM key
     * scoped to [accountId] without prompting for device authentication.
     */
    fun protect(
        accountId: String,
        recoveryKey: AccountRecoveryKey,
    ): AccountRecoveryKeyDeviceRecord {
        val protectionId = UUID.randomUUID().toString()
        val alias = alias(accountId, protectionId, AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED)
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec(alias))
            generateKey()
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(
                    "${AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED}|$accountId|$protectionId"
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val raw = recoveryKey.encodedCopy()
            val ciphertext = try {
                cipher.doFinal(raw)
            } finally {
                raw.fill(0)
            }
            AccountRecoveryKeyDeviceRecord(
                accountId = accountId,
                protectionId = protectionId,
                ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
                version = AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED,
            )
        } catch (failure: Throwable) {
            runCatching { store.deleteEntry(alias) }
            throw failure
        }
    }

    /**
     * Decrypts a stored [AccountRecoveryKeyDeviceRecord] created with app-managed Keystore protection
     * without prompting for device authentication.
     */
    fun unlock(record: AccountRecoveryKeyDeviceRecord): AccountRecoveryKey {
        require(record.version == AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED) {
            "Unsupported local recovery key protection version"
        }
        val alias = alias(record.accountId, record.protectionId, record.version)
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey)
            ?: error("Local recovery key protection is unavailable; recover this account again.")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.getDecoder().decode(record.ivBase64)),
            )
            updateAAD(record.aad())
        }
        val raw = cipher.doFinal(Base64.getDecoder().decode(record.ciphertextBase64))
        return try {
            AccountRecoveryKey.fromEncoded(raw)
        } finally {
            raw.fill(0)
        }
    }

    /** Removes a device key (v1 or v2) after its encrypted ARK record has been replaced or purged. */
    fun forget(record: AccountRecoveryKeyDeviceRecord) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(alias(record.accountId, record.protectionId, record.version))
            }
        }
    }

    private fun alias(accountId: String, protectionId: String, version: Int): String =
        "olive.account.ark.v$version." + MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } +
            "." + protectionId

    private fun spec(alias: String) = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
