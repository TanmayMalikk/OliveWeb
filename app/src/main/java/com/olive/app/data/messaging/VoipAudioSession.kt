package com.olive.app.data.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/** Connection details required by LiveKit Cloud to join a voice call room. */
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

/** Audio session interface for connecting to LiveKit. */
interface VoipAudioSession {
    fun setOnDisconnected(listener: (String?) -> Unit)
    fun setOnParticipantCountChanged(listener: (Int) -> Unit)
    suspend fun connect(details: LiveKitConnectionDetails)
    suspend fun setMicrophoneMuted(muted: Boolean)
    suspend fun setSpeakerEnabled(enabled: Boolean)
    suspend fun disconnect()
}

/** LiveKit-backed implementation of [VoipAudioSession]. */
class LiveKitAudioSession(context: Context) : VoipAudioSession {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutex = Mutex()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var roomEventJob: Job? = null
    private var localDisconnect = false
    private var onDisconnected: (String?) -> Unit = {}
    private var onParticipantCountChanged: (Int) -> Unit = {}
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerEnabled = false
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun setOnDisconnected(listener: (String?) -> Unit) {
        onDisconnected = listener
    }

    override fun setOnParticipantCountChanged(listener: (Int) -> Unit) {
        onParticipantCountChanged = listener
    }

