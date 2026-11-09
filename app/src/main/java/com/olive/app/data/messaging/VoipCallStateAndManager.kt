package com.olive.app.data.messaging

import com.olive.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.data.AccountRole
import java.util.UUID


enum class VoipParticipantStatus {
    PENDING,
    ACCEPTED,
    DECLINED
}

enum class VoipCallStatus { ACTIVE, ENDED, DECLINED, MISSED }

data class VoipParticipant(
    val userId: String,
    val status: VoipParticipantStatus
)

data class VoipCall(
    val callId: String,
    val name: String,
    val callerId: String,
    val participants: List<VoipParticipant>,
    val callerName: String = "",
    val undeliveredInviteeIds: List<String> = emptyList(),
    val conversationId: String = "",
    val status: VoipCallStatus = VoipCallStatus.ACTIVE,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val acceptedAt: Long = 0L,
    val endedAt: Long = 0L,
    val endReason: String = "",
)

fun VoipCall.connectedParticipantCount(): Int =
    participants.count { it.status == VoipParticipantStatus.ACCEPTED }

fun VoipCall.canBeRejoinedBy(userId: String): Boolean =
    status == VoipCallStatus.ACTIVE &&
        connectedParticipantCount() >= 2 &&
        participants.any { it.userId == userId && it.status == VoipParticipantStatus.DECLINED }

// Lets an invited chat member enter an active call from its chat log.
// Pending members accept the invitation, while disconnected members rejoin an established room.
fun VoipCall.canBeJoinedBy(userId: String): Boolean {
    if (status != VoipCallStatus.ACTIVE) return false
    return when (participants.firstOrNull { it.userId == userId }?.status) {
        VoipParticipantStatus.PENDING, VoipParticipantStatus.ACCEPTED -> true
        VoipParticipantStatus.DECLINED -> connectedParticipantCount() >= 2
        null -> false
    }
}

internal fun VoipCall.otherPartyName(currentUid: String): String =
    if (callerId == currentUid) name else callerName.ifBlank { name }

// VoipInvitation is a class that is responsible for accepting calls.
// When a user is 'dialed' the server will ping that user to make a VoipInvitation object to handle connecting to the call.
data class VoipInvitation(
    val callId: String,
    val callerId: String,
    val name: String,
    val status: VoipParticipantStatus,
    val expiresAt: Long = 0L,
)

// An interface that measures the status of the voip call.
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

internal fun isVoipApiConfigured(apiBaseUrl: String = BuildConfig.VOIP_API_BASE_URL): Boolean =
    apiBaseUrl.isNotBlank()

