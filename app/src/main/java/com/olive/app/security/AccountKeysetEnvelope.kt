package com.olive.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
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

    internal fun toJson()=JSONObject().apply {
        put("keyId",keyId);put("epoch",epoch);put("algorithm",algorithm);put("wrappedKey",wrappedKey)
    }

    internal fun toMap():Map<String,Any> = mapOf(
        "keyId" to keyId,"epoch" to epoch,"algorithm" to algorithm,"wrappedKey" to wrappedKey,
    )

    internal fun unwrap(keyset:UnlockedAccountKeyset):SecretKey {
        require(keyset.keyId==keyId && keyset.epoch==epoch) { "Account wrapper uses another keyset" }
        return KeyManager.unwrapKey(wrappedKey,keyset.privateKey,1)
    }

    companion object {
        private const val ALGORITHM="RSA-OAEP-256"

        internal fun fromJson(value:JSONObject)=AccountKeysetWrappedKey(
            value.getString("keyId"),value.getLong("epoch"),value.getString("algorithm"),
            value.getString("wrappedKey"),
        )

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

/** Account-scoped local copies of encrypted keyset material; neither entry contains a raw ARK. */
internal class AccountKeysetLocalStore(private val vault: JsonVault) {
    internal data class Saved(
        val envelope: AccountKeysetEnvelope,
        val deviceRecord: AccountRecoveryKeyDeviceRecord,
    )

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

    fun save(value: Saved) {
        require(value.envelope.accountId == value.deviceRecord.accountId) { "Account keyset record mismatch" }
        vault.update { root ->
            root.child(SECTION).put(value.envelope.accountId, JSONObject().apply {
                put("envelope", value.envelope.toJson())
                put("deviceRecord", value.deviceRecord.toJson())
            })
        }
    }

    /** Removes every local ciphertext envelope and device-protected ARK record for this account. */
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

/**
 * Single WebAuthn JSON boundary shared by the compatibility probe and production recovery.
 * PRF output is extracted and removed here before a response can be sent to Firebase.
 */
internal object PasskeyPrfProtocol {
    const val RP_ID = "yourolivebranch.ca"
    private val CREDENTIAL_ID = Regex("[A-Za-z0-9_-]{16,1024}")

    internal data class AssertionResult(
        val credentialId: String,
        val serverResponseJson: String,
        val prfOutput: ByteArray,
    )

    fun registrationRequest(options: Map<*, *>): String = JSONObject(options).apply {
        require(nonEmpty(optString("challenge"))) { "Invalid registration challenge" }
        require(optJSONObject("rp")?.optString("id") == RP_ID) { "Invalid relying party" }
        require(nonEmpty(optJSONObject("user")?.optString("id"))) { "Invalid passkey user" }
    }.toString()

    fun assertionRequest(options: Map<*, *>, prfInputs: Map<String, String>): String {
        val request = JSONObject(options)
        require(nonEmpty(request.optString("challenge"))) { "Invalid assertion challenge" }
        require(request.optString("rpId") == RP_ID) { "Invalid relying party" }
        require(prfInputs.isNotEmpty()) { "No passkey recovery factors are available" }
        val values = JSONObject()
        prfInputs.forEach { (credentialId, encoded) ->
            require(CREDENTIAL_ID.matches(credentialId)) { "Invalid passkey credential ID" }
            val input = decodeBase64(encoded)
            require(input.size == PRF_BYTES) { "Invalid PRF input" }
            values.put(credentialId, JSONObject().put("first", base64Url(input)))
            input.fill(0)
        }
        val extensions = request.optJSONObject("extensions") ?: JSONObject()
        extensions.put("prf", JSONObject().put("evalByCredential", values))
        return request.put("extensions", extensions).toString()
    }

    fun assertionResult(authenticationResponseJson: String): AssertionResult {
        val response = JSONObject(authenticationResponseJson)
        val credentialId = response.optString("id")
        require(CREDENTIAL_ID.matches(credentialId)) { "Invalid passkey credential ID" }
        val prf = response.optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf") ?: throw IllegalArgumentException("Missing PRF result")
        val encoded = prf.optJSONObject("results")?.optString("first").orEmpty()
        val output = runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("Invalid PRF result", it) }
        require(output.size == PRF_BYTES) {
            output.fill(0)
            "Invalid PRF result"
        }
        prf.remove("results")
        return AssertionResult(credentialId, response.toString(), output)
    }

    fun registrationResult(registrationResponseJson: String): String {
        val response = JSONObject(registrationResponseJson)
        require(CREDENTIAL_ID.matches(response.optString("id"))) { "Invalid passkey credential ID" }
        response.optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.remove("results")
        return response.toString()
    }

    /** Debug-only request shape; it deliberately does not ask for PRF during creation. */
    fun probeCreationRequest(challenge: ByteArray, userId: ByteArray): String {
        requireLength(challenge, 32, "Create challenge")
        requireLength(userId, 16, "Probe user ID")
        return JSONObject().apply {
            put("challenge", base64Url(challenge))
            put("rp", JSONObject().put("id", RP_ID).put("name", "Olive Branch PRF Probe"))
            put("user", JSONObject().apply {
                put("id", base64Url(userId))
                put("name", "prf-probe")
                put("displayName", "Olive Branch compatibility test")
            })
            put("pubKeyCredParams", JSONArray().apply {
                put(JSONObject().put("type", "public-key").put("alg", -7))
                put(JSONObject().put("type", "public-key").put("alg", -257))
            })
            put("authenticatorSelection", JSONObject()
                .put("residentKey", "required")
                .put("userVerification", "required"))
            put("attestation", "none")
        }.toString()
    }

    /** Debug-only assertion builder using already decoded random probe values. */
    fun probeAssertionRequest(challenge: ByteArray, credentialId: String, prfInput: ByteArray): String {
        requireLength(challenge, 32, "Assertion challenge")
        require(CREDENTIAL_ID.matches(credentialId)) { "Invalid passkey credential ID" }
        requireLength(prfInput, PRF_BYTES, "PRF input")
        return JSONObject().apply {
            put("challenge", base64Url(challenge))
            put("rpId", RP_ID)
            put("allowCredentials", JSONArray().put(JSONObject()
                .put("type", "public-key")
                .put("id", credentialId)))
            put("userVerification", "required")
            put("extensions", JSONObject().put("prf", JSONObject()
                .put("evalByCredential", JSONObject().put(
                    credentialId,
                    JSONObject().put("first", base64Url(prfInput)),
                ))))
        }.toString()
    }

    /** Probe helper that never throws or retains malformed provider output. */
    fun prfOutputOrNull(authenticationResponseJson: String): ByteArray? {
        val encoded = runCatching {
            JSONObject(authenticationResponseJson)
                .optJSONObject("clientExtensionResults")
                ?.optJSONObject("prf")
                ?.optJSONObject("results")
                ?.optString("first")
                .orEmpty()
        }.getOrDefault("")
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == PRF_BYTES } ?: run {
            bytes.fill(0)
            null
        }
    }

    private fun requireLength(value: ByteArray, expected: Int, name: String) {
        require(value.size == expected) { "$name must contain $expected bytes" }
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid PRF input", error)
    }

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun nonEmpty(value: String?) = !value.isNullOrBlank() && value.length <= 2048

    private const val PRF_BYTES = 32
}

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
        private fun decode(value: String) = Base64.getDecoder().decode(value)
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
) : DeviceAuthenticationChallenge {
    private var committed = false
    override val enrolling get() = saved == null
    override val promptCipher: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher() else null
    private var legacyCipher: Cipher? = null

    override fun legacyCipher(): Cipher = newCipher().also { legacyCipher = it }

    internal fun enroll(cipher: Cipher, recoveryKey: AccountRecoveryKey): AccountRecoveryKeyDeviceRecord {
        check(enrolling && cipher === (promptCipher ?: legacyCipher))
        val raw = recoveryKey.encodedCopy()
        return try {
            cipher.updateAAD("1|$accountId|$protectionId".toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(raw)
            AccountRecoveryKeyDeviceRecord(
                accountId, protectionId,
                AndroidBase64.encodeToString(cipher.iv, AndroidBase64.NO_WRAP),
                AndroidBase64.encodeToString(ciphertext, AndroidBase64.NO_WRAP),
            )
        } finally {
            raw.fill(0)
        }
    }

    internal fun unlock(cipher: Cipher): AccountRecoveryKey {
        val record = checkNotNull(saved)
        check(!enrolling && cipher === (promptCipher ?: legacyCipher))
        cipher.updateAAD(record.aad())
        val raw = cipher.doFinal(AndroidBase64.decode(record.ciphertextBase64, AndroidBase64.NO_WRAP))
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
            GCMParameterSpec(128, AndroidBase64.decode(record.ivBase64, AndroidBase64.NO_WRAP)),
        )
    }
}
