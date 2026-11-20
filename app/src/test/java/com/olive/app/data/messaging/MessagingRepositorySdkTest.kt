package com.olive.app.data.messaging

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import com.olive.app.data.account.AccountIdentity
import com.olive.app.support.FirestoreTestDriver
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class MessagingRepositorySdkTest {
    private val db = mock(FirebaseFirestore::class.java)
    private val authorizedPeers = mutableListOf<String>()
    private val repository = MessagingRepository(db) { authorizedPeers += it }
    private val user = AccountIdentity("owner", "Owner_Name")
    private val peer = DirectoryUser("trusted", "trusted_name", "")
    private val request = FriendRequest("request", peer.uid, peer.username, user.uid, user.username, 1L)
    private val pair = "owner--trusted"

    @Test
    fun searchReturnsOnlyAnExactOtherAccountAndRegistrationRejectsAnOccupiedName() = runBlocking {
        val directory = collection("messageDirectory")
        val candidate = document(directory, peer.username)
        val candidateRow = row("trusted", mapOf("uid" to peer.uid, "username" to peer.username, "publicKey" to "key"))
        `when`(candidate.get()).thenReturn(Tasks.forResult(candidateRow))
        assertEquals(listOf(peer.copy(publicKey = "key")), repository.searchUsers(" Trusted_Name ", user.uid))
        assertEquals(listOf("trusted_name"), authorizedPeers)
        assertTrue(repository.searchUsers(peer.username, peer.uid).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.searchUsers("invalid!", user.uid) } }

        val ownRef = document(directory, "owner_name")
        val ownRow = row("owner", mapOf("uid" to user.uid))
        `when`(ownRef.get()).thenReturn(Tasks.forResult(ownRow), Tasks.forResult(candidateRow))
        `when`(ownRef.set(anyMap<String, Any>(), any(SetOptions::class.java))).thenReturn(Tasks.forResult(null))
        val devices = mock(CollectionReference::class.java)
        `when`(ownRef.collection("devices")).thenReturn(devices)
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val device = document(devices, com.olive.app.security.KeyManager.deviceId(publicKey))
        `when`(device.set(anyMap<String, Any>(), any(SetOptions::class.java))).thenReturn(Tasks.forResult(null))
        repository.registerUser(user, publicKey)
        verify(device).set(argThat<Map<String, Any>> { it["uid"] == user.uid && it["platform"] == "ANDROID" },
            any(SetOptions::class.java))
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.registerUser(user, publicKey) } }
        Unit
    }

    @Test
    fun acceptingARequestWritesBothMembershipsAndTheConversationInOneBatch() = runBlocking {
        val requestRef = document(collection("friendRequests"), request.id)
        val friendship = document(collection("friendships"), pair)
        val conversation = document(collection("conversations"), pair)
        val friendLists = collection("friendLists")
        val self = friendEntry(friendLists, user.uid)
        val other = friendEntry(friendLists, peer.uid)
        val batch = mock(WriteBatch::class.java)
        `when`(db.batch()).thenReturn(batch)
        `when`(batch.commit()).thenReturn(Tasks.forResult(null))

        repository.acceptRequest(user, request)

        val order = inOrder(batch)
        order.verify(batch).set(eq(friendship), argThat<Map<String, Any>> { it["acceptedRequestId"] == request.id })
        order.verify(batch).set(eq(self), argThat<Map<String, Any>> { it["friendUid"] == peer.uid })
        order.verify(batch).set(eq(other), argThat<Map<String, Any>> { it["friendUid"] == user.uid })
        order.verify(batch).set(eq(conversation), argThat<Map<String, Any>> { it["participantIds"] == listOf(user.uid, peer.uid) })
        order.verify(batch).delete(requestRef)
        order.verify(batch).commit()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.acceptRequest(AccountIdentity("other", "other_name"), request) } }
        Unit
    }

    @Test
    fun pendingRequestCanBeSentByTheSenderAndDeclinedByTheRecipient() = runBlocking {
        val requests = collection("friendRequests")
        val outgoing = document(requests, pair)
        `when`(outgoing.set(anyMap<String, Any>())).thenReturn(Tasks.forResult(null))
        `when`(outgoing.delete()).thenReturn(Tasks.forResult(null))
        val incoming = document(requests, request.id)
        `when`(incoming.delete()).thenReturn(Tasks.forResult(null))

        repository.sendFriendRequest(user, peer)
        verify(outgoing).set(argThat<Map<String, Any>> { it["senderUid"] == user.uid && it["recipientUid"] == peer.uid })
        repository.cancelRequest(user.uid, request.copy(id = pair, senderUid = user.uid))
        verify(outgoing).delete()
        repository.declineRequest(user.uid, request)
        verify(incoming).delete()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.cancelRequest(user.uid, request) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.sendFriendRequest(user, peer.copy(uid = user.uid)) } }
        Unit
    }

    @Test
    fun existingConversationRequiresBothFriendsAndKeepsOldAndNewTicketMetadata() = runBlocking {
        val friendships = collection("friendships")
        val relationship = document(friendships, pair)
        val friendship = row(pair, mapOf(
            "participantIds" to listOf(user.uid, peer.uid),
            "participantUsernames" to mapOf(user.uid to user.username, peer.uid to peer.username),
        ))
        `when`(relationship.get()).thenReturn(Tasks.forResult(friendship))
        val conversations = collection("conversations")
        val direct = document(conversations, pair)
        val missing = mock(DocumentSnapshot::class.java)
        val existing = row(pair, mapOf("friendshipId" to pair, "participantIds" to listOf(user.uid, peer.uid)))
        `when`(direct.get()).thenReturn(Tasks.forResult(missing), Tasks.forResult(existing))
        `when`(direct.set(anyMap<String, Any>())).thenReturn(Tasks.forResult(null))
        val friend = Friend(pair, peer.uid, peer.username)
        assertEquals(pair, repository.conversationWith(user, friend).id)
        assertEquals(pair, repository.conversationWith(user, friend).id)
        verify(direct).set(anyMap<String, Any>())

        val ticket = document(conversations, "ticket")
        val ticketRow = row("ticket", mapOf(
            "type" to "SUPPORT", "participantIds" to listOf(user.uid, "staff"), "ticketNumber" to 524L,
        ))
        `when`(ticket.get(Source.SERVER)).thenReturn(Tasks.forResult(ticketRow))
        val fetched = requireNotNull(repository.getConversation("ticket"))
        assertEquals("Support Ticket #524", fetched.ticketDisplayName())
        assertEquals("SUPPORT", fetched.supportOrigin)
    }

    @Test
    fun conversationListenerRejectsCacheAndSortsServerAssignments() {
        val conversations = collection("conversations")
        val query = mock(Query::class.java)
        `when`(conversations.whereArrayContains("participantIds", user.uid)).thenReturn(query)
        val registration = mock(ListenerRegistration::class.java)
        `when`(query.addSnapshotListener(eq(MetadataChanges.INCLUDE), any<EventListener<QuerySnapshot>>())).thenReturn(registration)
        val updates = mutableListOf<List<ChatConversation>>()
        val errors = mutableListOf<String>()
        val serverUpdates = mutableListOf<List<ChatConversation>>()
        val subscription = repository.observeConversations(user.uid, updates::add, errors::add, serverUpdates::add)
        val captured = ArgumentCaptor.captor<EventListener<QuerySnapshot>>()
        verify(query).addSnapshotListener(eq(MetadataChanges.INCLUDE), captured.capture())
        @Suppress("UNCHECKED_CAST")
        val listener = captured.value
        val older = row("old", mapOf("type" to "SUPPORT", "participantIds" to listOf(user.uid), "updatedAt" to Timestamp(Date(50L))))
        val newer = row("new", mapOf("type" to "SUPPORT", "participantIds" to listOf(user.uid), "updatedAt" to Timestamp(Date(100L))))
        listener.onEvent(snapshot(listOf(older), cached = true), null)
        assertTrue(updates.isEmpty())
        listener.onEvent(snapshot(listOf(older, newer, row("invalid", emptyMap())), cached = false), null)
        assertEquals(listOf("new", "old"), updates.single().map { it.id })
        assertEquals(updates, serverUpdates)
        listener.onEvent(null, FirebaseFirestoreException("Access denied", FirebaseFirestoreException.Code.PERMISSION_DENIED))
        assertEquals(listOf("Access denied"), errors)
        subscription.close()
        verify(registration).remove()
    }

    private fun collection(path: String): CollectionReference = mock(CollectionReference::class.java).also {
        `when`(db.collection(path)).thenReturn(it)
    }

    @Test
    fun socialIndexWaitsForAllThreeServerSnapshotsAndSortsEachList() {
        val driver = FirestoreTestDriver()
        val source = MessagingRepository(driver.db)
        val friends = "friendLists/${user.uid}/friends"
        val requests = "friendRequests"
        driver.records["$friends/z"] = mapOf("friendUid" to "z", "friendUsername" to "Zoe", "friendshipId" to "z")
        driver.records["$friends/a"] = mapOf("friendUid" to "a", "friendUsername" to "Ali", "friendshipId" to "a")
        driver.records["$friends/invalid"] = emptyMap()
        driver.records["$requests/older"] = mapOf("senderUid" to "a", "recipientUid" to user.uid, "createdAt" to Timestamp(Date(50)))
        driver.records["$requests/newer"] = mapOf("senderUid" to "b", "recipientUid" to user.uid, "createdAt" to Timestamp(Date(100)))
        driver.records["$requests/outgoing"] = mapOf("senderUid" to user.uid, "recipientUid" to "c")
        val published = mutableListOf<SocialSnapshot>()
        val errors = mutableListOf<String>()
        var ready = 0
        val subscription = source.observeSocial(user.uid, published::add, errors::add) { ready++ }
        driver.emit(friends, cached = true)
        driver.emit(requests)
        assertEquals(0, ready)
        driver.emit(friends)
        assertEquals(1, ready)
        driver.emit(friends)
        assertEquals(1, ready)
        assertEquals(listOf("Ali", "Zoe"), published.last().friends.map { it.username })
        assertEquals(listOf("newer", "older"), published.last().incoming.map { it.id })
        assertEquals("outgoing", published.last().outgoing.single().id)
        driver.fail(requests, FirebaseFirestoreException("Synthetic offline error", FirebaseFirestoreException.Code.UNAVAILABLE))
        assertEquals(listOf("Synthetic offline error", "Synthetic offline error"), errors)
        subscription.close()
        assertEquals(3, driver.listeners.count { it.closed })
    }

    @Test
    fun ticketMembershipHistorySkipsCachedAndInvalidRowsAndLimitsActorScopedReads() {
        val driver = FirestoreTestDriver()
        val source = MessagingRepository(driver.db)
        val path = "conversations/ticket/membershipEvents"
        fun event(actor: String, action: String, timestamp: Long) = mapOf(
            "action" to action, "actorUid" to actor, "participantUid" to "guest", "createdAt" to Timestamp(Date(timestamp)),
        )
        driver.records["$path/removed"] = event(user.uid, "REMOVED", 100)
        driver.records["$path/added"] = event("staff", "ADDED", 50)
        driver.records["$path/invalid"] = event(user.uid, "UNKNOWN", 150)
        driver.records["$path/undated"] = event(user.uid, "ADDED", 150) - "createdAt"
        val room = mutableListOf<List<ChatMembershipEvent>>()
        val mine = mutableListOf<List<ChatMembershipEvent>>()
        val all = source.observeMembershipEvents("ticket", onUpdate = room::add)
        val filtered = source.observeMembershipEvents("ticket", user.uid, mine::add)
        driver.emit(path, cached = true)
        assertTrue(room.isEmpty())
        assertTrue(mine.isEmpty())
        driver.emit(path)
        assertEquals(listOf("added", "removed"), room.single().map { it.id })
        assertEquals("removed", mine.single().single().id)
        driver.fail(path, FirebaseFirestoreException("Synthetic revoked membership", FirebaseFirestoreException.Code.PERMISSION_DENIED))
        assertTrue(room.last().isEmpty())
        assertTrue(mine.last().isEmpty())
        all.close()
        filtered.close()
        assertEquals(2, driver.listeners.count { it.closed })
    }

    @Test
    fun conversationObserversAndListingSkipCachedResultsAndSurfaceErrors() = runBlocking<Unit> {
        val driver = FirestoreTestDriver()
        val source = MessagingRepository(driver.db)
        driver.records["conversations/c1"] = mapOf(
            "friendshipId" to "f1",
            "participantIds" to listOf(user.uid, peer.uid),
            "participantUsernames" to mapOf(user.uid to user.username, peer.uid to peer.username),
            "updatedAt" to Timestamp(Date(200)),
        )
        driver.records["conversations/c2"] = mapOf(
            "friendshipId" to "f2",
            "participantIds" to listOf(user.uid, "third"),
            "participantUsernames" to mapOf(user.uid to user.username, "third" to "third_user"),
            "updatedAt" to Timestamp(Date(500)),
        )
        assertEquals(listOf("c2", "c1"), source.listConversations(user.uid).map { it.id })

        val updates = mutableListOf<List<String>>()
        val serverUpdates = mutableListOf<List<String>>()
        val errors = mutableListOf<String>()
        val subscription = source.observeConversations(
            user.uid,
            onUpdate = { list -> updates += list.map { it.id } },
            onError = { errors += it },
            onServerUpdate = { list -> serverUpdates += list.map { it.id } },
        )
        driver.emit("conversations", cached = true)
        assertTrue(updates.isEmpty())
        driver.emit("conversations", cached = false)
        assertEquals(listOf(listOf("c2", "c1")), updates)
        assertEquals(updates, serverUpdates)
        driver.fail("conversations", FirebaseFirestoreException("Offline", FirebaseFirestoreException.Code.UNAVAILABLE))
        assertEquals(listOf("Offline"), errors)
        subscription.close()
    }

    private fun document(parent: CollectionReference, id: String): DocumentReference =
        mock(DocumentReference::class.java).also { `when`(parent.document(id)).thenReturn(it) }

    private fun friendEntry(parent: CollectionReference, uid: String): DocumentReference {
        val list = document(parent, uid)
        val entries = mock(CollectionReference::class.java)
        `when`(list.collection("friends")).thenReturn(entries)
        return document(entries, pair)
    }

    private fun row(id: String, fields: Map<String, Any>): DocumentSnapshot =
        mock(DocumentSnapshot::class.java).also { row ->
            `when`(row.id).thenReturn(id)
            `when`(row.exists()).thenReturn(true)
            `when`(row.get(anyString())).thenAnswer { fields[it.getArgument<String>(0)] }
            `when`(row.getString(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? String }
            `when`(row.getTimestamp(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Timestamp }
            `when`(row.getLong(anyString())).thenAnswer { fields[it.getArgument<String>(0)] as? Long }
        }

    private fun snapshot(rows: List<DocumentSnapshot>, cached: Boolean) = mock(QuerySnapshot::class.java).also {
        val metadata = mock(SnapshotMetadata::class.java)
        `when`(metadata.isFromCache).thenReturn(cached)
        `when`(it.metadata).thenReturn(metadata)
        `when`(it.documents).thenReturn(rows)
    }

    @Test
    fun outboxDrainableIgnoresPermanentFailuresAndMigratesV0Messages() {
        val queued = OutgoingMessage("m-1", "alice", "conv-1", "alice", "encrypted-1", 1000L)
        val failed = queued.copy(id = "m-2", conversationId = "conv-2", status = MessageDeliveryStatus.FAILED_PERMANENT)
        val messages = JSONObject()
            .put(queued.id, queued.toJson().apply { remove("clientSequence") })
            .put(failed.id, failed.toJson())
        val root = JSONObject().put("accounts", JSONObject().put("alice", JSONObject().put("messages", messages)))
        val vault = object : MessageOutboxVault {
            override fun read() = JSONObject(root.toString())
            override fun update(change: (JSONObject) -> Unit) = change(root)
        }
        val store = MessageOutboxStore(vault)
        assertEquals(setOf("conv-1"), store.drainableConversationIds("alice"))
        assertTrue(checkNotNull(store.get(queued.id)).clientSequence > 0)
    }
}
