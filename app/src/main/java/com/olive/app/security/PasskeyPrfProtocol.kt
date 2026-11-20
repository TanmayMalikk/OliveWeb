package com.olive.app.security

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

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
