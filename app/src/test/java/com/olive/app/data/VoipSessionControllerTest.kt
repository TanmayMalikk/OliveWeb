package com.olive.app.data

import com.olive.app.data.messaging.VoipApiException
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipManager
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.MumbleAudioSession
import com.olive.app.data.messaging.MumbleConnectionDetails
import com.olive.app.data.messaging.VoipSessionController
import com.olive.app.data.messaging.VoipSessionState
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("SameParameterValue", "SameParameterValue")
class VoipSessionControllerTest {
    // Checks the start join mute and leave keeps group call open step with the test's sample data.
    @Test
    fun startJoinMuteAndLeaveKeepsGroupCallOpen() = runBlocking {
        val requests = mutableListOf<Request>()
        val audio = FakeMumbleAudioSession()
        val controller = VoipSessionController(apiManager(requests), audio)

        val call = controller.startAndJoin(
            name = "Support",
            callerId = "caller-1",
            participantIds = listOf("user-2"),
            connection = connection
        )

        assertEquals("call-1", call.callId)
        assertEquals(listOf(call to connection), audio.connections)
        assertEquals(
            VoipSessionState.Active(call, microphoneMuted = false),
            controller.state.value
        )

        controller.setMicrophoneMuted(true)
        assertEquals(listOf(true), audio.muteChanges)
        assertEquals(VoipSessionState.Active(call, microphoneMuted = true), controller.state.value)

        controller.leaveCall()
        assertEquals(1, audio.disconnectCount)
        assertEquals(VoipSessionState.Idle, controller.state.value)
        assertEquals(listOf("/api/calls"), requests.map { it.url.encodedPath })
    }

    // Checks the accept join and end ends call on api step with the test's sample data.
    @Test
    fun acceptJoinAndEndEndsCallOnApi() = runBlocking {
        val requests = mutableListOf<Request>()
        val audio = FakeMumbleAudioSession()
        val controller = VoipSessionController(apiManager(requests), audio)

        controller.acceptAndJoin("call-1", "user-2", connection)
        controller.endCall()

        assertEquals(
            listOf(
                "/api/calls/call-1/accept",
                "/api/calls/call-1"
            ),
            requests.map { it.url.encodedPath }
        )
        assertEquals(1, audio.disconnectCount)
        assertEquals(VoipSessionState.Idle, controller.state.value)
    }

