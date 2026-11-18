package com.olive.app.ui.session

import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionMessagingActionsTest {
    @Test
    fun repositoryFailuresReleaseBusyStateAndAccountChangesSuppressOldSearchResults() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            `when`(fixture.messaging.searchUsers("trusted", "owner")).thenAnswer { emptyList<DirectoryUser>() }
            runtime.friendActions.searchUsers("trusted")
            fixture.await { !runtime.state.messagingBusy }
            assertTrue(runtime.state.userSearchResults.isEmpty())
            assertNotNull(runtime.state.message)

            `when`(fixture.messaging.searchUsers("denied", "owner")).thenAnswer {
                throw FirebaseFirestoreException("Permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
            }
            runtime.friendActions.searchUsers("denied")
            fixture.await { !runtime.state.messagingBusy }
            assertTrue(runtime.state.message.orEmpty().contains("Firebase denied this action"))
            runtime.state.messagingNeedsReconnect = true
            runtime.friendActions.searchUsers("blocked")
            verify(fixture.messaging, never()).searchUsers("blocked", "owner")
        }
    }

    @Test
    fun friendsAndPendingRequestsSelectTheAllowedActionAndRespectTheRecipient() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val incoming = FriendRequest("incoming", "requester", "Request fixture", "owner", "fixture_owner", 1)
            val outgoing = FriendRequest("outgoing", "owner", "fixture_owner", "pending", "Pending fixture", 2)
            runtime.state.friends = listOf(Friend("confirmed", "friend", "Friend fixture"))
            runtime.state.incomingFriendRequests = listOf(incoming)
            runtime.state.outgoingFriendRequests = listOf(outgoing)
            assertEquals(FriendRelationship.Friends, runtime.friendActions.relationshipFor("friend"))
            assertEquals(FriendRelationship.IncomingRequest, runtime.friendActions.relationshipFor("requester"))
            assertEquals(FriendRelationship.RequestSent, runtime.friendActions.relationshipFor("pending"))
            assertEquals(FriendRelationship.AddFriend, runtime.friendActions.relationshipFor("unknown"))

            runtime.friendActions.declineFriendRequest(incoming)
            fixture.await { !runtime.state.messagingBusy }
            verify(fixture.messaging).declineRequest("owner", incoming)
            runtime.friendActions.cancelFriendRequest(outgoing)
            fixture.await { !runtime.state.messagingBusy }
            verify(fixture.messaging).cancelRequest("owner", outgoing)
            val peer = DirectoryUser("new", "new_fixture")
            runtime.friendActions.sendFriendRequest(peer)
            fixture.await { !runtime.state.messagingBusy }
            verify(fixture.messaging).sendFriendRequest(AccountIdentity("owner", "fixture_owner"), peer)
            runtime.friendActions.acceptFriendRequest(incoming)
            fixture.await { !runtime.state.messagingBusy }
            verify(fixture.messaging).acceptRequest(AccountIdentity("owner", "fixture_owner"), incoming)
        }
    }

    @Test
    fun chatArchiveAndPrivateNotePreferencesStayScopedToTheSignedInOwner() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val conversation = ChatConversation("sample", "pair", listOf("owner", "trusted"), emptyMap(), lastMessageId = "latest", lastSenderUid = "trusted")
            assertTrue(runtime.isConversationUnread(conversation))
            runtime.conversationActions.markConversationRead(conversation)
            assertFalse(runtime.isConversationUnread(conversation))
            assertEquals(1, runtime.state.readMessageVersion)
            runtime.conversationActions.markConversationRead(conversation)
            assertEquals(1, runtime.state.readMessageVersion)

            runtime.conversationActions.setArchived(conversation, true)
            fixture.await { runtime.store.archivedConversationIds("owner").contains("sample") }
            assertEquals(setOf("sample"), runtime.state.archivedConversationIds)
            runtime.conversationActions.setArchived(conversation, false)
            fixture.await { runtime.store.archivedConversationIds("owner").isEmpty() }

            val target = DirectoryUser("trusted", "Trusted fixture")
            runtime.conversationActions.appendPrivateNote(target, "Denied")
            assertTrue(runtime.state.privateContactNotes.isEmpty())
            runtime.state.friends = listOf(Friend("pair", "trusted", "Trusted fixture"))
            runtime.conversationActions.appendPrivateNote(target, "First observation")
            fixture.await { !runtime.state.messagingBusy }
            runtime.conversationActions.appendPrivateNote(target, "Second observation")
            fixture.await { !runtime.state.messagingBusy }
            assertTrue(runtime.state.privateContactNotes.getValue("trusted").body.contains("First observation"))
            assertTrue(runtime.state.privateContactNotes.getValue("trusted").body.contains("Second observation"))
            runtime.enrollment = DeviceEnrollment("different-owner", "")
            assertTrue(runtime.state.privateContactNotes.isEmpty())
            assertTrue(runtime.store.privateContactNotes("different-owner").isEmpty())
        }
    }

    @Test
    fun signedOutOrDifferentScreenCannotRunAStaleMessagingAction() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            runtime.state.screen = SessionScreen.Cover
            runtime.friendActions.searchUsers("blocked")
            verify(fixture.messaging, never()).searchUsers("blocked", "owner")
            runtime.state.screen = SessionScreen.Safe
            runtime.state.page = SafePage.Messages
            `when`(fixture.accounts.authenticatedUid()).thenReturn("different-owner")
            runtime.friendActions.searchUsers("stale")
            verify(fixture.messaging, never()).searchUsers("stale", "owner")
            assertTrue(runtime.state.messagingNeedsReconnect)
            assertFalse(runtime.state.messagingBusy)
        }
    }
}
