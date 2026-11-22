package com.olive.app.data.messaging

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.*
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.network.persistentInstallationId
import com.olive.app.notification.OliveNotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
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

/**
 * Computes connected participant count for this VoIP call.
 *
 * @return The computed connected participant count numeric value.
 */
fun VoipCall.connectedParticipantCount(): Int =
    participants.count { it.status == VoipParticipantStatus.ACCEPTED }

/**
 * Checks whether the caller can be rejoined by.
 *
 * @param userId User ID supplied to [canBeRejoinedBy].
 * @return `true` if can be rejoined by, or `false` otherwise.
 */
fun VoipCall.canBeRejoinedBy(userId: String): Boolean =
    status == VoipCallStatus.ACTIVE &&
        connectedParticipantCount() >= 2 &&
        participants.any { it.userId == userId && it.status == VoipParticipantStatus.DECLINED }

/**
 * Lets an invited chat member enter an active call from its chat log. Pending members accept the invitation, while disconnected members rejoin an established room.
 *
 * @param userId User ID supplied to [canBeJoinedBy].
 * @return `true` if can be joined by, or `false` otherwise.
 */
fun VoipCall.canBeJoinedBy(userId: String): Boolean {
    return status == VoipCallStatus.ACTIVE && when (participants.firstOrNull { it.userId == userId }?.status) {
        VoipParticipantStatus.PENDING, VoipParticipantStatus.ACCEPTED -> true
        VoipParticipantStatus.DECLINED -> connectedParticipantCount() >= 2
        null -> false
    }
}

/**
 * Computes other party name for this VoIP call.
 *
 * @param currentUid Current user ID supplied to [otherPartyName].
 * @return The resulting other party name string.
 */
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
    data class Active(val callId: String, val participantCount: Int) : VoipState
    data class Error(val message: String) : VoipState
}

class VoipApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String
) : IOException(message)

/**
 * Checks whether VoIP API configured.
 *
 * @param apiBaseUrl API base URL supplied to [isVoipApiConfigured].
 * @return `true` if is VoIP API configured, or `false` otherwise.
 */
internal fun isVoipApiConfigured(apiBaseUrl: String = BuildConfig.VOIP_API_BASE_URL): Boolean =
    apiBaseUrl.isNotBlank()

