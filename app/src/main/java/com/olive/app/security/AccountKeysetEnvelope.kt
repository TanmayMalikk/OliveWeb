package com.olive.app.security

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator

/** AES-256 key that unlocks one account's encrypted keyset. */
internal class AccountRecoveryKey private constructor(internal val value: SecretKey) {
    /**
     * Returns a defensive copy of the raw 256-bit recovery key bytes.
     *
     * @return A defensive copy of the raw 32-byte recovery key.
     */
    internal fun encodedCopy(): ByteArray = value.encoded.copyOf()

    companion object {
        /**
         * Generates a random AES-256 [AccountRecoveryKey].
         *
         * @return A newly generated AES-256 account recovery key.
         */
        fun generate(): AccountRecoveryKey = AccountRecoveryKey(EncryptionUtils.generateSymmetricKey())

        /**
         * Constructs an [AccountRecoveryKey] from raw 32-byte AES key material.
         *
         * @param encoded The 32 bytes of AES recovery key material.
         * @return The recovery key reconstructed from the supplied AES key bytes.
         */
        internal fun fromEncoded(encoded: ByteArray): AccountRecoveryKey {
            require(encoded.size == 32) { "Invalid account recovery key" }
            return AccountRecoveryKey(javax.crypto.spec.SecretKeySpec(encoded, "AES"))
        }
    }
}

