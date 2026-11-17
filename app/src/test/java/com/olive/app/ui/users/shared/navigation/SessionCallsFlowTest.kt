package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionCallsFlowTest {
    private val manager = mock(VoipManager::class.java)
    private val audio = mock(VoipAudioSession::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = VoipSessionController(manager, audio)
    private val calls = SessionCalls(RuntimeEnvironment.getApplication(), scope, manager, controller, available = true)
    private val room = VoipCall("call", "Synthetic call", "owner", listOf(
        VoipParticipant("owner", VoipParticipantStatus.ACCEPTED),
        VoipParticipant("friend", VoipParticipantStatus.ACCEPTED),
        VoipParticipant("third", VoipParticipantStatus.PENDING),
    ), conversationId = "group")
    private val details = LiveKitConnectionDetails("wss://voice.example.test", "fixture-token", "fixture-room")

    @org.junit.After fun cleanUp() {
        calls.close()
        scope.cancel()
    }

    @Test
    fun outgoingGroupCallTargetsEveryoneElseAndWrongNotificationCannotEndIt() = runBlocking<Unit> {
        val group = ChatConversation("group", "group", listOf("owner", "friend", "third"),
            mapOf("owner" to "Owner", "friend" to "Friend", "third" to "Third"), type = "GROUP")
        `when`(manager.startCall("Friend, Third", "owner", listOf("friend", "third"), "Owner", "group")).thenReturn(room)
        `when`(manager.getLiveKitConnectionDetails("call")).thenReturn(details)
        calls.start(group.copy(type = "SUPPORT"), "owner", AccountRole.USER)
        assertNotNull(calls.message)
        calls.start(group, "owner", AccountRole.USER)
        assertTrue(calls.canHandleNotification("call"))
        calls.setMuted(true)
        calls.setSpeakerEnabled(true)
        val active = calls.state.value as VoipSessionState.Active
        assertTrue(active.microphoneMuted)
        assertTrue(active.speakerEnabled)
        calls.finish("owner", "wrong-call").join()
        assertTrue(calls.canHandleNotification("call"))
        calls.finish("owner", "call").join()
        assertEquals(VoipSessionState.Idle, calls.state.value)
        verify(manager).endCall("call")
    }

    @Test
    fun acceptingAnInvitationAsAGroupMemberAndLeavingKeepsTheRemoteRoom() = runBlocking<Unit> {
        val invitation = VoipInvitation("call", "owner", "Synthetic caller", VoipParticipantStatus.PENDING)
        `when`(manager.acceptCall("call", "friend")).thenReturn(room)
        `when`(manager.getLiveKitConnectionDetails("call")).thenReturn(details)
        calls.accept(invitation, "friend", AccountRole.STAFF)
        verify(manager, never()).acceptCall("call", "friend")
        calls.accept(invitation, "friend", AccountRole.SAFE_CONTACT)
        assertTrue(calls.canHandleNotification("call"))
        calls.finish("friend").join()
        verify(manager).leaveCall("call")
        verify(manager, never()).endCall("call")
        assertEquals(VoipSessionState.Idle, calls.state.value)
    }

    @Test
    fun pollingFiltersExpiredInvitesAndDecliningSuppressesTheMatchingCall() = runBlocking<Unit> {
        val incoming = room.copy(participants = room.participants.map {
            if (it.userId == "friend") it.copy(status = VoipParticipantStatus.PENDING) else it
        }, expiresAt = System.currentTimeMillis() + 60_000)
        `when`(manager.listCalls()).thenReturn(listOf(incoming, incoming.copy(callId = "expired", expiresAt = 1)))
        calls.observeInvitations("friend", AccountRole.SAFE_CONTACT)
        assertEquals(listOf("call"), calls.invitations.map { it.callId })
        assertEquals(2, calls.historyFor("group", AccountRole.USER).size)
        assertTrue(calls.historyFor("group", AccountRole.STAFF).isEmpty())
        calls.decline(calls.invitations.single(), "friend", AccountRole.SAFE_CONTACT)
        verify(manager).declineCall("call", "friend")
        assertTrue(calls.invitations.isEmpty())
    }
}