class VoipManager(
    apiBaseUrl: String = BuildConfig.VOIP_API_BASE_URL,
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

    /**
     * Creates a new call record and invites each selected participant.
     *
     * @param name Field, key, or display name.
     * @param callerId Firebase UID of the caller initiating the call.
     * @param participantIds Collection of participant IDs values to process.
     * @param callerName Caller name supplied to [startCall].
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return The resulting [VoipCall] for start call.
     */
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

    /**
     * Returns live kit connection details.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @return The resulting [LiveKitConnectionDetails] for get live kit connection details.
     */
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

    /**
     * Loads every call record currently visible to the signed-in client.
     *
     * @return List of resulting list calls items.
     */
    suspend fun listCalls(): List<VoipCall> {
        val response = request("GET", "/api/calls")
        return response.getJSONArray("calls").toCallList()
    }

    /**
     * Loads the unanswered call invitations for one user.
     *
     * @param userId User ID supplied to [getIncomingCalls].
     * @return List of resulting get incoming calls items.
     */
    suspend fun getIncomingCalls(userId: String): List<VoipInvitation> {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val encodedUserId = withContext(Dispatchers.IO) {
            URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
        }
        val response = request("GET", "/api/calls/invitations?userId=$encodedUserId")
        return response.getJSONArray("invitations").toInvitationList()
    }

    /**
     * Polls the server and updates the ringing state until the collector stops.
     *
     * @param userId User ID supplied to [receiveCalls].
     * @param pollingIntervalMillis Duration or timestamp for polling interval, in milliseconds.
     * @return The resulting [Flow] for receive calls.
     */
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

    /**
     * Accepts the incoming VoIP call and starts its audio session.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @param userId User ID supplied to [acceptCall].
     * @return The resulting [VoipCall] for accept call.
     */
    suspend fun acceptCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "accept")

    /**
     * Declines the incoming VoIP call and closes its session.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @param userId User ID supplied to [declineCall].
     * @return The resulting [VoipCall] for decline call.
     */
    suspend fun declineCall(callId: String, userId: String): VoipCall =
        respondToInvitation(callId, userId, "decline")

    /**
     * Deletes the server call record after the call has ended.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    suspend fun endCall(callId: String) {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        request("POST", "/calls/end", JSONObject().put("callId", callId))
    }

    /**
     * Removes this phone from a group call without disconnecting everyone else.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    suspend fun leaveCall(callId: String) {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        request("POST", "/api/calls/${encodePathSegment(callId)}/leave", JSONObject())
    }

    /**
     * Restores a previous member while at least two people are still in the group call.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @return The resulting [VoipCall] for rejoin call.
     */
    suspend fun rejoinCall(callId: String): VoipCall {
        require(callId.isNotBlank()) { "Call ID must not be blank" }
        return parseCall(
            request("POST", "/api/calls/${encodePathSegment(callId)}/rejoin", JSONObject())
        )
    }

    /**
     * Registers push token.
     *
     * @param deviceId Stable identifier for this device installation.
     * @param token Authentication, push notification, or session token.
     */
    suspend fun registerPushToken(deviceId: String, token: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        require(token.isNotBlank()) { "Push token must not be blank" }
        request(
            "POST",
            "/notifications/register",
            JSONObject().put("deviceId", deviceId).put("token", token)
        )
    }

    /**
     * Unregisters push token.
     *
     * @param deviceId Stable identifier for this device installation.
     */
    suspend fun unregisterPushToken(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        request("DELETE", "/notifications/register", JSONObject().put("deviceId", deviceId))
    }

    /**
     * Sends one user's accept or decline choice to the matching call endpoint.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @param userId User ID supplied to [respondToInvitation].
     * @param response Response supplied to [respondToInvitation].
     * @return The resulting [VoipCall] for respond to invitation.
     */
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

    /**
     * Runs one authenticated API request and converts its response into JSON.
     *
     * @param method Method supplied to [request].
     * @param path Path supplied to [request].
     * @param body Body text content, or null when not provided.
     * @return JSON object representing request.
     */
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

    /**
     * Builds one request with the Firebase identity currently available for this signed-in account.
     *
     * @param method Method supplied to [authenticatedRequest].
     * @param path Path supplied to [authenticatedRequest].
     * @param body Body text content, or null when not provided.
     * @param bearerToken Optional bearer token supplied to [authenticatedRequest].
     * @return The resulting [Request] for authenticated request.
     */
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

    /**
     * Retries only DNS lookup failures, then parses the server response or reports its exact error.
     *
     * @param request Request supplied to [executeWithDnsRetries].
     * @return JSON object representing execute with dns retries.
     */
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
                    if (attempt + 1 < attempts) delay((750L * (attempt + 1)).milliseconds)
                }
            }
            throw IOException(
                "The voice service could not be reached. Check the connection and try again.",
                lastDnsError,
            )
    }

    /**
     * Converts one call JSON object into the model used by the app.
     *
     * @param json JSON object containing serialized fields.
     * @return The resulting [VoipCall] for parse call.
     */
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

    /**
     * Converts a server array into call models.
     *
     * @return List of resulting to call list items.
     */
    private fun JSONArray.toCallList(): List<VoipCall> =
        (0 until length()).map { parseCall(getJSONObject(it)) }

    /**
     * Converts a server array into invitation models for the ringing screen.
     *
     * @return List of resulting to invitation list items.
     */
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

    /**
     * Converts a server array into the participants and their current responses.
     *
     * @return List of resulting to participant list items.
     */
    private fun JSONArray.toParticipantList(): List<VoipParticipant> =
        (0 until length()).map { index ->
            getJSONObject(index).let {
                VoipParticipant(
                    userId = it.getString("userId"),
                    status = parseParticipantStatus(it.getString("status"))
                )
            }
        }

    /**
     * Validates a server status before exposing it to the rest of the app.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting [VoipParticipantStatus] for parse participant status.
     */
    private fun parseParticipantStatus(value: String): VoipParticipantStatus =
        try {
            VoipParticipantStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw IOException("VoIP API returned an unknown participant status", error)
        }

    /**
     * Parses call status.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting [VoipCallStatus] for parse call status.
     */
    private fun parseCallStatus(value: String): VoipCallStatus =
        try {
            VoipCallStatus.valueOf(value.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw IOException("VoIP API returned an unknown call status", error)
        }

    /**
     * Escapes a call ID so special characters cannot change the request path.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting encode path segment string.
     */
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_POLLING_INTERVAL_MILLIS = 5_000L
    }
}

