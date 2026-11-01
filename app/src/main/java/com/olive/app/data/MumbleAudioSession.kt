package com.olive.app.data

import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MumbleConnectionDetails(
    val host: String,
    val port: Int,
    val username: String,
    val serverPassword: String? = null
) {
    init {
        require(host.isNotBlank()) { "Mumble host must not be blank" }
        require(port in 1..65535) { "Mumble port must be between 1 and 65535" }
        require(username.isNotBlank()) { "Mumble username must not be blank" }
    }
}

interface MumbleAudioSession {
    suspend fun connect(call: VoipCall, details: MumbleConnectionDetails)
    suspend fun setMicrophoneMuted(muted: Boolean)
    suspend fun disconnect()
}

sealed interface VoipSessionState {
    data object Idle : VoipSessionState
    data class Connecting(val call: VoipCall) : VoipSessionState
    data class Active(val call: VoipCall, val microphoneMuted: Boolean) : VoipSessionState
    data class Leaving(val call: VoipCall) : VoipSessionState
    data class Error(val call: VoipCall?, val message: String) : VoipSessionState
}

class VoipSessionController(
    private val voipManager: VoipManager,
    private val audioSession: MumbleAudioSession
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<VoipSessionState>(VoipSessionState.Idle)

    val state: StateFlow<VoipSessionState> = mutableState.asStateFlow()

    suspend fun startAndJoin(
        name: String,
        callerId: String,
        participantIds: List<String>,
        connection: MumbleConnectionDetails
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.startCall(name, callerId, participantIds)
        joinAudio(call, connection)
    }

    suspend fun acceptAndJoin(
        callId: String,
        userId: String,
        connection: MumbleConnectionDetails
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.acceptCall(callId, userId)
        joinAudio(call, connection)
    }

    suspend fun joinCall(
        call: VoipCall,
        connection: MumbleConnectionDetails
    ): VoipCall = mutex.withLock {
        val current = mutableState.value
        check(current == VoipSessionState.Idle ||
            (current is VoipSessionState.Error && current.call?.callId == call.callId)
        ) { "A different VoIP session is already in progress" }
        joinAudio(call, connection)
    }

    suspend fun setMicrophoneMuted(muted: Boolean) = mutex.withLock {
        val active = mutableState.value as? VoipSessionState.Active
            ?: error("No active VoIP session")
        try {
            audioSession.setMicrophoneMuted(muted)
            mutableState.value = active.copy(microphoneMuted = muted)
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(active.call, error.message ?: "Could not change microphone state")
            throw error
        }
    }

    suspend fun leaveCall() = mutex.withLock {
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to leave")
            else -> error("No call to leave")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        try {
            audioSession.disconnect()
            mutableState.value = VoipSessionState.Idle
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(call, error.message ?: "Could not disconnect from Mumble")
            throw error
        }
    }

    suspend fun endCall() = mutex.withLock {
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to end")
            else -> error("No call to end")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        try {
            audioSession.disconnect()
            voipManager.endCall(call.callId)
            mutableState.value = VoipSessionState.Idle
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(call, error.message ?: "Could not end the VoIP call")
            throw error
        }
    }

    private suspend fun joinAudio(
        call: VoipCall,
        connection: MumbleConnectionDetails
    ): VoipCall {
        mutableState.value = VoipSessionState.Connecting(call)
        try {
            audioSession.connect(call, connection)
            mutableState.value = VoipSessionState.Active(call, microphoneMuted = false)
            return call
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not connect to the Mumble voice server"
            )
            throw error
        }
    }
}
