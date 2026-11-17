package com.olive.app.ui.session

import android.content.Context
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionMessagingObservationTest {
    private class Observers(val fixture: SessionRuntimeFixture) {
        lateinit var social: (SocialSnapshot)->Unit
        lateinit var socialError: (String)->Unit
        lateinit var socialReady: ()->Unit
        lateinit var chats: (List<ChatConversation>)->Unit
        lateinit var server: (List<ChatConversation>)->Unit
        lateinit var chatError: (String)->Unit
        var closed = 0
        init {
            doAnswer { call ->
                social=call.getArgument(1); socialError=call.getArgument(2); socialReady=call.getArgument(3)
                ChatSubscription { closed++ }
            }.`when`(fixture.messaging).observeSocial(anyString(), any<(SocialSnapshot)->Unit>() ?: {}, any<(String)->Unit>() ?: {}, any<()->Unit>())
            doAnswer { call ->
                chats=call.getArgument(1); chatError=call.getArgument(2); server=call.getArgument(3)
                ChatSubscription { closed++ }
            }.`when`(fixture.messaging).observeConversations(anyString(), any<(List<ChatConversation>)->Unit>() ?: {}, any<(String)->Unit>() ?: {}, any<(List<ChatConversation>)->Unit>())
        }
    }
    private fun withBackgroundTransport(action: ()->Unit) {
        val auth = mock(FirebaseAuth::class.java)
        val work = mock(androidx.work.impl.WorkManagerImpl::class.java)
        mockStatic(FirebaseAuth::class.java).use { firebase ->
            firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            androidx.work.impl.WorkManagerImpl.setDelegate(work)
            try { action() } finally { androidx.work.impl.WorkManagerImpl.setDelegate(null) }
        }
    }

    @Test
    fun socialCallbacksFilterForeignRequestsAndAreIgnoredAfterAnAccountSwitch() = withBackgroundTransport {
        SessionRuntimeFixture().use { f ->
            val listeners=Observers(f)
            f.runtime.messaging.startMessaging()
            val valid=FriendRequest("request", "friend", "fixture_friend", "owner", "fixture_owner", 1)
            val foreign=valid.copy(id="foreign", recipientUid="another")
            listeners.social(SocialSnapshot(listOf(Friend("pair","friend","fixture_friend")), listOf(valid,foreign),listOf(valid, valid.copy(senderUid="owner"))))
            assertEquals(listOf(valid), f.runtime.state.incomingFriendRequests)
            assertEquals(1,f.runtime.state.outgoingFriendRequests.size)
            assertEquals(1,f.runtime.state.friends.size)
            listeners.socialError("Synthetic failure")
            assertTrue(f.runtime.state.message.orEmpty().contains("unable_to_load_friend"))
            listeners.socialReady()
            f.runtime.enrollment=DeviceEnrollment("different","",AccountRole.USER)
            listeners.social(SocialSnapshot(listOf(Friend("foreign","intruder","intruder_fixture"))))
            assertTrue(f.runtime.state.friends.isEmpty())
            assertTrue(f.runtime.state.incomingFriendRequests.isEmpty())
            assertEquals(2,listeners.closed)
        }
    }

    @Test
    fun serverMembershipRemovalClosesTheChatAndPurgesArchivePreferences() = withBackgroundTransport {
        SessionRuntimeFixture().use { f ->
            val listeners=Observers(f)
            f.runtime.messaging.startMessaging()
            val chat=ChatConversation("ticket","SUPPORT",listOf("owner","staff"),emptyMap())
            listeners.chats(listOf(chat))
            assertEquals(listOf(chat),f.runtime.state.conversations)
            f.runtime.state.activeConversation=chat
            f.runtime.state.archivedConversationIds=setOf("ticket","removed")
            f.runtime.state.restoredClosedConversationIds=setOf("ticket","removed")
            listeners.server(emptyList())
            assertNull(f.runtime.state.activeConversation)
            assertTrue(f.runtime.state.archivedConversationIds.isEmpty())
            assertTrue(f.runtime.state.restoredClosedConversationIds.isEmpty())
            listeners.chatError("Synthetic permission failure")
            assertTrue(f.runtime.state.conversationLoadFailed)
            listeners.chats(emptyList())
            assertFalse(f.runtime.state.conversationLoadFailed)
            assertNull(f.runtime.state.message)
        }
    }

    @Test
    fun closedTicketUpdatesCloseTheActiveConversationAndMismatchedRolesRequireReconnection() = withBackgroundTransport {
        SessionRuntimeFixture().use { f ->
            val listeners=Observers(f)
            f.runtime.messaging.startMessaging()
            val chat=ChatConversation("ticket","SUPPORT",listOf("owner","staff"),emptyMap())
            f.runtime.state.activeConversation=chat
            listeners.chats(listOf(chat.copy(closedAt=10)))
            assertNull(f.runtime.state.activeConversation)
            f.runtime.messaging.stopMessaging()
            runBlocking { `when`(f.accounts.authenticatedRole()).thenReturn(AccountRole.ADMIN) }
            f.runtime.messaging.startMessaging()
            assertTrue(f.runtime.state.messagingNeedsReconnect)
            assertTrue(f.runtime.state.message.orEmpty().contains("registered_as"))
        }
    }

    @Test
    fun pendingSupportLookupOnlyOpensAnActiveTicketTheCurrentAccountStillBelongsTo() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val messages = SessionMessages(fixture.runtime)
            val ticket = ChatConversation(
                "ticket", "ticket", listOf("owner", "staff"), emptyMap(),
                type = "SUPPORT", ownerUid = "owner", staffUid = "staff", ticketSequence = 104,
            )
            assertNull(messages.findRequestedConversation(""))
            verifyNoInteractions(fixture.messaging)
            `when`(fixture.messaging.getConversation("ticket")).thenReturn(ticket)
            assertEquals(ticket, messages.findRequestedConversation("ticket"))
            `when`(fixture.messaging.getConversation("ticket")).thenReturn(ticket.copy(closedAt = 5))
            assertNull(messages.findRequestedConversation("ticket"))
            `when`(fixture.messaging.getConversation("ticket")).thenReturn(ticket.copy(participantIds = listOf("staff")))
            assertNull(messages.findRequestedConversation("ticket"))
            doAnswer { throw java.io.IOException("Synthetic connection issue") }.`when`(fixture.messaging).getConversation("ticket")
            assertNull(messages.findRequestedConversation("ticket"))
            doAnswer { throw CancellationException("Synthetic cancellation") }.`when`(fixture.messaging).getConversation("ticket")
            assertTrue(runCatching { messages.findRequestedConversation("ticket") }.exceptionOrNull() is CancellationException)
        }
    }

}