/** Plain account-specific key material available only while an ARK is unlocked. */
internal data class UnlockedAccountKeyset(
    val accountId: String,
    val keyId: String,
    val epoch: Long,
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

/** Opaque AES-key copy encrypted for one recoverable account keyset. */
data class AccountKeysetWrappedKey(
    val keyId:String,
    val epoch:Long,
    val algorithm:String,
    val wrappedKey:String,
) {
    init {
        require(runCatching { UUID.fromString(keyId) }.isSuccess) { "Invalid account key ID" }
        require(epoch>=1) { "Invalid account key epoch" }
        require(algorithm==ALGORITHM) { "Unsupported account key algorithm" }
        require(wrappedKey.isNotBlank() && wrappedKey.length<=768) { "Invalid account-wrapped key" }
    }

    /**
     * Serializes this wrapped key entry into a [JSONObject].
     *
     * @return A JSON object containing the serialized fields.
     */
    internal fun toJson()=JSONObject().apply {
        put("keyId",keyId);put("epoch",epoch);put("algorithm",algorithm);put("wrappedKey",wrappedKey)
    }

    /**
     * Converts this wrapped key entry into a Firestore-compatible map.
     *
     * @return The serialized fields ready for storage or transport.
     */
    internal fun toMap():Map<String,Any> = mapOf(
        "keyId" to keyId,"epoch" to epoch,"algorithm" to algorithm,"wrappedKey" to wrappedKey,
    )

    /**
     * Unwraps the content encryption key using [keyset]'s private key.
     *
     * @param keyset The account keyset used for encryption or recovery.
     * @return The unwrapped content key for the matching account keyset.
     */
    internal fun unwrap(keyset:UnlockedAccountKeyset):SecretKey {
        require(keyset.keyId==keyId && keyset.epoch==epoch) { "Account wrapper uses another keyset" }
        return KeyManager.unwrapKey(wrappedKey,keyset.privateKey,1)
    }

    companion object {
        private const val ALGORITHM="RSA-OAEP-256"

        /**
         * Parses an [AccountKeysetWrappedKey] from a serialized [JSONObject].
         *
         * @param value The serialized JSON record to parse.
         * @return The record reconstructed from the serialized JSON fields.
         */
        internal fun fromJson(value:JSONObject)=AccountKeysetWrappedKey(
            value.getString("keyId"),value.getLong("epoch"),value.getString("algorithm"),
            value.getString("wrappedKey"),
        )

        /**
         * Parses an [AccountKeysetWrappedKey] from a Firestore field map.
         *
         * @param value The stored field map to parse.
         * @return The wrapped encryption key reconstructed from the Firestore fields.
         */
        internal fun fromMap(value:Map<*,*>)=AccountKeysetWrappedKey(
            value["keyId"] as? String ?: error("Missing keyId"),
            (value["epoch"] as? Number)?.toLong() ?: error("Missing epoch"),
            value["algorithm"] as? String ?: error("Missing algorithm"),
            value["wrappedKey"] as? String ?: error("Missing wrappedKey"),
        )
    }
}

/** Account keyset material is unusable if an authenticated envelope cannot be parsed or unlocked. */
internal class AccountKeysetException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Versioned ARK-encrypted account private-key envelope.
 *
 * Its header intentionally contains only routing/version data and the public key. The private key
 * remains encrypted under a unique random-IV AES-GCM payload bound to every header field through
 * additional authenticated data.
 */
internal data class AccountKeysetEnvelope(
    val version: Int,
    val accountId: String,
    val keyId: String,
    val epoch: Long,
    val algorithm: String,
    val publicKeyBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    init {
        require(version == VERSION) { "Unsupported account keyset version" }
        require(validAccountId(accountId)) { "Invalid account ID" }
        require(validKeyId(keyId)) { "Invalid account key ID" }
        require(epoch >= 1) { "Invalid account key epoch" }
        require(algorithm == KEY_ALGORITHM) { "Unsupported account keyset algorithm" }
        require(decode(publicKeyBase64).isNotEmpty()) { "Missing account public key" }
        require(decode(ivBase64).size == IV_BYTES) { "Invalid account keyset IV" }
        require(decode(ciphertextBase64).size > TAG_BYTES) { "Invalid account keyset ciphertext" }
    }

    internal val ciphertext: ByteArray get() = decode(ciphertextBase64)

    /**
     * Unlocks this envelope only for the account named in its authenticated header.
     *
     * @param expectedAccountId The account ID the encrypted data must belong to.
     * @param recoveryKey The account recovery key used to protect or open the private keyset.
     * @return The account's unlocked public and private key material.
     */
    fun unlock(expectedAccountId: String, recoveryKey: AccountRecoveryKey): UnlockedAccountKeyset {
        require(expectedAccountId == accountId) { "Account keyset belongs to another account" }
        val plaintext = Cipher.getInstance(AES_GCM).run {
            init(Cipher.DECRYPT_MODE, recoveryKey.value, GCMParameterSpec(TAG_BITS, decode(ivBase64)))
            updateAAD(aad())
            doFinal(ciphertext)
        }
        return try {
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(plaintext))
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(decode(publicKeyBase64)))
            UnlockedAccountKeyset(accountId, keyId, epoch, publicKey, privateKey)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Serializes the encrypted keyset envelope header and ciphertext into a [JSONObject].
     *
     * @return A JSON object containing the serialized fields.
     */
    internal fun toJson() = JSONObject().apply {
        put("version", version)
        put("accountId", accountId)
        put("keyId", keyId)
        put("epoch", epoch)
        put("algorithm", algorithm)
        put("publicKey", publicKeyBase64)
        put("iv", ivBase64)
        put("ciphertext", ciphertextBase64)
    }

    /**
     * Builds the AES-GCM additional authenticated data binding header fields together.
     *
     * @return The UTF-8 bytes supplied as additional authenticated data for AES-GCM.
     */
    private fun aad() = listOf(version, accountId, keyId, epoch, algorithm)
        .joinToString("|").toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION = 1
        private const val KEY_ALGORITHM = "RSA-OAEP-256"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS

        /**
         * Creates one new software account key pair and seals only its private portion under ARK.
         *
         * @param accountId The account ID that owns the key or record.
         * @param recoveryKey The account recovery key used to protect or open the private keyset.
         * @return The encrypted account keyset envelope.
         */
        fun create(accountId: String, recoveryKey: AccountRecoveryKey): AccountKeysetEnvelope {
            require(validAccountId(accountId)) { "Invalid account ID" }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
            val keyId = UUID.randomUUID().toString()
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            val header = AccountKeysetEnvelope(
                VERSION, accountId, keyId, 1, KEY_ALGORITHM, publicKey,
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(ByteArray(TAG_BYTES + 1)),
            )
            val privateKey = pair.private.encoded
            val ciphertext = try {
                Cipher.getInstance(AES_GCM).run {
                    init(Cipher.ENCRYPT_MODE, recoveryKey.value, GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(header.aad())
                    doFinal(privateKey)
                }
            } finally {
                privateKey.fill(0)
            }
            return header.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext))
        }

        /**
         * Deserializes an [AccountKeysetEnvelope] from a stored [JSONObject].
         *
         * @param value The serialized JSON record to parse.
         * @return The record reconstructed from the serialized JSON fields.
         */
        internal fun fromJson(value: JSONObject) = AccountKeysetEnvelope(
            version = value.getInt("version"),
            accountId = value.getString("accountId"),
            keyId = value.getString("keyId"),
            epoch = value.getLong("epoch"),
            algorithm = value.getString("algorithm"),
            publicKeyBase64 = value.getString("publicKey"),
            ivBase64 = value.getString("iv"),
            ciphertextBase64 = value.getString("ciphertext"),
        )

        /**
         * Checks that [value] is a non-empty URL/path-safe account UID string.
         *
         * @param value The candidate account identifier to validate.
         * @return True when the identifier has 1–128 letters, digits, underscores, or hyphens.
         */
        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        /**
         * Checks whether [value] is a valid UUID key identifier.
         *
         * @param value The candidate UUID key identifier.
         * @return True when the identifier parses as a UUID.
         */
        private fun validKeyId(value: String) = runCatching { UUID.fromString(value) }.isSuccess
        /**
         * Decodes a Base64 string into raw bytes.
         *
         * @param value The Base64 string to decode.
         * @return The bytes decoded from the Base64 string.
         */
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}

