package com.olive.app.data.messaging

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.DisconnectReason
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveKitAudioSessionTest {
    private val details = LiveKitConnectionDetails("wss://calls.example.test", "fixture-token", "fixture-room")

    @Test
    fun successfulCallStartsPrivatelyAndRestoresAudioAfterARemoteDisconnect() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = true
        val room = mock(Room::class.java)
        val local = mock(LocalParticipant::class.java)
        val remote = mock(RemoteParticipant::class.java)
        val eventBus = BroadcastEventBus<RoomEvent>()
        `when`(room.localParticipant).thenReturn(local)
        `when`(local.setMicrophoneEnabled(anyBoolean())).thenReturn(true)
        `when`(room.remoteParticipants).thenReturn(emptyMap())
        `when`(room.events).thenReturn(eventBus)
        val session = LiveKitAudioSession(app) { room }
        val counts = mutableListOf<Int>()
        val disconnects = mutableListOf<String?>()
        session.setOnParticipantCountChanged { counts += it }
        session.setOnDisconnected { disconnects += it }
        session.connect(details)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertFalse(audio.isSpeakerphoneOn)
        session.setSpeakerEnabled(true)
        assertTrue(audio.isSpeakerphoneOn)
        session.setMicrophoneMuted(true)
        verify(local).setMicrophoneEnabled(false)
        shadowOf(Looper.getMainLooper()).idle()

        `when`(room.remoteParticipants).thenReturn(mapOf(Participant.Identity("remote") to remote))
        eventBus.tryPostEvent(RoomEvent.ParticipantConnected(room, remote))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 1), counts)
        eventBus.tryPostEvent(RoomEvent.Disconnected(room, IllegalStateException("Synthetic network disconnect"),
            DisconnectReason.values().first()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Synthetic network disconnect"), disconnects)
        assertEquals(listOf(0, 1, 0), counts)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertTrue(audio.isSpeakerphoneOn)
        assertTrue(runCatching { session.setMicrophoneMuted(false) }.isFailure)
    }

    @Test
    fun deniedPermissionOrFailedRoomCreationLeavesDeviceAudioUntouched() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = AudioManager.MODE_NORMAL
        var creates = 0
        val session = LiveKitAudioSession(app) {
            creates++
            throw IllegalStateException("Room initialization failed")
        }
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(runCatching { session.connect(details) }.isFailure)
        assertEquals(0, creates)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals("Room initialization failed", runCatching { session.connect(details) }.exceptionOrNull()?.message)
        assertEquals(1, creates)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }

    @Test
    fun failureToPublishMicrophoneDisconnectsTheRoomAndRestoresAudio() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val room = mock(Room::class.java)
        val local = mock(LocalParticipant::class.java)
        `when`(room.localParticipant).thenReturn(local)
        `when`(local.setMicrophoneEnabled(true)).thenReturn(false)
        val session = LiveKitAudioSession(app) { room }

        assertEquals("LiveKit could not publish microphone audio", runCatching { session.connect(details) }.exceptionOrNull()?.message)
        verify(room).disconnect()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
}