class VoipManager(
    private val apiBaseUrl: String = BuildConfig.VOIP_API_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val bearerTokenProvider: suspend () -> String? = {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    },
    private val refreshedBearerTokenProvider: suspend () -> String? = {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()?.token
    },
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mutableState = MutableStateFlow<VoipState>(VoipState.Idle)

    val state: StateFlow<VoipState> = mutableState.asStateFlow()

    //double check this code when Live, might need to be changed to connect to actual server.
    init {
        // A missing optional VoIP URL disables calls without stopping the rest of Olive from opening.
        if (baseUrl.isNotBlank()) {
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
                "Configure VOIP_API_BASE_URL with HTTPS (or the Android emulator loopback host)"
            }
        }
    }

    // Creates a new call record and invites each selected participant.
    suspend fun startCall(
        name: String,
        callerId: String,
        participantIds: List<String>,
        callerName: String = name,
        conversationId: String,
    ): VoipCall {
        // Stops incomplete or conflicting call details from reaching the server.
        require(name.isNotBlank()) { "Call name must not be blank" }
        require(callerId.isNotBlank()) { "Caller ID must not be blank" }
        require(callerName.isNotBlank()) { "Caller name must not be blank" }
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank" }
        require(participantIds.isNotEmpty()) { "At least one participant is required" }
        require(participantIds.all(String::isNotBlank)) { "Participant IDs must not be blank" }
        require(participantIds.distinct().size == participantIds.size) { "Participant IDs must be unique" }
        require(callerId !in participantIds) { "Caller cannot be a participant" }

        // Builds the JSON body expected by the call-creation endpoint.
        val body = JSONObject()
            .put("name", name.trim())
            .put("callerId", callerId)
            .put("callerName", callerName.trim())
            .put("conversationId", conversationId)
            .put("participantIds", JSONArray(participantIds))
        return parseCall(request("POST", "/api/calls", body))
    }

    suspend fun getLiveKitConnectionDetails(callId: String): LiveKitConnectionDetails {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        val response = request(
            "POST",
            "/calls/token",
            JSONObject().put("callId", callId)
        )
        return LiveKitConnectionDetails(
            serverUrl = response.getString("serverUrl"),
            token = response.getString("token"),
            roomName = response.getString("roomName")
        )
    }

    // Loads every call record currently visible to the signed-in client.
    suspend fun listCalls(): List<VoipCall> {
        val response = request("GET", "/api/calls")
        return response.getJSONArray("calls").toCallList()
    }

    // Loads the unanswered call invitations for one user.
    suspend fun getIncomingCalls(userId: String): List<VoipInvitation> {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val encodedUserId = withContext(Dispatchers.IO) {
            URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
        }
        val response = request("GET", "/api/calls/invitations?userId=$encodedUserId")
        return response.getJSONArray("invitations").toInvitationList()
    }

    // Polls the server and updates the ringing state until the collector stops.
    fun receiveCalls(
        userId: String,
        pollingIntervalMillis: Long = DEFAULT_POLLING_INTERVAL_MILLIS
    ): Flow<List<VoipInvitation>> {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        require(pollingIntervalMillis > 0) { "Polling interval must be greater than zero" }

        // Repeats the invitation check at the requested interval while this flow is active.
        return flow {
            // Messaging: Keeps receive calls active while current coroutine context is active.
            while (currentCoroutineContext().isActive) {
                val invitations = getIncomingCalls(userId)
                mutableState.value = invitations.firstOrNull()?.let {
                    VoipState.Ringing(it.callId, it.name, it.callerId)
                } ?: VoipState.Idle
                emit(invitations)
                delay(pollingIntervalMillis.milliseconds)
            }
        }.distinctUntilChanged()
    }

    // Accepts the incoming VoIP call and starts its audio session.
    suspend fun acceptCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "accept")

    // Declines the incoming VoIP call and closes its session.
    suspend fun declineCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "decline")

    // Deletes the server call record after the call has ended.
    suspend fun endCall(callId: String) {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        request("POST", "/calls/end", JSONObject().put("callId", callId))
    }

    // Removes this phone from a group call without disconnecting everyone else.
    suspend fun leaveCall(callId: String) {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        request("POST", "/api/calls/${encodePathSegment(callId)}/leave", JSONObject())
    }

    // Restores a previous member while at least two people are still in the group call.
    suspend fun rejoinCall(callId: String): VoipCall {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        return parseCall(
            request("POST", "/api/calls/${encodePathSegment(callId)}/rejoin", JSONObject())
        )
    }

    suspend fun registerPushToken(deviceId: String, token: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        require(token.isNotBlank()) { "Push token must not be blank" }
        request(
            "POST",
            "/notifications/register",
            JSONObject().put("deviceId", deviceId).put("token", token)
        )
    }

    suspend fun unregisterPushToken(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        request("DELETE", "/notifications/register", JSONObject().put("deviceId", deviceId))
    }

    // Sends one user's accept or decline choice to the matching call endpoint.
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

    // Runs one authenticated API request and converts its response into JSON.
    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            check(isVoipApiConfigured(baseUrl)) {
                "Voice calling is unavailable because this build has no VoIP service configured"
            }
            val cachedToken = bearerTokenProvider()?.takeIf(String::isNotBlank)

            // A rejected cached Firebase token is refreshed once before showing an authentication error.
            try {
                executeWithDnsRetries(authenticatedRequest(method, path, body, cachedToken))
            } catch (error: VoipApiException) {
                if (error.statusCode != 401) throw error
                val freshToken = refreshedBearerTokenProvider()?.takeIf(String::isNotBlank) ?: throw error
                executeWithDnsRetries(authenticatedRequest(method, path, body, freshToken))
            }
        }

    // Builds one request with the Firebase identity currently available for this signed-in account.
    private fun authenticatedRequest(
        method: String,
        path: String,
        body: JSONObject?,
        bearerToken: String?,
    ): Request {
        val requestBuilder = Request.Builder().url("$baseUrl$path")
        bearerToken?.let { requestBuilder.header("Authorization", "Bearer $it") }
        if (body != null) {
            requestBuilder.method(method, body.toString().toRequestBody(jsonMediaType))
        } else {
            requestBuilder.method(method, null)
        }
        return requestBuilder.build()
    }

    // Retries only DNS lookup failures, then parses the server response or reports its exact error.
    private suspend fun executeWithDnsRetries(request: Request): JSONObject {
            // Unknown-host failures happen before a request reaches the server, so retrying cannot
            // create a duplicate call. This gives Android time to refresh a recently changed DNS entry.
            val attempts = 3
            var lastDnsError: UnknownHostException? = null

            // A phone can briefly keep an old DNS result after the VoIP host is deployed.
            repeat(attempts) { attempt ->
                try {
                    // Converts successful responses and turns server failures into useful app errors.
                    return httpClient.newCall(request).execute().use { response ->
                        val responseBody = response.body.string()
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
                } catch (error: UnknownHostException) {
                    lastDnsError = error
                    if (attempt + 1 < attempts) delay(750L * (attempt + 1))
                }
            }
            throw IOException(
                "The voice service could not be reached. Check the connection and try again.",
                lastDnsError,
            )
    }

    // Converts one call JSON object into the model used by the app.
    private fun parseCall(json: JSONObject): VoipCall =
        VoipCall(
            callId = json.getString("callId"),
            name = json.getString("name"),
            callerId = json.getString("callerId"),
            participants = json.getJSONArray("participants").toParticipantList(),
            callerName = json.optString("callerName"),
            undeliveredInviteeIds = json.optJSONArray("notificationFailures")
                ?.let { failures -> (0 until failures.length()).map(failures::getString) }
                .orEmpty(),
            conversationId = json.optString("conversationId"),
            status = parseCallStatus(json.optString("status", "active")),
            createdAt = json.optLong("createdAt"),
            expiresAt = json.optLong("expiresAt"),
            acceptedAt = json.optLong("acceptedAt"),
            endedAt = json.optLong("endedAt"),
            endReason = json.optString("endReason"),
        )

    // Converts a server array into call models.
    private fun JSONArray.toCallList(): List<VoipCall> =
        (0 until length()).map { parseCall(getJSONObject(it)) }

    // Converts a server array into invitation models for the ringing screen.
    private fun JSONArray.toInvitationList(): List<VoipInvitation> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                VoipInvitation(
                    callId = it.getString("callId"),
                    callerId = it.getString("callerId"),
                    name = it.getString("name"),
                    status = parseParticipantStatus(it.getString("status")),
                    expiresAt = it.optLong("expiresAt"),
                )
            }
        }

    // Converts a server array into the participants and their current responses.
    private fun JSONArray.toParticipantList(): List<VoipParticipant> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                VoipParticipant(
                    userId = it.getString("userId"),
                    status = parseParticipantStatus(it.getString("status"))
                )
            }
        }

    // Validates a server status before exposing it to the rest of the app.
    private fun parseParticipantStatus(value: String): VoipParticipantStatus =
        try {
            VoipParticipantStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw IOException("VoIP API returned an unknown participant status", error)
        }

    private fun parseCallStatus(value: String): VoipCallStatus =
        try {
            VoipCallStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw IOException("VoIP API returned an unknown call status", error)
        }

    // Escapes a call ID so special characters cannot change the request path.
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_POLLING_INTERVAL_MILLIS = 5_000L
    }
}


