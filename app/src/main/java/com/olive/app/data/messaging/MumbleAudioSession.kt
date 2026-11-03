// Voice chat users: coordinates Mumble audio with the app's VoIP call records.
package com.olive.app.data.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MumbleConnectionDetails(
    val host: String,
    val port: Int,
    val username: String,
    val serverPassword: String? = null,
) {
    // Rejects connection details that could never identify a Mumble server or user.
    init {
        require(host.isNotBlank()) { "Mumble host must not be blank" }
        require(port in 1..65535) { "Mumble port must be between 1 and 65535" }
        require(username.isNotBlank()) { "Mumble username must not be blank" }
    }
}

// Defines the audio operations that a real Mumble client must provide.
interface MumbleAudioSession {
    // Connects to the Mumble audio room used by this call.
    suspend fun connect(call: VoipCall, details: MumbleConnectionDetails)
    // Mutes or restores the microphone for the active voice call.
    suspend fun setMicrophoneMuted(muted: Boolean)
    // Disconnects from the Mumble audio room and releases audio resources.
    suspend fun disconnect()
}

// Describes the Mumble audio state that call screens can display.
sealed interface VoipSessionState {
    data object Idle : VoipSessionState
    data class Connecting(val call: VoipCall) : VoipSessionState
    data class Active(val call: VoipCall, val microphoneMuted: Boolean) : VoipSessionState
    data class Leaving(val call: VoipCall) : VoipSessionState
    data class Error(val call: VoipCall?, val message: String) : VoipSessionState
}

// Keeps the API call record and Mumble audio connection in the same state.
class VoipSessionController(
    private val voipManager: VoipManager,
    private val audioSession: MumbleAudioSession,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<VoipSessionState>(VoipSessionState.Idle)

    val state: StateFlow<VoipSessionState> = mutableState.asStateFlow()

    // Creates a call record and then joins its Mumble audio channel.
    suspend fun startAndJoin(
        name: String,
        callerId: String,
        participantIds: List<String>,
        connection: MumbleConnectionDetails,
    ): VoipCall = mutex.withLock {
        // Prevents a second call from replacing the session already in progress.
        check(mutableState.value == VoipSessionState.Idle) {
            "A VoIP session is already in progress"
        }
        val call = voipManager.startCall(name, callerId, participantIds)
        joinAudio(call, connection)
    }

    // Accepts an invitation and then joins its Mumble audio channel.
    suspend fun acceptAndJoin(
        callId: String,
        userId: String,
        connection: MumbleConnectionDetails,
    ): VoipCall = mutex.withLock {
        // Accepting is allowed only when this device has no active call.
        check(mutableState.value == VoipSessionState.Idle) {
            "A VoIP session is already in progress"
        }
        val call = voipManager.acceptCall(callId, userId)
        joinAudio(call, connection)
    }

    // Joins an existing call or retries the same call after a connection error.
    suspend fun joinCall(
        call: VoipCall,
        connection: MumbleConnectionDetails,
    ): VoipCall = mutex.withLock {
        // Blocks joining a different call while another session still owns the audio client.
        val current = mutableState.value
        check(
            current == VoipSessionState.Idle ||
                (current is VoipSessionState.Error && current.call?.callId == call.callId)
        ) { "A different VoIP session is already in progress" }
        joinAudio(call, connection)
    }

    // Updates the real microphone and the state shown by the call controls.
    suspend fun setMicrophoneMuted(muted: Boolean) = mutex.withLock {
        val active = mutableState.value as? VoipSessionState.Active
            ?: error("No active VoIP session")
        // Keeps an audio-client failure visible instead of showing the wrong mute state.
        try {
            audioSession.setMicrophoneMuted(muted)
            mutableState.value = active.copy(microphoneMuted = muted)
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                active.call,
                error.message ?: "Could not change microphone state",
            )
            throw error
        }
    }

    // Disconnects this device from audio without deleting the shared call record.
    suspend fun leaveCall() = mutex.withLock {
        // Finds the call that owns the current active or failed audio session.
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to leave")
            else -> error("No call to leave")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        // Returns to idle only after the Mumble client disconnects successfully.
        try {
            audioSession.disconnect()
            mutableState.value = VoipSessionState.Idle
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not disconnect from Mumble",
            )
            throw error
        }
    }

    // Disconnects audio and deletes the shared call record for every participant.
    suspend fun endCall() = mutex.withLock {
        // Finds the call that must be disconnected and removed from the server.
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to end")
            else -> error("No call to end")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        // Performs both cleanup steps before reporting that the session is idle.
        try {
            audioSession.disconnect()
            voipManager.endCall(call.callId)
            mutableState.value = VoipSessionState.Idle
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not end the VoIP call",
            )
            throw error
        }
    }

    // Opens the Mumble audio channel and reports connection failures to the UI.
    private suspend fun joinAudio(
        call: VoipCall,
        connection: MumbleConnectionDetails,
    ): VoipCall {
        mutableState.value = VoipSessionState.Connecting(call)
        // Marks the call active only when the audio client confirms its connection.
        try {
            audioSession.connect(call, connection)
            mutableState.value = VoipSessionState.Active(call, microphoneMuted = false)
            return call
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not connect to the Mumble voice server",
            )
            throw error
        }
    }
}
