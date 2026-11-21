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

/** AES-256 key that unlocks one account's encrypted keyset. */
internal class AccountRecoveryKey private constructor(internal val value: SecretKey) {
    internal fun encodedCopy(): ByteArray = value.encoded.copyOf()

    companion object {
        fun generate(): AccountRecoveryKey = AccountRecoveryKey(EncryptionUtils.generateSymmetricKey())

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

    /** Unlocks this envelope only for the account named in its authenticated header. */
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

    private fun aad() = listOf(version, accountId, keyId, epoch, algorithm)
        .joinToString("|").toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION = 1
        private const val KEY_ALGORITHM = "RSA-OAEP-256"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS

        /** Creates one new software account key pair and seals only its private portion under ARK. */
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

        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        private fun validKeyId(value: String) = runCatching { UUID.fromString(value) }.isSuccess
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}

/**
 * Web-compatible ciphertext envelope for one ARK protected by a passkey PRF result.
 *
 * Only this opaque value crosses Firebase. The PRF result, derived KEK, and raw ARK remain in the
 * client operation and are cleared where the platform exposes mutable byte arrays.
 */
internal data class PasskeyRecoveryEnvelope(
    val version: Int,
    val accountId: String,
    val credentialId: String,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
) {
    init {
        require(version == VERSION) { "Unsupported passkey recovery envelope" }
        require(validAccountId(accountId)) { "Invalid account ID" }
        require(CREDENTIAL_ID.matches(credentialId)) { "Invalid passkey credential ID" }
        require(decode(saltBase64).size == SALT_BYTES) { "Invalid recovery salt" }
        require(decode(ivBase64).size == IV_BYTES) { "Invalid recovery IV" }
        require(decode(ciphertextBase64).size == RECOVERY_KEY_BYTES + TAG_BYTES) {
            "Invalid encrypted recovery key"
        }
    }

    internal fun toMap(): Map<String, Any> = mapOf(
        "version" to version,
        "accountId" to accountId,
        "credentialId" to credentialId,
        "salt" to saltBase64,
        "iv" to ivBase64,
        "ciphertext" to ciphertextBase64,
    )

    internal fun open(
        expectedAccountId: String,
        expectedCredentialId: String,
        prfOutput: ByteArray,
    ): AccountRecoveryKey {
        require(expectedAccountId == accountId && expectedCredentialId == credentialId) {
            "Passkey recovery record belongs to another account or credential"
        }
        require(prfOutput.size == PRF_BYTES) { "Invalid passkey recovery output" }
        val key = deriveKey(prfOutput, decode(saltBase64))
        val plaintext = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, decode(ivBase64)))
                updateAAD(aad(accountId, credentialId))
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
        private const val RECOVERY_KEY_BYTES = 32
        private const val PRF_BYTES = 32
        private const val SALT_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * Byte.SIZE_BITS
        private const val AES = "AES"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val HKDF_INFO = "olive.account.ark.passkey-prf.v1"
        private val CREDENTIAL_ID = Regex("[A-Za-z0-9_-]{16,1024}")

        internal fun create(
            accountId: String,
            credentialId: String,
            prfOutput: ByteArray,
            recoveryKey: AccountRecoveryKey,
            salt: ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes),
            iv: ByteArray = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes),
        ): PasskeyRecoveryEnvelope {
            require(validAccountId(accountId)) { "Invalid account ID" }
            require(CREDENTIAL_ID.matches(credentialId)) { "Invalid passkey credential ID" }
            require(prfOutput.size == PRF_BYTES) { "Invalid passkey recovery output" }
            require(salt.size == SALT_BYTES) { "Invalid recovery salt" }
            require(iv.size == IV_BYTES) { "Invalid recovery IV" }
            val key = deriveKey(prfOutput, salt)
            val rawRecoveryKey = recoveryKey.encodedCopy()
            val ciphertext = try {
                Cipher.getInstance(AES_GCM).run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(aad(accountId, credentialId))
                    doFinal(rawRecoveryKey)
                }
            } finally {
                rawRecoveryKey.fill(0)
                key.fill(0)
            }
            return PasskeyRecoveryEnvelope(
                VERSION,
                accountId,
                credentialId,
                encode(salt),
                encode(iv),
                encode(ciphertext),
            )
        }

        internal fun fromMap(value: Map<*, *>): PasskeyRecoveryEnvelope = PasskeyRecoveryEnvelope(
            version = (value["version"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Invalid recovery version"),
            accountId = value["accountId"] as? String
                ?: throw IllegalArgumentException("Invalid account ID"),
            credentialId = value["credentialId"] as? String
                ?: throw IllegalArgumentException("Invalid passkey credential ID"),
            saltBase64 = value["salt"] as? String
                ?: throw IllegalArgumentException("Invalid recovery salt"),
            ivBase64 = value["iv"] as? String
                ?: throw IllegalArgumentException("Invalid recovery IV"),
            ciphertextBase64 = value["ciphertext"] as? String
                ?: throw IllegalArgumentException("Invalid encrypted recovery key"),
        )

        /** RFC 5869 HKDF-SHA-256. A 256-bit key needs only the first expansion block. */
        private fun deriveKey(prfOutput: ByteArray, salt: ByteArray): ByteArray {
            val extract = Mac.getInstance(HMAC_SHA_256).run {
                init(SecretKeySpec(salt, HMAC_SHA_256))
                doFinal(prfOutput)
            }
            return try {
                Mac.getInstance(HMAC_SHA_256).run {
                    init(SecretKeySpec(extract, HMAC_SHA_256))
                    doFinal(HKDF_INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
                }
            } finally {
                extract.fill(0)
            }
        }

        private fun aad(accountId: String, credentialId: String) =
            "$HKDF_INFO|$accountId|$credentialId".toByteArray(Charsets.UTF_8)

        private fun validAccountId(value: String) = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
