package com.olive.app.data

import com.olive.app.data.messaging.VoipApiException
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipManager
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.VoipState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

class VoipManagerTest {
    // Checks the start call sends every invitee and uses bearer token step with the test's sample data.
    @Test
    fun startCallSendsEveryInviteeAndUsesBearerToken() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = managerFor(
            responseBody = CALLRESPONSE,
            requests = requests,
            token = "user-token"
        )

        val call = manager.startCall("  Support  ", "caller-1", listOf("user-2", "user-3"))

        assertEquals("call-1", call.callId)
        assertEquals("Support", call.name)
        assertEquals(listOf("user-2", "user-3"), call.participants.drop(1).map { it.userId })
        assertEquals("Bearer user-token", requests.single().header("Authorization"))
        val requestBody = JSONObject(Buffer().also { requests.single().body?.writeTo(it) }.readUtf8())
        assertEquals("Support", requestBody.getString("name"))
        assertEquals("caller-1", requestBody.getString("callerId"))
        assertEquals(listOf("user-2", "user-3"), requestBody.getJSONArray("participantIds")
            .let { array -> (0 until array.length()).map(array::getString) })
    }

    // Checks the incoming calls can be observed and accepted step with the test's sample data.
    @Test
    fun incomingCallsCanBeObservedAndAccepted() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = managerFor(
            responseBody = INVITATIONRESPONSE,
            requests = requests,
            acceptResponseBody = CALLRESPONSE
        )

        val invitations = manager.receiveCalls("user 2", pollingIntervalMillis = 1).first()
        assertEquals("call-1", invitations.single().callId)
        assertEquals("caller-1", invitations.single().callerId)
        assertEquals(VoipState.Ringing("call-1", "Support", "caller-1"), manager.state.value)

        val accepted = manager.acceptCall("call-1", "user 2")
        assertEquals(VoipParticipantStatus.ACCEPTED, accepted.participants[1].status)
        assertEquals("/api/calls/call-1/accept", requests.last().url.encodedPath)
        assertEquals("user 2", requests.first().url.queryParameter("userId"))
    }

    // Checks the api errors are propagated with their status and code step with the test's sample data.
    @Test
    fun apiErrorsArePropagatedWithTheirStatusAndCode() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.request().jsonResponse(
                    code = 401,
                    body = """{"error":{"code":"unauthorized","message":"Sign in required"}}"""
                )
            }
            .build()
        val manager = VoipManager(apiBaseUrl = "https://voip.test", httpClient = client)

        val error = assertThrows(VoipApiException::class.java) {
            runBlocking { manager.getIncomingCalls("user-1") }
        }

        assertEquals(401, error.statusCode)
        assertEquals("unauthorized", error.errorCode)
        assertEquals("Sign in required", error.message)
    }

    // Checks the server error without json uses status fallback step with the test's sample data.
    @Test
    fun serverErrorWithoutJsonUsesStatusFallback() {
        val manager = managerForResponse(code = 503, body = "maintenance")

        val error = assertThrows(VoipApiException::class.java) {
            runBlocking { manager.getIncomingCalls("user-1") }
        }

        assertEquals(503, error.statusCode)
        assertEquals(null, error.errorCode)
        assertTrue(error.message!!.contains("503"))
    }

    // Checks the network failures are not converted to successful responses step with the test's sample data.
    @Test
    fun networkFailuresAreNotConvertedToSuccessfulResponses() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("Connection refused") }
            .build()
        val manager = VoipManager(apiBaseUrl = "https://voip.test", httpClient = client)

        val error = assertThrows(IOException::class.java) {
            runBlocking { manager.getIncomingCalls("user-1") }
        }

        assertEquals("Connection refused", error.message)
    }

    // Checks the malformed successful payload is reported step with the test's sample data.
    @Test
    fun malformedSuccessfulPayloadIsReported() {
        val manager = managerForResponse(body = """{"invitations":""")

        assertThrows(Exception::class.java) {
            runBlocking { manager.getIncomingCalls("user-1") }
        }
    }

    // Checks the unknown participant status is reported instead of accepted as valid step with the test's sample data.
    @Test
    fun unknownParticipantStatusIsReportedInsteadOfAcceptedAsValid() {
        val unknownStatus = """{"invitations":[{"callId":"call-1","callerId":"caller-1","name":"Support","status":"maybe"}]}"""
        val manager = managerForResponse(body = unknownStatus)

        val error = assertThrows(IOException::class.java) {
            runBlocking { manager.getIncomingCalls("user-1") }
        }

        assertTrue(error.message!!.contains("unknown participant status"))
    }

    // Checks the declining invitation uses decline endpoint and body step with the test's sample data.
    @Test
    fun decliningInvitationUsesDeclineEndpointAndBody() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = managerFor(responseBody = CALLRESPONSE, requests = requests)

        manager.declineCall("call/with space", "user-2")

        assertEquals("/api/calls/call%2Fwith%20space/decline", requests.single().url.encodedPath)
        val body = JSONObject(Buffer().also { requests.single().body?.writeTo(it) }.readUtf8())
        assertEquals("user-2", body.getString("userId"))
    }

    // Checks the invalid call and polling inputs are rejected before network access step with the test's sample data.
    @Test
    fun invalidCallAndPollingInputsAreRejectedBeforeNetworkAccess() {
        val requests = mutableListOf<Request>()
        val manager = managerFor(responseBody = CALLRESPONSE, requests = requests)
        val invalidCalls = listOf(
            Triple("", "caller-1", listOf("user-2")),
            Triple("Support", "", listOf("user-2")),
            Triple("Support", "caller-1", emptyList()),
            Triple("Support", "caller-1", listOf(" ")),
            Triple("Support", "caller-1", listOf("user-2", "user-2")),
            Triple("Support", "caller-1", listOf("caller-1"))
        )

        invalidCalls.forEach { (name, callerId, participants) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { manager.startCall(name, callerId, participants) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { manager.receiveCalls(" ", 1) }
        assertThrows(IllegalArgumentException::class.java) { manager.receiveCalls("user-1", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            VoipManager(apiBaseUrl = "http://insecure.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoipManager(apiBaseUrl = "http://10.0.2.2.attacker.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoipManager(apiBaseUrl = "https://")
        }
        VoipManager(apiBaseUrl = "http://10.0.2.2:3000")
        assertEquals(emptyList<Request>(), requests)
    }

    // Checks the polling clears ringing state when invitations disappear step with the test's sample data.
    @Test
    fun pollingClearsRingingStateWhenInvitationsDisappear() = runBlocking {
        var requestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount += 1
                val body = if (requestCount == 1) INVITATIONRESPONSE else """{"invitations":[]}"""
                chain.request().jsonResponse(body = body)
            }
            .build()
        val manager = VoipManager(apiBaseUrl = "https://voip.test", httpClient = client)

        val results = manager.receiveCalls("user-2", pollingIntervalMillis = 1)
            .take(2)
            .toList()

        assertEquals(2, results.size)
        assertEquals(1, results.first().size)
        assertEquals(emptyList<VoipInvitation>(), results.last())
        assertEquals(VoipState.Idle, manager.state.value)
    }

    // Checks the manager for step with the test's sample data.
    private fun managerFor(
        responseBody: String,
        requests: MutableList<Request>,
        token: String? = null,
        acceptResponseBody: String = responseBody
    ): VoipManager {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                val response = when {
                    request.url.encodedPath == "/api/calls/invitations" ->
                        request.jsonResponse(body = INVITATIONRESPONSE)
                    request.url.encodedPath.endsWith("/accept") ->
                        request.jsonResponse(body = acceptResponseBody)
                    else -> request.jsonResponse(body = responseBody)
                }
                response
            })
            .build()
        return VoipManager(
            apiBaseUrl = "https://voip.test",
            httpClient = client,
            bearerTokenProvider = { token }
        )
    }

    // Checks the manager for response step with the test's sample data.
    private fun managerForResponse(
        code: Int = 200,
        body: String
    ): VoipManager {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.request().jsonResponse(code = code, body = body) }
            .build()
        return VoipManager(apiBaseUrl = "https://voip.test", httpClient = client)
    }

    // Builds a fake server response so each VoIP test can control the returned JSON.
    private fun Request.jsonResponse(code: Int = 200, body: String): Response =
        Response.Builder()
            .request(this)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Unauthorized")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    companion object {
        private const val CALLRESPONSE =
            """{"callId":"call-1","channelId":42,"name":"Support","callerId":"caller-1","participants":[{"userId":"caller-1","status":"accepted"},{"userId":"user-2","status":"accepted"},{"userId":"user-3","status":"pending"}]}"""
        private const val INVITATIONRESPONSE =
            """{"invitations":[{"callId":"call-1","callerId":"caller-1","name":"Support","status":"pending"}]}"""
    }
}
