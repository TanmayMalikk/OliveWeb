package com.olive.app.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetException
import com.olive.app.security.PasskeyRecoveryEnvelope
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Reads and writes only the ARK-encrypted account-keyset envelope; it never receives an ARK. */
internal class AccountKeysetRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    /**
     * Publishes a ciphertext-only envelope for the authenticated envelope owner.
     *
     * @param envelope The encrypted envelope carrying the account keyset.
     */
    suspend fun publish(envelope: AccountKeysetEnvelope) {
        require(auth.currentUser?.uid == envelope.accountId) { "Sign in again before saving account keys" }
        val payload = mapOf(
            "version" to envelope.version,
            "accountId" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "iv" to envelope.ivBase64,
            "ciphertext" to envelope.ciphertextBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val directory = mapOf(
            "ownerUid" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        firestore.batch().apply {
            set(firestore.collection(COLLECTION).document(envelope.accountId), payload)
            set(firestore.collection(DIRECTORY).document(envelope.accountId), directory)
        }.commit().await()
    }

    /**
     * Fetches the latest envelope from the server, bypassing Firestore's cache for recovery.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The latest server envelope, or null if the account has not published a keyset.
     */
    suspend fun fetch(accountId: String): AccountKeysetEnvelope? {
        require(auth.currentUser?.uid == accountId) { "Sign in again before recovering account keys" }
        val snapshot = firestore.collection(COLLECTION).document(accountId).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return try {
            AccountKeysetEnvelope.fromJson(JSONObject().apply {
                snapshot.data.orEmpty().forEach { (name, value) ->
                    if (name != "updatedAt") put(name, value)
                }
            })
        } catch (failure: Exception) {
            throw AccountKeysetException("Account keyset is invalid or unavailable", failure)
        }
    }

    companion object {
        private const val COLLECTION = "accountKeysets"
        private const val DIRECTORY = "accountKeyDirectories"
    }
}

internal fun interface AccountRecoveryCallable {
    /**
     * Invokes the named recovery Cloud Function with serialized request parameters.
     *
     * @param name The Firebase callable function name.
     * @param data The data payload to read or deserialize.
     * @return The payload returned by the callable function, which may be null.
     */
    suspend fun call(name: String, data: Map<String, Any?>): Any?
}

private class FirebaseAccountRecoveryCallable(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) : AccountRecoveryCallable {
    /**
     * Calls the named Firebase callable function and returns its deserialized payload.
     *
     * @param name The Firebase callable function name.
     * @param data The data payload to read or deserialize.
     * @return The payload returned by the callable function, which may be null.
     */
    override suspend fun call(name: String, data: Map<String, Any?>): Any? =
        functions.getHttpsCallable(name).call(data).await().data
}

/**
 * Ciphertext-only passkey recovery transport. Credential Manager and PRF bytes never cross this
 * class; callers provide the already-sanitized public WebAuthn response JSON.
 */
internal class PasskeyRecoveryRepository(
    private val callable: AccountRecoveryCallable = FirebaseAccountRecoveryCallable(),
) {
    internal data class RegistrationStart(
        val options: Map<String, Any?>,
        val challengeId: String,
    )

    internal data class AssertionStart(
        val options: Map<String, Any?>,
        val challengeId: String,
        val prfInputs: Map<String, String>,
    )

    internal data class UnlockResult(
        val envelope: PasskeyRecoveryEnvelope,
        val prfInput: String,
    )

    internal data class Credential(
        val credentialId: String,
        val deviceType: String,
        val backedUp: Boolean,
    )

    /**
     * Starts passkey recovery enrollment and returns WebAuthn registration options.
     *
     * @return The registration challenge and WebAuthn creation options.
     */
    suspend fun beginEnrollment(): RegistrationStart {
        val value = response(callable.call(BEGIN_ENROLLMENT, emptyMap()))
        return RegistrationStart(options(value), challengeId(value))
    }

    /**
     * Submits the WebAuthn registration response and requests PRF assertion options.
     *
     * @param challengeId The server-issued identifier for the current passkey challenge.
     * @param registrationResponseJson The WebAuthn registration response from Credential Manager.
     * @return The assertion options and salts needed to finish enrolling the passkey.
     */
    suspend fun continueEnrollment(challengeId: String, registrationResponseJson: String): AssertionStart {
        val value = response(callable.call(CONTINUE_ENROLLMENT, mapOf(
            "challengeId" to validChallengeId(challengeId),
            "response" to jsonMap(registrationResponseJson),
        )))
        return assertionStart(value)
    }

    /**
     * Completes passkey recovery enrollment by storing the PRF-wrapped recovery envelope.
     *
     * @param challengeId The server-issued identifier for the current passkey challenge.
     * @param assertionResponseJson The WebAuthn authentication response from Credential Manager.
     * @param envelope The encrypted envelope carrying the account keyset.
     * @return The credential identifier for the newly enrolled passkey.
     */
    suspend fun completeEnrollment(
        challengeId: String,
        assertionResponseJson: String,
        envelope: PasskeyRecoveryEnvelope,
    ): String {
        val value = response(callable.call(COMPLETE_ENROLLMENT, mapOf(
            "challengeId" to validChallengeId(challengeId),
            "response" to jsonMap(assertionResponseJson),
            "envelope" to envelope.toMap(),
        )))
        check(value["registered"] == true) { "Passkey recovery enrollment was not completed" }
        return credentialId(value["credentialId"]).also {
            check(it == envelope.credentialId) { "Passkey recovery credential mismatch" }
        }
    }

    /**
     * Begins passkey recovery sign-in by fetching assertion options and PRF salts.
     *
     * @return The assertion challenge, WebAuthn options, and PRF salts for recovery.
     */
    suspend fun beginUnlock(): AssertionStart =
        assertionStart(response(callable.call(BEGIN_UNLOCK, emptyMap())))

    /**
     * Verifies a WebAuthn unlock assertion and returns the stored recovery envelope.
     *
     * @param challengeId The server-issued identifier for the current passkey challenge.
     * @param assertionResponseJson The WebAuthn authentication response from Credential Manager.
     * @return The stored recovery envelope and the matching PRF input salt.
     */
    suspend fun completeUnlock(challengeId: String, assertionResponseJson: String): UnlockResult {
        val value = response(callable.call(COMPLETE_UNLOCK, mapOf(
            "challengeId" to validChallengeId(challengeId),
            "response" to jsonMap(assertionResponseJson),
        )))
        val envelope = PasskeyRecoveryEnvelope.fromMap(
            value["envelope"] as? Map<*, *> ?: error("Invalid passkey recovery envelope"),
        )
        val prfInput = validPrfInput(value["prfInput"])
        return UnlockResult(envelope, prfInput)
    }

    /**
     * Lists registered passkey recovery factors for the signed-in account.
     *
     * @return The recovery credentials currently registered for the account.
     */
    suspend fun listCredentials(): List<Credential> {
        val values = response(callable.call(LIST_CREDENTIALS, emptyMap()))["credentials"] as? List<*>
            ?: error("Invalid passkey recovery credential list")
        return values.map { raw ->
            val value = raw as? Map<*, *> ?: error("Invalid passkey recovery credential")
            Credential(
                credentialId(value["credentialId"]),
                when (value["deviceType"]) {
                    "multiDevice" -> "multiDevice"
                    "singleDevice" -> "singleDevice"
                    else -> error("Invalid passkey recovery credential")
                },
                value["backedUp"] as? Boolean ?: error("Invalid passkey recovery credential"),
            )
        }
    }

    /**
     * Revokes a previously enrolled passkey recovery credential on the backend.
     *
     * @param credentialId The WebAuthn credential identifier for the passkey.
     */
    suspend fun removeCredential(credentialId: String) {
        val id = credentialId(credentialId)
        val value = response(callable.call(REMOVE_CREDENTIAL, mapOf("credentialId" to id)))
        check(value["removed"] == true && value["credentialId"] == id) {
            "Passkey recovery credential was not removed"
        }
    }

    /**
     * Extracts assertion options, challenge ID, and validated PRF inputs from a response map.
     *
     * @param value The stored field map to parse.
     * @return The parsed assertion challenge, request options, and PRF inputs.
     */
    private fun assertionStart(value: Map<*, *>): AssertionStart {
        val rawInputs = value["prfInputs"] as? Map<*, *> ?: error("Invalid passkey recovery assertion")
        val inputs = rawInputs.entries.associate { (rawId, rawInput) ->
            credentialId(rawId) to validPrfInput(rawInput)
        }
        check(inputs.isNotEmpty()) { "No passkey recovery factors are available" }
        return AssertionStart(options(value), challengeId(value), inputs)
    }

    /**
     * Extracts and validates WebAuthn options from a callable response map.
     *
     * @param value The stored field map to parse.
     * @return The validated WebAuthn options from the server response.
     */
    private fun options(value: Map<*, *>): Map<String, Any?> {
        val raw = value["options"] as? Map<*, *> ?: error("Invalid passkey recovery options")
        return raw.entries.associate { (key, item) ->
            (key as? String ?: error("Invalid passkey recovery options")) to item
        }
    }

    /**
     * Extracts and validates the passkey challenge identifier from a callable response map.
     *
     * @param value The stored field map to parse.
     * @return The validated challenge ID from the server response.
     */
    private fun challengeId(value: Map<*, *>) = validChallengeId(value["challengeId"])

    private companion object {
        const val BEGIN_ENROLLMENT = "passkeyRecoveryBeginEnrollment"
        const val CONTINUE_ENROLLMENT = "passkeyRecoveryContinueEnrollment"
        const val COMPLETE_ENROLLMENT = "passkeyRecoveryCompleteEnrollment"
        const val BEGIN_UNLOCK = "passkeyRecoveryBeginUnlock"
        const val COMPLETE_UNLOCK = "passkeyRecoveryCompleteUnlock"
        const val LIST_CREDENTIALS = "passkeyRecoveryListCredentials"
        const val REMOVE_CREDENTIAL = "passkeyRecoveryRemoveCredential"
        val CREDENTIAL_ID = Regex("[A-Za-z0-9_-]{16,1024}")
        val CHALLENGE_ID = Regex("[A-Za-z0-9_-]{22,128}")

        /**
         * Casts a raw callable payload to a key-value map or throws if malformed.
         *
         * @param value The untyped payload returned by the callable function.
         * @return The server payload as a map.
         */
        fun response(value: Any?): Map<*, *> = value as? Map<*, *>
            ?: error("Invalid passkey recovery response")

        /**
         * Validates a passkey credential identifier against expected Base64URL bounds.
         *
         * @param value The untyped credential ID returned by the server.
         * @return The validated Base64URL credential identifier.
         */
        fun credentialId(value: Any?): String = (value as? String)
            ?.takeIf(CREDENTIAL_ID::matches) ?: error("Invalid passkey recovery credential")

        /**
         * Validates a passkey recovery challenge identifier against expected bounds.
         *
         * @param value The untyped challenge ID returned by the server.
         * @return The challenge identifier after validating its format.
         */
        fun validChallengeId(value: Any?): String = (value as? String)
            ?.takeIf(CHALLENGE_ID::matches) ?: error("Invalid passkey recovery challenge")

        /**
         * Decodes and verifies that a WebAuthn PRF input salt is exactly 32 bytes.
         *
         * @param value The encoded PRF salt returned by the server.
         * @return The encoded PRF salt after verifying that it decodes to 32 bytes.
         */
        fun validPrfInput(value: Any?): String {
            val encoded = value as? String ?: error("Invalid passkey PRF input")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: error("Invalid passkey PRF input")
            check(bytes.size == 32) { "Invalid passkey PRF input" }
            bytes.fill(0)
            return encoded
        }

        /**
         * Parses a WebAuthn response JSON string while ensuring no raw PRF results leave the client.
         *
         * @param value The raw WebAuthn response JSON.
         * @return A map containing the response with the local PRF result removed.
         */
        fun jsonMap(value: String): Map<String, Any?> {
            val response = JSONObject(value)
            check(response.optJSONObject("clientExtensionResults")
                ?.optJSONObject("prf")
                ?.has("results") != true) {
                "Passkey secret material must remain on the client"
            }
            return jsonObjectMap(response)
        }

        /**
         * Recursively converts a [JSONObject] into a plain Kotlin map.
         *
         * @param value The JSON object whose fields are converted.
         * @return A Kotlin map with nested JSON objects and arrays converted to Kotlin values.
         */
        fun jsonObjectMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence()
            .associateWith { key -> jsonValue(value.get(key)) }

        /**
         * Converts a JSON element into its primitive, map, list, or null representation.
         *
         * @param value The JSON field value to convert to a Kotlin value.
         * @return The corresponding Kotlin primitive, map, list, or null.
         */
        fun jsonValue(value: Any?): Any? = when (value) {
            JSONObject.NULL -> null
            is JSONObject -> jsonObjectMap(value)
            is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
            is String, is Number, is Boolean -> value
            else -> error("Invalid WebAuthn response value")
        }
    }
}