/**
 * Ciphertext envelope for one ARK protected by an OPAQUE export key.
 *
 * This provides zero-knowledge password recovery failsafe. Only this opaque value
 * crosses Firebase. The export key, derived KEK, and raw ARK remain strictly client-side
 * and are zeroed immediately after use.
 */
internal data class OpaquePasswordRecoveryEnvelope(
    val version: Int,
    val accountId: String,
    val opaqueRecordId: String,
    val algorithm: String,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    init {
        require(version == VERSION) { "Unsupported password recovery version" }
        require(validAccountId(accountId)) { "Invalid account ID" }
        require(RECORD_ID.matches(opaqueRecordId)) { "Invalid OPAQUE record ID" }
        require(algorithm == ALGORITHM) { "Unsupported password recovery algorithm" }
        require(decode(saltBase64).size == SALT_BYTES) { "Invalid recovery salt" }
        require(decode(ivBase64).size == IV_BYTES) { "Invalid recovery IV" }
        require(decode(ciphertextBase64).size == RECOVERY_KEY_BYTES + TAG_BYTES) {
            "Invalid encrypted recovery key"
        }
    }

    /**
     * Converts this password recovery envelope into a backend callable map payload.
     *
     * @return The serialized fields ready for storage or transport.
     */
    internal fun toMap(): Map<String, Any> = mapOf(
        "version" to version,
        "accountId" to accountId,
        "opaqueRecordId" to opaqueRecordId,
        "algorithm" to algorithm,
        "salt" to saltBase64,
        "iv" to ivBase64,
        "ciphertext" to ciphertextBase64,
    )

    /**
     * Decrypts and returns the wrapped [AccountRecoveryKey] using [exportKey] and HKDF-SHA-256.
     *
     * @param expectedAccountId The account ID the encrypted data must belong to.
     * @param expectedRecordId The OPAQUE record ID the recovery envelope must match.
     * @param exportKey The OPAQUE export key derived from the user's recovery password.
     * @return The account recovery key decrypted from the supplied record.
     */
    internal fun open(
        expectedAccountId: String,
        expectedRecordId: String,
        exportKey: ByteArray,
    ): AccountRecoveryKey {
        require(expectedAccountId == accountId && expectedRecordId == opaqueRecordId) {
            "Password recovery record belongs to another account or record"
        }
        require(exportKey.size in VALID_EXPORT_KEY_BYTES) { "Invalid OPAQUE export key" }
        val key = deriveKey(exportKey, decode(saltBase64), accountId)
        val authData = aad(version, accountId, opaqueRecordId, algorithm)
        val plaintext = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, decode(ivBase64)))
                updateAAD(authData)
                doFinal(decode(ciphertextBase64))
            }
        } finally {
            key.fill(0)
        }
        return try {
            AccountRecoveryKey.fromEncoded(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        private const val VERSION = 1
        private const val ALGORITHM = "OPAQUE-3DH-HKDF-SHA256-AES-256-GCM"
        private const val RECOVERY_KEY_BYTES = 32
        private val VALID_EXPORT_KEY_BYTES = setOf(32, 64)
        private const val SALT_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * Byte.SIZE_BITS
        private const val AES = "AES"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val HKDF_PREFIX = "olive-branch/password-recovery/v1/"
        private val RECORD_ID = Regex("[A-Za-z0-9_-]{16,1024}")

        /**
         * Encrypts [recoveryKey] under a key derived from OPAQUE [exportKey] and random salt/IV.
         *
         * @param accountId The account ID that owns the key or record.
         * @param opaqueRecordId The OPAQUE record identifier for this password recovery envelope.
         * @param exportKey The OPAQUE export key derived from the user's recovery password.
         * @param recoveryKey The account recovery key used to protect or open the private keyset.
         * @param salt The salt used to derive the wrapping key.
         * @param iv The initialization vector used to encrypt the recovery envelope.
         * @return The recovery envelope containing the password-protected key.
         */
        internal fun create(
            accountId: String,
            opaqueRecordId: String,
            exportKey: ByteArray,
            recoveryKey: AccountRecoveryKey,
            salt: ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes),
            iv: ByteArray = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes),
        ): OpaquePasswordRecoveryEnvelope {
            require(validAccountId(accountId)) { "Invalid account ID" }
            require(RECORD_ID.matches(opaqueRecordId)) { "Invalid OPAQUE record ID" }
            require(exportKey.size in VALID_EXPORT_KEY_BYTES) { "Invalid OPAQUE export key" }
            require(salt.size == SALT_BYTES) { "Invalid recovery salt" }
            require(iv.size == IV_BYTES) { "Invalid recovery IV" }
            val key = deriveKey(exportKey, salt, accountId)
            val authData = aad(VERSION, accountId, opaqueRecordId, ALGORITHM)
            val rawRecoveryKey = recoveryKey.encodedCopy()
            val ciphertext = try {
                Cipher.getInstance(AES_GCM).run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(authData)
                    doFinal(rawRecoveryKey)
                }
            } finally {
                rawRecoveryKey.fill(0)
                key.fill(0)
            }
            return OpaquePasswordRecoveryEnvelope(
                VERSION,
                accountId,
                opaqueRecordId,
                ALGORITHM,
                encode(salt),
                encode(iv),
                encode(ciphertext),
            )
        }

        /**
         * Parses an [OpaquePasswordRecoveryEnvelope] from a map returned by Cloud Functions.
         *
         * @param value The stored field map to parse.
         * @return The recovery envelope containing the OPAQUE-protected key.
         */
        internal fun fromMap(value: Map<*, *>): OpaquePasswordRecoveryEnvelope = OpaquePasswordRecoveryEnvelope(
            version = (value["version"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Invalid recovery version"),
            accountId = value["accountId"] as? String
                ?: throw IllegalArgumentException("Invalid account ID"),
            opaqueRecordId = value["opaqueRecordId"] as? String
                ?: throw IllegalArgumentException("Invalid OPAQUE record ID"),
            algorithm = value["algorithm"] as? String ?: ALGORITHM,
            saltBase64 = value["salt"] as? String
                ?: throw IllegalArgumentException("Invalid recovery salt"),
            ivBase64 = value["iv"] as? String
                ?: throw IllegalArgumentException("Invalid recovery IV"),
            ciphertextBase64 = value["ciphertext"] as? String
                ?: throw IllegalArgumentException("Invalid encrypted recovery key"),
        )

        /**
         * RFC 5869 HKDF-SHA-256 bound to purpose, version, and UID.
         *
         * @param exportKey The OPAQUE export key bytes for this user.
         * @param salt The salt used to derive the wrapping key.
         * @param accountId The owning account UID.
         * @return The 32-byte wrapping key derived with HKDF-SHA-256.
         */
        private fun deriveKey(exportKey: ByteArray, salt: ByteArray, accountId: String): ByteArray {
            val extract = Mac.getInstance(HMAC_SHA_256).run {
                init(SecretKeySpec(salt, HMAC_SHA_256))
                doFinal(exportKey)
            }
            val info = (HKDF_PREFIX + accountId).toByteArray(Charsets.UTF_8)
            return try {
                Mac.getInstance(HMAC_SHA_256).run {
                    init(SecretKeySpec(extract, HMAC_SHA_256))
                    doFinal(info + byteArrayOf(1))
                }
            } finally {
                extract.fill(0)
            }
        }

        /**
         * Builds AES-GCM additional authenticated data binding version, UID, record ID, and algorithm.
         *
         * @param version Recovery envelope format version.
         * @param accountId Firebase UID of the owning account.
         * @param recordId Unique OPAQUE password recovery record identifier.
         * @param algorithm Cryptographic suite identifier protecting the recovery key.
         * @return UTF-8 bytes supplied as AES-GCM additional authenticated data.
         */
        private fun aad(version: Int, accountId: String, recordId: String, algorithm: String) =
            "$version|$accountId|$recordId|$algorithm".toByteArray(Charsets.UTF_8)

        /**
         * Checks that [value] is a non-empty URL/path-safe account UID string.
         *
         * @param value The candidate account identifier to validate.
         * @return True when the identifier has 1–128 letters, digits, underscores, or hyphens.
         */
        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        /**
         * Encodes raw bytes into standard Base64.
         *
         * @param value The bytes to encode in standard Base64.
         * @return The standard Base64 representation of the input bytes.
         */
        private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        /**
         * Decodes a Base64 string into raw bytes.
         *
         * @param value The Base64 string to decode.
         * @return The bytes decoded from the Base64 string.
         */
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}

/** Account-scoped local copies of encrypted keyset material; neither entry contains a raw ARK. */
internal class AccountKeysetLocalStore(private val vault: JsonVault) {
    internal data class Saved(
        val envelope: AccountKeysetEnvelope,
        val deviceRecord: AccountRecoveryKeyDeviceRecord,
    )

    /**
     * Reads the local encrypted keyset envelope and device record for [accountId], if present.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The stored keyset envelope and recovery record, or null when unavailable or unreadable.
     */
    fun read(accountId: String): Saved? {
        val value = vault.read().optJSONObject(SECTION)?.optJSONObject(accountId) ?: return null
        return try {
            Saved(
                AccountKeysetEnvelope.fromJson(value.getJSONObject("envelope")),
                AccountRecoveryKeyDeviceRecord.fromJson(value.getJSONObject("deviceRecord")),
            ).also {
                check(it.envelope.accountId == accountId && it.deviceRecord.accountId == accountId) {
                    "Stored account keyset belongs to another account"
                }
            }
        } catch (failure: Exception) {
            throw AccountKeysetException("Stored account keyset is invalid", failure)
        }
    }

    /**
     * Persists a validated encrypted keyset envelope and device-protected ARK record into the local vault.
     *
     * @param value The paired account envelope and device-protected recovery key record.
     */
    fun save(value: Saved) {
        require(value.envelope.accountId == value.deviceRecord.accountId) { "Account keyset record mismatch" }
        vault.update { root ->
            root.child(SECTION).put(value.envelope.accountId, JSONObject().apply {
                put("envelope", value.envelope.toJson())
                put("deviceRecord", value.deviceRecord.toJson())
            })
        }
    }

    /**
     * Removes every local ciphertext envelope and device-protected ARK record for this account.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The device protection record that was removed, or null when there was no record.
     */
    fun purge(accountId: String): AccountRecoveryKeyDeviceRecord? {
        var removed: AccountRecoveryKeyDeviceRecord? = null
        vault.update { root ->
            val entries = root.optJSONObject(SECTION) ?: return@update
            entries.optJSONObject(accountId)?.let { value ->
                removed = runCatching {
                    AccountRecoveryKeyDeviceRecord.fromJson(value.getJSONObject("deviceRecord"))
                }.getOrNull()
            }
            entries.remove(accountId)
        }
        return removed
    }

    private companion object {
        const val SECTION = "accountKeysets"
    }
}

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

    /**
     * Serializes this device-protected ARK record into a [JSONObject].
     *
     * @return A JSON object containing the serialized fields.
     */
    internal fun toJson() = JSONObject().apply {
        put("version", version)
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
    internal fun aad() = "$version|$accountId|$protectionId".toByteArray(Charsets.UTF_8)

    companion object {
        internal const val VERSION_DEVICE_AUTH = 1
        internal const val VERSION_APP_MANAGED = 2
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

        /**
         * Deserializes and validates an [AccountRecoveryKeyDeviceRecord] from a [JSONObject].
         *
         * @param value The serialized JSON record to parse.
         * @return The decoded device protection record.
         */
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
     *
     * @param accountId The Firebase UID owning the recovery key record.
     * @param recoveryKey The account recovery key to seal in local device storage.
     * @return The sealed device record referencing the generated Keystore alias.
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
     *
     * @param record The locally stored app-managed ARK record to unwrap.
     * @return The decrypted account recovery key held only in client memory.
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

    /**
     * Removes a device key (v1 or v2) after its encrypted ARK record has been replaced or purged.
     *
     * @param record The stored record used to identify the Keystore alias to delete.
     */
    fun forget(record: AccountRecoveryKeyDeviceRecord) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(alias(record.accountId, record.protectionId, record.version))
            }
        }
    }

    /**
     * Derives the Keystore key alias from [accountId], [protectionId], and [version].
     *
     * @param accountId The account ID that owns the key or record.
     * @param protectionId The unique protection ID for this encrypted ARK record.
     * @param version Protection schema version determining the Keystore alias prefix.
     * @return The deterministic Android Keystore alias for the account, protection ID, and version.
     */
    private fun alias(accountId: String, protectionId: String, version: Int): String =
        "olive.account.ark.v$version." + MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } +
            "." + protectionId

    /**
     * Builds an app-managed AES-GCM Android Keystore key specification for [alias].
     *
     * @param alias The Android Keystore alias for the key.
     * @return The Android Keystore specification for an AES-256-GCM key.
     */
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