// Keeps an active voice call alive when the user leaves Olive Branch or turns off the screen.
class VoipCallForegroundService : Service() {
    /**
     * Initializes the component and sets up its state or UI when created by Android.
     */
    override fun onCreate() {
        super.onCreate()
        OliveNotificationManager.createNotificationChannels(this)
    }

    /**
     * Android requires the foreground notification immediately after the call service starts.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @param flags Flags supplied to [onStartCommand].
     * @param startId Start ID supplied to [onStartCommand].
     * @return The computed on start command numeric value.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val returnToApp = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = returnToApp?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, OliveNotificationManager.CHANNEL_ACTIVE_CALLS)
            .setSmallIcon(R.drawable.ic_notification_olive_branch)
            .setContentTitle(getString(R.string.ui_voice_call))
            .setContentText(getString(R.string.ui_call_running_in_background))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ui_end_call),
                callAction(ACTION_END_CALL, callId),
            )
            .apply {
                if (intent.getBooleanExtra(EXTRA_QUICK_EXIT, false)) addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.quick_exit),
                    callAction(ACTION_QUICK_EXIT, callId),
                )
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    /**
     * Invokes action.
     *
     * @param action Action supplied to [callAction].
     * @param callId Unique identifier of the VoIP call session.
     * @return The resulting [PendingIntent] for call action.
     */
    private fun callAction(action: String, callId: String): PendingIntent {
        val intent = Intent(action)
            .setPackage(packageName)
            .setData(Uri.Builder().scheme(CALL_ACTION_SCHEME).authority("call").appendPath(callId).build())
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Returns the binder interface for clients connecting to this service.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @return The resulting [IBinder], or `null` if unavailable.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_END_CALL = "com.olive.app.voip.END_CALL"
        internal const val ACTION_QUICK_EXIT = "com.olive.app.voip.QUICK_EXIT"
        internal const val CALL_ACTION_SCHEME = "olive-call-action"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_QUICK_EXIT = "quick_exit"
        private const val NOTIFICATION_ID = 8_107

        /**
         * Starts the notification while the call room is connecting or connected.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param callId Unique identifier of the VoIP call session.
         * @param quickExit Whether to apply quick exit.
         */
        fun start(context: Context, callId: String, quickExit: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoipCallForegroundService::class.java)
                    .putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_QUICK_EXIT, quickExit),
            )
        }

        /**
         * Removes the call notification after the room has closed or failed.
         *
         * @param context Android context used to access system services, resources, or storage.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoipCallForegroundService::class.java))
        }
    }
}

// Registers or removes this device's call-invitation push token with the VoIP server.
object VoipPushTokenRegistration {
    private const val PREFERENCES = "voip_push_token"
    private const val TOKEN_KEY = "token"
    private const val DEVICE_ID_KEY = "device_id"
    private const val ENABLED_KEY = "enabled_for_account"
    private const val WORK_NAME = "voip-push-token-registration"
    private const val INPUT_TOKEN = "token"
    private const val INPUT_DEVICE_ID = "device_id"

    /**
     * Subscribes to Firebase messaging updates and uploads the latest push token.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun registerForTokenUpdates(context: Context) {
        FirebaseMessaging.getInstance().register().await()
        onTokenRefreshed(context, FirebaseInstallations.getInstance().id.await())
    }

    /**
     * Personal accounts can receive calls; organization accounts remove any token left by a previous user.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param role Account role determining workspace permissions.
     */
    suspend fun configureForRole(context: Context, role: AccountRole) {
        val enabled = role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) && isVoipApiConfigured()
        preferences(context).edit { putBoolean(ENABLED_KEY, enabled)}
        if (enabled) {
            registerCurrentToken(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (FirebaseAuth.getInstance().currentUser != null) unregisterCurrentDevice(context)
        }
    }

    /**
     * Registers the current device token once the user is signed in.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun registerCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before registering this device for call invitations"
        }
        check(isEnabled(context)) { "Voice calls are not enabled for this account type" }
        registerForTokenUpdates(context)
    }

    /**
     * Removes this device's call-invitation token from the server on sign-out.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun unregisterCurrentDevice(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before unregistering this device's call-invitation token"
        }
        val deviceId = deviceId(context)
        VoipManager().unregisterPushToken(deviceId)
        Log.i(TAG, "Unregistered this device's call-invitation token")
    }

    /**
     * Replaces the existing push token with a fresh installation ID.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
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
        preferences(context).edit {remove(TOKEN_KEY)}
        messaging.register().await()
        val token = installations.id.await()
        saveToken(context, token)
        VoipManager().registerPushToken(installationId, token)
        Log.i(TAG, "Rotated and registered this device's call-invitation token")
    }

    /**
     * Saves a refreshed Firebase token and schedules background registration.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    internal fun onTokenRefreshed(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null && isEnabled(context)) {
            enqueueRegistration(context, token)
        }
    }

    /**
     * Checks whether enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return `true` if is enabled, or `false` otherwise.
     */
    internal fun isEnabled(context: Context): Boolean =
        isVoipApiConfigured() && preferences(context).getBoolean(ENABLED_KEY, false)

    /**
     * Saves token.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    private fun saveToken(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        preferences(context).edit { putString(TOKEN_KEY, token)}
    }

    /**
     * Computes device ID.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting device ID string.
     */
    private fun deviceId(context: Context): String =
        persistentInstallationId(context, PREFERENCES, DEVICE_ID_KEY)

    /**
     * Computes preferences.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting preferences.
     */
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Enqueues registration.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    private fun enqueueRegistration(context: Context, token: String) {
        val input = Data.Builder()
            .putString(INPUT_TOKEN, token)
            .putString(INPUT_DEVICE_ID, deviceId(context))
            .build()
        val request = OneTimeWorkRequestBuilder<VoipPushTokenRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal const val TAG = "VoipPushToken"
    internal const val INPUT_TOKEN_KEY = INPUT_TOKEN
    internal const val INPUT_DEVICE_ID_KEY = INPUT_DEVICE_ID
}

// Uploads the stored VoIP push token to the call server when network connectivity is available.
class VoipPushTokenRegistrationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result {
        val token = inputData.getString(VoipPushTokenRegistration.INPUT_TOKEN_KEY) ?: return Result.failure()
        val deviceId = inputData.getString(VoipPushTokenRegistration.INPUT_DEVICE_ID_KEY) ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()
        if (!VoipPushTokenRegistration.isEnabled(applicationContext)) return Result.success()

        return try {
            VoipManager().registerPushToken(deviceId, token)
            Result.success()
        } catch (error: VoipApiException) {
            if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) Result.retry()
            else Result.failure()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: IllegalArgumentException) {
            Result.failure()
        }
    }
}