    // Checks the audio connection failure is visible and can be retried step with the test's sample data.
    @Test
    fun audioConnectionFailureIsVisibleAndCanBeRetried() = runBlocking {
        val audio = FakeMumbleAudioSession(connectFailure = IllegalStateException("Audio unavailable"))
        val controller = VoipSessionController(apiManager(mutableListOf()), audio)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)
            }
        }

        assertEquals("Audio unavailable", failure.message)
        assertEquals(
            VoipSessionState.Error(
                call = call,
                message = "Audio unavailable"
            ),
            controller.state.value
        )

        audio.connectFailure = null
        controller.joinCall(call, connection)
        assertEquals(VoipSessionState.Active(call, microphoneMuted = false), controller.state.value)
    }

    // Checks the mumble connection details reject invalid endpoint step with the test's sample data.
    @Test
    fun mumbleConnectionDetailsRejectInvalidEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            MumbleConnectionDetails(host = "", port = 64738, username = "olive-user")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MumbleConnectionDetails(host = "voice.example.test", port = 0, username = "olive-user")
        }
    }

    // Checks the cannot start second call while one is active step with the test's sample data.
    @Test
    fun cannotStartSecondCallWhileOneIsActive() = runBlocking {
        val requests = mutableListOf<Request>()
        val controller = VoipSessionController(apiManager(requests), FakeMumbleAudioSession())
        controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                controller.startAndJoin("Other", "caller-1", listOf("user-3"), connection)
            }
        }

        assertEquals(listOf("/api/calls"), requests.map { it.url.encodedPath })
    }

    // Checks the microphone failure is visible and session can still be left step with the test's sample data.
    @Test
    fun microphoneFailureIsVisibleAndSessionCanStillBeLeft() = runBlocking {
        val audio = FakeMumbleAudioSession(muteFailure = IllegalStateException("Microphone unavailable"))
        val controller = VoipSessionController(apiManager(mutableListOf()), audio)
        val call = controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.setMicrophoneMuted(true) }
        }

        assertEquals("Microphone unavailable", failure.message)
        assertEquals(VoipSessionState.Error(call, "Microphone unavailable"), controller.state.value)

        audio.muteFailure = null
        controller.leaveCall()
        assertEquals(1, audio.disconnectCount)
        assertEquals(VoipSessionState.Idle, controller.state.value)
    }

    // Checks the disconnect failure is visible and can be retried step with the test's sample data.
    @Test
    fun disconnectFailureIsVisibleAndCanBeRetried() = runBlocking {
        val audio = FakeMumbleAudioSession(disconnectFailure = IllegalStateException("Socket close failed"))
        val controller = VoipSessionController(apiManager(mutableListOf()), audio)
        val call = controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.leaveCall() }
        }
        assertEquals("Socket close failed", failure.message)
        assertEquals(VoipSessionState.Error(call, "Socket close failed"), controller.state.value)

        audio.disconnectFailure = null
        controller.leaveCall()
        assertEquals(2, audio.disconnectCount)
        assertEquals(VoipSessionState.Idle, controller.state.value)
    }

    // Checks the failed call creation leaves controller idle step with the test's sample data.
    @Test
    fun failedCallCreationLeavesControllerIdle() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.request().jsonResponse(
                    code = 503,
                    body = """{"error":{"code":"unavailable","message":"Call service unavailable"}}"""
                )
            }
            .build()
        val controller = VoipSessionController(
            VoipManager(apiBaseUrl = "https://voip.test", httpClient = client),
            FakeMumbleAudioSession()
        )

        val failure = assertThrows(VoipApiException::class.java) {
            runBlocking {
                controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)
            }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(VoipSessionState.Idle, controller.state.value)
    }

    // Checks the failed end request is visible and does not report call ended step with the test's sample data.
    @Test
    fun failedEndRequestIsVisibleAndDoesNotReportCallEnded() = runBlocking {
        val requests = mutableListOf<Request>()
        val audio = FakeMumbleAudioSession()
        val controller = VoipSessionController(apiManager(requests, deleteStatus = 503), audio)
        val call = controller.startAndJoin("Support", "caller-1", listOf("user-2"), connection)

        val failure = assertThrows(VoipApiException::class.java) {
            runBlocking { controller.endCall() }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(1, audio.disconnectCount)
        assertEquals(
            VoipSessionState.Error(call, "Call service unavailable"),
            controller.state.value
        )
    }

    // Checks the api manager step with the test's sample data.
    private fun apiManager(
        requests: MutableList<Request>,
        deleteStatus: Int = 200
    ): VoipManager {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                val responseCode = if (request.method == "DELETE") deleteStatus else 200
                val responseBody = when (request.method) {
                    "DELETE" if deleteStatus != 200 -> {
                        """{"error":{"code":"unavailable","message":"Call service unavailable"}}"""
                    }
                    "DELETE" -> {
                        """{"deleted":true,"callId":"call-1"}"""
                    }
                    else -> {
                        CALLRESPONSE
                    }
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (request.method == "POST" && request.url.encodedPath == "/api/calls") 201 else responseCode)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return VoipManager(apiBaseUrl = "https://voip.test", httpClient = client)
    }

    // Builds a fake server response so the session test can check success and error cases.
    private fun Request.jsonResponse(code: Int, body: String): Response =
        Response.Builder()
            .request(this)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Service Unavailable")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private class FakeMumbleAudioSession(
        var connectFailure: Exception? = null,
        var muteFailure: Exception? = null,
        var disconnectFailure: Exception? = null
    ) : MumbleAudioSession {
        val connections = mutableListOf<Pair<VoipCall, MumbleConnectionDetails>>()
        val muteChanges = mutableListOf<Boolean>()
        var disconnectCount = 0

        // Checks the connect step with the test's sample data.
        override suspend fun connect(call: VoipCall, details: MumbleConnectionDetails) {
            connectFailure?.let { throw it }
            connections += call to details
        }

        // Checks the set microphone muted step with the test's sample data.
        override suspend fun setMicrophoneMuted(muted: Boolean) {
            muteFailure?.let { throw it }
            muteChanges += muted
        }

        // Checks the disconnect step with the test's sample data.
        override suspend fun disconnect() {
            disconnectCount += 1
            disconnectFailure?.let { throw it }
        }
    }

    companion object {
        private val connection = MumbleConnectionDetails(
            host = "voice.example.test",
            port = 64738,
            username = "olive-user"
        )
        private val call = VoipCall(
            callId = "call-1",
            channelId = 42,
            name = "Support",
            callerId = "caller-1",
            participants = listOf(
                VoipParticipant("caller-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("user-2", VoipParticipantStatus.PENDING)
            )
        )
        private const val CALLRESPONSE =
            """{"callId":"call-1","channelId":42,"name":"Support","callerId":"caller-1","participants":[{"userId":"caller-1","status":"accepted"},{"userId":"user-2","status":"pending"}]}"""
    }
}
