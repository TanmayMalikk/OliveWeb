package com.olive.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipManager
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

data class LiveKitConnectionDetails(
    val serverUrl: String,
    val token: String,
    val roomName: String
) {
    init {
        val uri = runCatching { URI(serverUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme.equals("wss", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null
        ) { "LiveKit server URL must be a secure WebSocket URL" }
        require(token.isNotBlank()) { "LiveKit token must not be blank" }
        require(roomName.isNotBlank()) { "LiveKit room name must not be blank" }
    }
}

interface VoipAudioSession {
    suspend fun connect(details: LiveKitConnectionDetails)
    suspend fun setMicrophoneMuted(muted: Boolean)
    suspend fun disconnect()
}

class LiveKitAudioSession(context: Context) : VoipAudioSession {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var room: Room? = null

    override suspend fun connect(details: LiveKitConnectionDetails) = mutex.withLock {
        check(room == null) { "A LiveKit room is already connected" }
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required to join a voice call" }

        val newRoom = LiveKit.create(appContext)
        try {
            newRoom.connect(details.serverUrl, details.token)
            check(newRoom.localParticipant.setMicrophoneEnabled(true)) {
                "LiveKit could not publish microphone audio"
            }
            room = newRoom
        } catch (error: Exception) {
            try {
                newRoom.disconnect()
            } catch (disconnectError: Exception) {
                error.addSuppressed(disconnectError)
            }
            throw error
        }
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) = mutex.withLock {
        val connectedRoom = room ?: error("No LiveKit room is connected")
        check(connectedRoom.localParticipant.setMicrophoneEnabled(!muted)) {
            "LiveKit could not update microphone state"
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        val connectedRoom = room ?: return@withLock
        connectedRoom.disconnect()
        room = null
    }
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
    private val audioSession: VoipAudioSession
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<VoipSessionState>(VoipSessionState.Idle)

    val state: StateFlow<VoipSessionState> = mutableState.asStateFlow()

    companion object {
        fun create(
            context: Context,
            voipManager: VoipManager = VoipManager()
        ) = VoipSessionController(voipManager, LiveKitAudioSession(context))
    }

    suspend fun startAndJoin(
        name: String,
        callerId: String,
        participantIds: List<String>,
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.startCall(name, callerId, participantIds)
        joinAudio(call)
    }

    suspend fun acceptAndJoin(
        callId: String,
        userId: String,
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.acceptCall(callId, userId)
        joinAudio(call)
    }

    suspend fun joinCall(
        call: VoipCall,
    ): VoipCall = mutex.withLock {
        val current = mutableState.value
        check(current == VoipSessionState.Idle ||
            (current is VoipSessionState.Error && current.call?.callId == call.callId)
        ) { "A different VoIP session is already in progress" }
        joinAudio(call)
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
            mutableState.value = VoipSessionState.Error(call, error.message ?: "Could not disconnect from LiveKit")
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

    private suspend fun joinAudio(call: VoipCall): VoipCall {
        mutableState.value = VoipSessionState.Connecting(call)
        try {
            val connection = voipManager.getLiveKitConnectionDetails(call.callId)
            audioSession.connect(connection)
            mutableState.value = VoipSessionState.Active(call, microphoneMuted = false)
            return call
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not connect to the LiveKit voice room"
            )
            throw error
        }
    }
}