object VoipPushTokenRegistration {
    private const val PREFERENCES = "voip_push_token"
    private const val TOKEN_KEY = "token"
    private const val DEVICE_ID_KEY = "device_id"
    private const val ENABLED_KEY = "enabled_for_account"
    private const val WORK_NAME = "voip-push-token-registration"
    private const val INPUT_TOKEN = "token"
    private const val INPUT_DEVICE_ID = "device_id"

    suspend fun registerForTokenUpdates(context: Context) {
        FirebaseMessaging.getInstance().register().await()
        val token = FirebaseInstallations.getInstance().id.await()
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null && isEnabled(context)) {
            enqueueRegistration(context, token)
        }
    }

    // Personal accounts can receive calls. Organization accounts remove any token left by a previous user.
    suspend fun configureForRole(context: Context, role: AccountRole) {
        val enabled = role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) &&
            isVoipApiConfigured()
        preferences(context).edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) {
            registerCurrentToken(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (FirebaseAuth.getInstance().currentUser != null) unregisterCurrentDevice(context)
        }
    }

    suspend fun registerCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before registering this device for call invitations"
        }
        check(isEnabled(context)) {
            "Voice calls are not enabled for this account type"
        }
        registerForTokenUpdates(context)
    }

    suspend fun unregisterCurrentDevice(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before unregistering this device's call-invitation token"
        }
        val deviceId = deviceId(context)
        VoipManager().unregisterPushToken(deviceId)
        Log.i(TAG, "Unregistered this device's call-invitation token")
    }

    suspend fun rotateCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before rotating this device's call-invitation token"
        }
        val messaging = FirebaseMessaging.getInstance()
        val installations = FirebaseInstallations.getInstance()
        val installationId = deviceId(context)
        VoipManager().unregisterPushToken(installationId)
        messaging.unregister().await()
        installations.delete().await()
        preferences(context).edit().remove(TOKEN_KEY).apply()
        messaging.register().await()
        val token = installations.id.await()
        saveToken(context, token)
        VoipManager().registerPushToken(installationId, token)
        Log.i(TAG, "Rotated and registered this device's call-invitation token")
    }

    internal fun onTokenRefreshed(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null && isEnabled(context)) {
            enqueueRegistration(context, token)
        }
    }

    internal fun isEnabled(context: Context): Boolean =
        isVoipApiConfigured() && preferences(context).getBoolean(ENABLED_KEY, false)

    private fun saveToken(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        preferences(context).edit()
            .putString(TOKEN_KEY, token)
            .apply()
    }

    private fun storedToken(context: Context): String? =
        preferences(context).getString(TOKEN_KEY, null)

    @Synchronized
    private fun deviceId(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun enqueueRegistration(context: Context, token: String) {
        val input = Data.Builder()
            .putString(INPUT_TOKEN, token)
            .putString(INPUT_DEVICE_ID, deviceId(context))
            .build()
        val request = OneTimeWorkRequestBuilder<VoipPushTokenRegistrationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    internal const val TAG = "VoipPushToken"
    internal const val INPUT_TOKEN_KEY = INPUT_TOKEN
    internal const val INPUT_DEVICE_ID_KEY = INPUT_DEVICE_ID
}

class VoipPushTokenRegistrationWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val token = inputData.getString(VoipPushTokenRegistration.INPUT_TOKEN_KEY)
            ?: return Result.failure()
        val deviceId = inputData.getString(VoipPushTokenRegistration.INPUT_DEVICE_ID_KEY)
            ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.i(VoipPushTokenRegistration.TAG, "Push token saved locally; registration waits for sign-in")
            return Result.success()
        }
        if (!VoipPushTokenRegistration.isEnabled(applicationContext)) return Result.success()

        return try {
            VoipManager().registerPushToken(deviceId, token)
            Result.success()
        } catch (error: VoipApiException) {
            Log.e(VoipPushTokenRegistration.TAG, "Push-token registration failed with HTTP ${error.statusCode}", error)
            if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (error: IOException) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not reach the VoIP API to register the push token", error)
            Result.retry()
        } catch (error: IllegalArgumentException) {
            Log.e(VoipPushTokenRegistration.TAG, "VoIP API configuration is invalid; token remains stored locally", error)
            Result.failure()
        }
    }
}

class VoipFirebaseMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        try {
            VoipPushTokenRegistration.onTokenRefreshed(this, installationId)
        } catch (error: Exception) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not persist the Firebase messaging registration", error)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "call_invitation") return
        if (!VoipPushTokenRegistration.isEnabled(this)) return

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: return
        if (recipientId != currentUid || callId.isBlank() || callerId.isBlank()) return

        try {
            OliveNotificationManager.showIncomingCall(
                this,
                callId,
                callerId,
                data["callName"].orEmpty()
            )
        } catch (error: Exception) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not show the incoming call notification", error)
        }
    }
}
