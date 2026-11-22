package com.olive.app.security

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasskeyPrfProtocolTest {
    @Test
    fun `server assertion binds each credential to its exact PRF input`() {
        val input = ByteArray(32) { 5 }
        val request = JSONObject(PasskeyPrfProtocol.assertionRequest(
            mapOf(
                "challenge" to "Y2hhbGxlbmdl",
                "rpId" to "yourolivebranch.ca",
                "userVerification" to "required",
            ),
            mapOf("credential_abcdefghijklmnop" to Base64.getEncoder().encodeToString(input)),
        ))

        val values = request.getJSONObject("extensions").getJSONObject("prf")
            .getJSONObject("evalByCredential").getJSONObject("credential_abcdefghijklmnop")
        assertArrayEquals(input, decode(values.getString("first")))
        assertEquals("required", request.getString("userVerification"))
    }

    @Test
    fun `assertion result keeps PRF bytes local and strips them from server JSON`() {
        val expected = ByteArray(32) { 7 }
        val response = JSONObject()
            .put("id", "credential_abcdefghijklmnop")
            .put("clientExtensionResults", JSONObject().put("prf", JSONObject()
                .put("enabled", true)
                .put("results", JSONObject().put("first", encode(expected)))))
            .toString()

        val result = PasskeyPrfProtocol.assertionResult(response)

        assertEquals("credential_abcdefghijklmnop", result.credentialId)
        assertArrayEquals(expected, result.prfOutput)
        assertFalse(JSONObject(result.serverResponseJson).getJSONObject("clientExtensionResults")
            .getJSONObject("prf").has("results"))
    }

    @Test
    fun `registration response cannot forward unexpected PRF results`() {
        val extensions = JSONObject().put("prf", JSONObject()
            .put("enabled", true)
            .put("results", JSONObject().put("first", encode(ByteArray(32) { 8 }))))
        val response = JSONObject()
            .put("id", "credential_abcdefghijklmnop")
            .put("clientExtensionResults", extensions)
            .toString()

        val sanitized = JSONObject(PasskeyPrfProtocol.registrationResult(response))

        assertFalse(sanitized.getJSONObject("clientExtensionResults")
            .getJSONObject("prf").has("results"))
    }

    @Test
    fun `PRF response accepts only 32-byte base64url across malformed inputs`() {
        val malformed = listOf("", "!", encode(ByteArray(31)), encode(ByteArray(33)))
        for (value in malformed) {
            val response = JSONObject()
                .put("id", "credential_abcdefghijklmnop")
                .put("clientExtensionResults", JSONObject().put("prf", JSONObject()
                    .put("results", JSONObject().put("first", value))))
                .toString()
            assertTrue(runCatching { PasskeyPrfProtocol.assertionResult(response) }.isFailure)
        }
    }

    @Test
    fun `invalid options and identities are rejected under repetition`() {
        repeat(100) { iteration ->
            assertTrue(runCatching {
                PasskeyPrfProtocol.assertionRequest(
                    mapOf("challenge" to "Y2hhbGxlbmdl", "rpId" to "yourolivebranch.ca"),
                    mapOf("bad/$iteration" to Base64.getEncoder().encodeToString(ByteArray(32))),
                )
            }.isFailure)
        }
        assertTrue(runCatching {
            PasskeyPrfProtocol.assertionRequest(
                mapOf("challenge" to "Y2hhbGxlbmdl", "rpId" to "other.example"),
                mapOf("credential_abcdefghijklmnop" to Base64.getEncoder().encodeToString(ByteArray(32))),
            )
        }.isFailure)
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
