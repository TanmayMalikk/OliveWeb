package com.olive.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class VoipParticipantStatus {
    PENDING,
    ACCEPTED,
    DECLINED
}

data class VoipParticipant(
    val userId: String,
    val status: VoipParticipantStatus
)

data class VoipCall(
    val callId: String,
    val channelId: Int,
    val name: String,
    val callerId: String,
    val participants: List<VoipParticipant>
)

// VoipInvitation is a class that is responsible for accepting calls.
// When a user is 'dialed' the server will ping that user to make a VoipInvitation object to handle connecting to the call.
data class VoipInvitation(
    val callId: String,
    val callerId: String,
    val name: String,
    val status: VoipParticipantStatus
)

// A interface that measures the status of the voip call.
sealed interface VoipState {
    data object Idle : VoipState
    data class Ringing(val callId: String, val callName: String, val callerId: String = "") : VoipState
    data class Connecting(val callId: String) : VoipState
    data class Active(val callId: String, val participantCount: Int) : VoipState
    data class Error(val message: String) : VoipState
}

class VoipApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String
) : IOException(message)

class VoipManager(
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val bearerTokenProvider: () -> String? = { null }
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mutableState = MutableStateFlow<VoipState>(VoipState.Idle)

    val state: StateFlow<VoipState> = mutableState.asStateFlow()

    //double check this code when Live, might need to be changed to connnect to actual server.
    init {
        val apiUri = runCatching { URI(baseUrl) }.getOrNull()
        val isHttps = apiUri?.scheme.equals("https", ignoreCase = true)
        val isEmulatorLoopback = apiUri?.scheme.equals("http", ignoreCase = true) &&
            apiUri?.host.equals("10.0.2.2", ignoreCase = true)
        require(
            apiUri?.isAbsolute == true &&
                apiUri.host != null &&
                apiUri.userInfo == null &&
                apiUri.query == null &&
                apiUri.fragment == null &&
                (isHttps || isEmulatorLoopback)
        ) {
            "VoIP API base URL must use HTTPS (or the Android emulator loopback host)"
        }
    }

    suspend fun startCall(
        name: String,
        callerId: String,
        participantIds: List<String>
    ): VoipCall {
        require(name.isNotBlank()) { "Call name must not be blank" }
        require(callerId.isNotBlank()) { "Caller ID must not be blank" }
        require(participantIds.isNotEmpty()) { "At least one participant is required" }
        require(participantIds.all(String::isNotBlank)) { "Participant IDs must not be blank" }
        require(participantIds.distinct().size == participantIds.size) { "Participant IDs must be unique" }
        require(callerId !in participantIds) { "Caller cannot be a participant" }

        val body = JSONObject()
            .put("name", name.trim())
            .put("callerId", callerId)
            .put("participantIds", JSONArray(participantIds))
        return parseCall(request("POST", "/api/calls", body))
    }

    suspend fun listCalls(): List<VoipCall> {
        val response = request("GET", "/api/calls")
        return response.getJSONArray("calls").toCallList()
    }

    suspend fun getIncomingCalls(userId: String): List<VoipInvitation> {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val encodedUserId = URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
        val response = request("GET", "/api/calls/invitations?userId=$encodedUserId")
        return response.getJSONArray("invitations").toInvitationList()
    }

    fun receiveCalls(
        userId: String,
        pollingIntervalMillis: Long = DEFAULT_POLLING_INTERVAL_MILLIS
    ): Flow<List<VoipInvitation>> {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        require(pollingIntervalMillis > 0) { "Polling interval must be greater than zero" }

        return flow {
            while (currentCoroutineContext().isActive) {
                val invitations = getIncomingCalls(userId)
                mutableState.value = invitations.firstOrNull()?.let {
                    VoipState.Ringing(it.callId, it.name, it.callerId)
                } ?: VoipState.Idle
                emit(invitations)
                delay(pollingIntervalMillis)
            }
        }.distinctUntilChanged()
    }

    suspend fun acceptCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "accept")

    suspend fun declineCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "decline")

    suspend fun endCall(callId: String) {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        request("DELETE", "/api/calls/${encodePathSegment(callId)}")
    }

    private suspend fun respondToInvitation(
        callId: String,
        userId: String,
        response: String
    ): VoipCall {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val body = JSONObject().put("userId", userId)
        return parseCall(request("POST", "/api/calls/${encodePathSegment(callId)}/$response", body))
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url("$baseUrl$path")
            bearerTokenProvider()?.takeIf(String::isNotBlank)?.let {
                requestBuilder.header("Authorization", "Bearer $it")
            }
            if (body != null) {
                requestBuilder.method(method, body.toString().toRequestBody(jsonMediaType))
            } else {
                requestBuilder.method(method, null)
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(responseBody).optJSONObject("error") }.getOrNull()
                    throw VoipApiException(
                        statusCode = response.code,
                        errorCode = error?.optString("code")?.takeIf(String::isNotBlank),
                        message = error?.optString("message")?.takeIf(String::isNotBlank)
                            ?: "VoIP API request failed with HTTP ${response.code}"
                    )
                }

                try {
                    JSONObject(responseBody)
                } catch (error: Exception) {
                    throw IOException("VoIP API returned an invalid JSON response", error)
                }
            }
        }

    private fun parseCall(json: JSONObject): VoipCall =
        VoipCall(
            callId = json.getString("callId"),
            channelId = json.getInt("channelId"),
            name = json.getString("name"),
            callerId = json.getString("callerId"),
            participants = json.getJSONArray("participants").toParticipantList()
        )

    private fun JSONArray.toCallList(): List<VoipCall> =
        (0 until length()).map { parseCall(getJSONObject(it)) }

    private fun JSONArray.toInvitationList(): List<VoipInvitation> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                VoipInvitation(
                    callId = it.getString("callId"),
                    callerId = it.getString("callerId"),
                    name = it.getString("name"),
                    status = parseParticipantStatus(it.getString("status"))
                )
            }
        }

    private fun JSONArray.toParticipantList(): List<VoipParticipant> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                VoipParticipant(
                    userId = it.getString("userId"),
                    status = parseParticipantStatus(it.getString("status"))
                )
            }
        }

    private fun parseParticipantStatus(value: String): VoipParticipantStatus =
        try {
            VoipParticipantStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw IOException("VoIP API returned an unknown participant status", error)
        }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_API_BASE_URL = "https://mumble.yourolivebranch.ca"
        const val DEFAULT_POLLING_INTERVAL_MILLIS = 5_000L
    }
}
