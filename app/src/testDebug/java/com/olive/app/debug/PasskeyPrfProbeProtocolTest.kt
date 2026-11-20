package com.olive.app.debug

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasskeyPrfProbeProtocolTest {
    @Test
    fun creationRequestBindsTheApprovedRpAndPrfInput() {
        val challenge = ByteArray(32) { 1 }
        val userId = ByteArray(16) { 2 }
        val input = ByteArray(32) { 3 }

        val request = JSONObject(PasskeyPrfProbeProtocol.creationRequest(challenge, userId, input))

        assertEquals("yourolivebranch.ca", request.getJSONObject("rp").getString("id"))
        assertEquals("required", request.getJSONObject("authenticatorSelection").getString("userVerification"))
        assertArrayEquals(input, decode(request.getJSONObject("extensions")
            .getJSONObject("prf").getJSONObject("eval").getString("first")))
    }

    @Test
    fun assertionRequestBindsOneCredentialToTheExactPrfInput() {
        val challenge = ByteArray(32) { 4 }
        val input = ByteArray(32) { 5 }
        val request = JSONObject(PasskeyPrfProbeProtocol.assertionRequest(
            challenge, "credential_abcdefghijklmnop", input,
        ))

        val values = request.getJSONObject("extensions").getJSONObject("prf")
            .getJSONObject("evalByCredential").getJSONObject("credential_abcdefghijklmnop")
        assertArrayEquals(input, decode(values.getString("first")))
        assertEquals("required", request.getString("userVerification"))
    }

    @Test
    fun prfResponseAcceptsOnlyA32ByteBase64UrlValueAcrossMalformedInputs() {
        val expected = ByteArray(32) { 7 }
        val supported = JSONObject().put("clientExtensionResults", JSONObject().put("prf", JSONObject()
            .put("results", JSONObject().put("first", encode(expected))))).toString()
        assertArrayEquals(expected, PasskeyPrfProbeProtocol.prfOutputOrNull(supported))

        val malformed = listOf("", "!", encode(ByteArray(31)), encode(ByteArray(33)))
        for (value in malformed) {
            val response = JSONObject().put("clientExtensionResults", JSONObject().put("prf", JSONObject()
                .put("results", JSONObject().put("first", value)))).toString()
            assertNull(PasskeyPrfProbeProtocol.prfOutputOrNull(response))
        }
    }

    @Test
    fun invalidInputLengthsAreRejectedInsteadOfCreatingBrokenWebAuthnRequests() {
        assertTrue(runCatching {
            PasskeyPrfProbeProtocol.creationRequest(ByteArray(31), ByteArray(16), ByteArray(32))
        }.isFailure)
        assertTrue(runCatching {
            PasskeyPrfProbeProtocol.assertionRequest(ByteArray(32), "", ByteArray(32))
        }.isFailure)
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