    override suspend fun connect(details: LiveKitConnectionDetails) = mutex.withLock {
        check(room == null) { "A LiveKit room is already connected" }
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission is required to join a voice call" }

        prepareCallAudio()
        val newRoom = LiveKit.create(appContext)
        try {
            newRoom.connect(details.serverUrl, details.token)
            check(newRoom.localParticipant.setMicrophoneEnabled(true)) {
                "LiveKit could not publish microphone audio"
            }
            room = newRoom
            onParticipantCountChanged(newRoom.remoteParticipants.size)
            roomEventJob = eventScope.launch {
                newRoom.events.collect { event ->
                    if ((event is RoomEvent.ParticipantConnected ||
                            event is RoomEvent.ParticipantDisconnected) && room === newRoom) {
                        onParticipantCountChanged(newRoom.remoteParticipants.size)
                    }
                    if (event is RoomEvent.Disconnected && room === newRoom) {
                        room = null
                        onParticipantCountChanged(0)
                        restoreAudioRoute()
                        if (!localDisconnect) {
                            onDisconnected(event.error?.message)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            try {
                newRoom.disconnect()
            } catch (disconnectError: Exception) {
                error.addSuppressed(disconnectError)
            }
            restoreAudioRoute()
            throw error
        }
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) = mutex.withLock {
        val connectedRoom = room ?: error("No LiveKit room is connected")
        check(connectedRoom.localParticipant.setMicrophoneEnabled(!muted)) {
            "LiveKit could not update microphone state"
        }
    }

    override suspend fun setSpeakerEnabled(enabled: Boolean) = mutex.withLock {
        check(room != null) { "No LiveKit room is connected" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
                ?: error(if (enabled) "Speaker is unavailable" else "Phone earpiece is unavailable")
            check(audioManager.setCommunicationDevice(device)) { "Could not change the call audio route" }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        val connectedRoom = room ?: return@withLock
        try {
            localDisconnect = true
            connectedRoom.disconnect()
            room = null
        } finally {
            roomEventJob?.cancel()
            roomEventJob = null
            localDisconnect = false
            onParticipantCountChanged(0)
            restoreAudioRoute()
        }
    }

    private fun prepareCallAudio() {
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerEnabled = audioManager.isSpeakerphoneOn
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .build()
                .also(audioManager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun restoreAudioRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = previousSpeakerEnabled }
        runCatching { audioManager.mode = previousAudioMode }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}

/** Represents the current state of a VoIP session. */
sealed interface VoipSessionState {
    data object Idle : VoipSessionState
    data class Connecting(val call: VoipCall) : VoipSessionState
    data class Active(
        val call: VoipCall,
        val microphoneMuted: Boolean,
        val speakerEnabled: Boolean = false,
        val remoteParticipantCount: Int = 0,
    ) : VoipSessionState
    data class Leaving(val call: VoipCall) : VoipSessionState
    data class Error(val call: VoipCall?, val message: String) : VoipSessionState
}

/** Coordinates VoIP API calls and LiveKit audio sessions. */
class VoipSessionController(
    private val voipManager: VoipManager,
    private val audioSession: VoipAudioSession
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<VoipSessionState>(VoipSessionState.Idle)
    private var remoteParticipantCount = 0

    val state: StateFlow<VoipSessionState> = mutableState.asStateFlow()

    init {
        audioSession.setOnParticipantCountChanged { count ->
            remoteParticipantCount = count.coerceAtLeast(0)
            val active = mutableState.value as? VoipSessionState.Active
            if (active != null) {
                mutableState.value = active.copy(remoteParticipantCount = remoteParticipantCount)
            }
        }
        audioSession.setOnDisconnected { message ->
            val call = when (val current = mutableState.value) {
                is VoipSessionState.Connecting -> current.call
                is VoipSessionState.Active -> current.call
                else -> null
            } ?: return@setOnDisconnected
            mutableState.value = if (message.isNullOrBlank()) VoipSessionState.Idle
            else VoipSessionState.Error(call, message)
        }
    }

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
        callerName: String = name,
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.startCall(name, callerId, participantIds, callerName)
        joinAudio(call)
    }

    suspend fun acceptAndJoin(
        callId: String,
        userId: String
    ): VoipCall = mutex.withLock {
        check(mutableState.value == VoipSessionState.Idle) { "A VoIP session is already in progress" }
        val call = voipManager.acceptCall(callId, userId)
        joinAudio(call)
    }

    suspend fun joinCall(
        call: VoipCall
    ): VoipCall = mutex.withLock {
        val current = mutableState.value
        check(
            current == VoipSessionState.Idle ||
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
            mutableState.value = VoipSessionState.Error(
                active.call,
                error.message ?: "Could not change microphone state"
            )
            throw error
        }
    }

    suspend fun setSpeakerEnabled(enabled: Boolean) = mutex.withLock {
        val active = mutableState.value as? VoipSessionState.Active
            ?: error("No active VoIP session")
        try {
            audioSession.setSpeakerEnabled(enabled)
            mutableState.value = active.copy(speakerEnabled = enabled)
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                active.call,
                error.message ?: "Could not change the call audio route"
            )
            throw error
        }
    }

    suspend fun leaveCall() = mutex.withLock {
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Connecting -> current.call
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to leave")
            else -> error("No call to leave")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        try {
            audioSession.disconnect()
            mutableState.value = VoipSessionState.Idle
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not disconnect from LiveKit"
            )
            throw error
        }
    }

    suspend fun endCall() = mutex.withLock {
        val call = when (val current = mutableState.value) {
            is VoipSessionState.Connecting -> current.call
            is VoipSessionState.Active -> current.call
            is VoipSessionState.Error -> current.call ?: error("No call to end")
            else -> error("No call to end")
        }
        mutableState.value = VoipSessionState.Leaving(call)
        try {
            audioSession.disconnect()
        } catch (error: Exception) {
            mutableState.value = VoipSessionState.Error(
                call,
                error.message ?: "Could not disconnect from LiveKit"
            )
            throw error
        }
        // Hangup is locally complete once audio is disconnected. A server cleanup failure can
        // still be reported outside the call, but must never strand the user on a Close screen.
        mutableState.value = VoipSessionState.Idle
        try {
            voipManager.endCall(call.callId)
        } catch (error: Exception) {
            if (error !is VoipApiException || error.statusCode != 409) throw error
        }
    }

    /** Closes an unrecoverable/stale call screen without making another failing server request. */
    suspend fun dismiss() = mutex.withLock {
        runCatching { audioSession.disconnect() }
        mutableState.value = VoipSessionState.Idle
    }

    private suspend fun joinAudio(call: VoipCall): VoipCall {
        mutableState.value = VoipSessionState.Connecting(call)
        remoteParticipantCount = 0
        try {
            val connection = voipManager.getLiveKitConnectionDetails(call.callId)
            audioSession.connect(connection)
            mutableState.value = VoipSessionState.Active(
                call,
                microphoneMuted = false,
                remoteParticipantCount = remoteParticipantCount,
            )
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
