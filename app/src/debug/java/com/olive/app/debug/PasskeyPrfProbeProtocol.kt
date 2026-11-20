// Debug builds only: isolates WebAuthn JSON and PRF-result validation from the probe Activity.
package com.olive.app.debug

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Builds the two WebAuthn requests and validates the only secret-derived response used by the
 * compatibility probe. It deliberately has no Android UI, Firebase, storage, or logging duties.
 */
internal object PasskeyPrfProbeProtocol {
    const val RP_ID = "yourolivebranch.ca"

    fun creationRequest(challenge: ByteArray, userId: ByteArray, prfInput: ByteArray): String {
        requireLength(challenge, 32, "Create challenge")
        requireLength(userId, 16, "Probe user ID")
        requireLength(prfInput, 32, "PRF input")
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
            put("extensions", JSONObject().put("prf", JSONObject().put("eval", JSONObject()
                .put("first", base64Url(prfInput)))))
        }.toString()
    }

    fun assertionRequest(challenge: ByteArray, credentialId: String, prfInput: ByteArray): String {
        requireLength(challenge, 32, "Assertion challenge")
        require(credentialId.matches(Regex("[A-Za-z0-9_-]{16,1024}"))) { "Invalid passkey credential ID" }
        requireLength(prfInput, 32, "PRF input")
        return JSONObject().apply {
            put("challenge", base64Url(challenge))
            put("rpId", RP_ID)
            put("allowCredentials", JSONArray().put(JSONObject()
                .put("type", "public-key")
                .put("id", credentialId)))
            put("userVerification", "required")
            put("extensions", JSONObject().put("prf", JSONObject().put("evalByCredential", JSONObject()
                .put(credentialId, JSONObject().put("first", base64Url(prfInput))))))
        }.toString()
    }

    /** Returns a short-lived PRF result only when it has exactly the WebAuthn-required 32 bytes. */
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
        return bytes.takeIf { it.size == 32 } ?: run {
            bytes.fill(0)
            null
        }
    }

    private fun requireLength(value: ByteArray, expected: Int, name: String) {
        require(value.size == expected) { "$name must contain $expected bytes" }
    }

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
