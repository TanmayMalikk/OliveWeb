// All chat roles: loads and saves direct messages and friend requests.
package com.olive.app.data.messaging

import com.olive.app.data.network.cloudStringMap
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import com.olive.app.security.KeyManager
import java.security.PublicKey
import java.util.Base64
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.network.requireLong
import com.olive.app.data.network.requireString
import com.olive.app.data.network.requireStringKeyedMap
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.UnlockedAccountKeyset
import java.security.spec.MGF1ParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MessagingRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val authorizePeer: suspend (String) -> Unit = ::authorizeMessagingPeer,
) {
    /**
     * Prepares support tickets.
     */
    suspend fun prepareSupportTickets() {
        FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable("supportTicketsPrepare").call().await()
    }

    /**
     * Prepares ticket participant names.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return Map containing the resulting prepare ticket participant names entries.
     */
    internal suspend fun prepareTicketParticipantNames(conversationId: String): Map<String, String> {
        val result = FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable("conversationPrepareKeys")
            .call(mapOf("conversationId" to conversationId)).await().data as? Map<*, *>
        return result?.get("participantUsernames").cloudStringMap()
            ?: error("Ticket participant identities are unavailable")
    }
    /**
     * Publishes this account's messaging key and device details to Firebase.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param publicKey Public key supplied to [registerUser].
     */
    suspend fun registerUser(
        identity: AccountIdentity,
        publicKey: PublicKey = KeyManager.getOrCreatePublicKey()
    ) {
        val username = identity.username.trim().lowercase()
        val encodedKey = Base64.getEncoder().encodeToString(publicKey.encoded)
        val reference = firestore.collection("messageDirectory").document(username)
        val current = reference.get().await()
        check(!current.exists() || current.getString("uid") == identity.uid) {
            "That username belongs to another account."
        }
        reference.set(
            mapOf(
                "uid" to identity.uid, "username" to username, "publicKey" to encodedKey,
                "updatedAt" to FieldValue.serverTimestamp(),
            ), SetOptions.merge()
        ).await()
        val deviceId = KeyManager.deviceId(publicKey)
        reference.collection("devices").document(deviceId).set(
            mapOf(
                "uid" to identity.uid,
                "deviceId" to deviceId,
                "publicKey" to encodedKey,
                "platform" to "ANDROID",
                "mgf1Digest" to "SHA-1",
                "updatedAt" to FieldValue.serverTimestamp(),
            ), SetOptions.merge()
        ).await()
    }

    /**
     * Resolves encryption identities only for existing authorized connections.
     *
     * @param query Search query string entered by the user.
     * @param currentUid Current user ID supplied to [searchUsers].
     * @return List of resulting search users items.
     */
    suspend fun searchUsers(query: String, currentUid: String): List<DirectoryUser> {
        val normalized = query.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Enter the exact 4-24 character username." }
        authorizePeer(normalized)
        val row = firestore.collection("messageDirectory").document(normalized).get().await()
        val uid = row.getString("uid") ?: return emptyList()
        val username = row.getString("username") ?: return emptyList()
        val publicKey = row.getString("publicKey").orEmpty()
        return if (uid == currentUid) emptyList() else listOf(
            DirectoryUser(
                uid,
                username,
                publicKey
            )
        )
    }

    /**
     * Streams friend requests, accepted friends, and directory changes for one signed-in account.
     *
     * @param uid Firebase UID of the target user account.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @param onServerReady Callback invoked when server ready changes or occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeSocial(
        uid: String,
        onUpdate: (SocialSnapshot) -> Unit,
        onError: (String) -> Unit,
        onServerReady: (() -> Unit)? = null,
    ): ChatSubscription {
        var friends = emptyList<Friend>()
        var incoming = emptyList<FriendRequest>()
        var outgoing = emptyList<FriendRequest>()
        // Sends the newest friend and request lists after any listener changes.
        val confirmed = mutableSetOf<Int>()
        /**
         * Publishes the messaging repository.
         *
         * @param list List supplied to [publish].
         * @param snapshot Firestore or data snapshot containing the latest state, or null when not provided.
         */
        fun publish(list: Int, snapshot: QuerySnapshot?) {
            onUpdate(SocialSnapshot(friends, incoming, outgoing))
            if (snapshot?.metadata?.isFromCache == false && confirmed.add(list) && confirmed.size == 3) {
                onServerReady?.invoke()
            }
        }
        val listeners = mutableListOf<ListenerRegistration>()
        listeners += firestore.collection("friendLists").document(uid).collection("friends")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load friends."
                    )
                    return@addSnapshotListener
                }
                friends =
                    snapshot?.documents.orEmpty().mapNotNull(::friendFrom).sortedBy { it.username }
                publish(0, snapshot)
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("recipientUid", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load incoming requests."
                    )
                    return@addSnapshotListener
                }
                incoming = snapshot?.documents.orEmpty().mapNotNull(::requestFrom)
                    .sortedByDescending { it.createdAt }
                publish(1, snapshot)
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("senderUid", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load sent requests."
                    )
                    return@addSnapshotListener
                }
                outgoing = snapshot?.documents.orEmpty().mapNotNull(::requestFrom)
                    .sortedByDescending { it.createdAt }
                publish(2, snapshot)
            }
        return ChatSubscription { listeners.forEach(ListenerRegistration::remove) }
    }

    /**
     * Creates a pending request after confirming both accounts and their roles may connect.
     *
     * @param current Current supplied to [sendFriendRequest].
     * @param other Other supplied to [sendFriendRequest].
     */
    suspend fun sendFriendRequest(current: AccountIdentity, other: DirectoryUser) {
        require(current.uid != other.uid) { "You cannot add yourself." }
        require(other.username.matches(Regex("[a-z0-9_]{4,24}"))) { "That username is not valid." }
        val forward =
            firestore.collection("friendRequests").document(requestId(current.uid, other.uid))
        // Rules reject duplicates, reverse requests, and existing friendships atomically without private missing-document reads.
        forward.set(
            mapOf(
                "senderUid" to current.uid,
                "senderUsername" to current.username.lowercase(),
                "recipientUid" to other.uid,
                "recipientUsername" to other.username,
                "friendshipId" to friendshipId(current.uid, other.uid),
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /**
     * Accepts a friend request and creates the shared friendship record.
     *
     * @param current Current supplied to [acceptRequest].
     * @param request Request supplied to [acceptRequest].
     */
    suspend fun acceptRequest(current: AccountIdentity, request: FriendRequest) {
        require(request.recipientUid == current.uid) { "Only the recipient can accept this request." }
        val requestRef = firestore.collection("friendRequests").document(request.id)
        val friendshipId = friendshipId(current.uid, request.senderUid)
        val friendshipRef = firestore.collection("friendships").document(friendshipId)
        val currentFriendRef =
            firestore.collection("friendLists").document(current.uid).collection("friends")
                .document(friendshipId)
        val senderFriendRef =
            firestore.collection("friendLists").document(request.senderUid).collection("friends")
                .document(friendshipId)
        val conversationRef = firestore.collection("conversations").document(friendshipId)
        val participantIds = listOf(current.uid, request.senderUid).sorted()
        val participantNames = mapOf(
            current.uid to current.username.lowercase(),
            request.senderUid to request.senderUsername
        )
        val batch = firestore.batch()
        batch.set(
            friendshipRef, mapOf(
                "participantIds" to participantIds, "participantUsernames" to participantNames,
                "acceptedRequestId" to request.id, "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            currentFriendRef, mapOf(
                "friendshipId" to friendshipId,
                "friendUid" to request.senderUid,
                "friendUsername" to request.senderUsername,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            senderFriendRef, mapOf(
                "friendshipId" to friendshipId,
                "friendUid" to current.uid,
                "friendUsername" to current.username.lowercase(),
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            conversationRef, directConversationData(friendshipId, participantIds, participantNames)
        )
        batch.delete(requestRef)
        batch.commit().await()
    }

    /**
     * Declines an incoming friend request and removes its pending record.
     *
     * @param currentUid Current user ID supplied to [declineRequest].
     * @param request Request supplied to [declineRequest].
     */
    suspend fun declineRequest(currentUid: String, request: FriendRequest) {
        require(request.recipientUid == currentUid) { "Only the recipient can decline this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    /**
     * Removes an outgoing request only when the signed-in account originally sent it.
     *
     * @param currentUid Current user ID supplied to [cancelRequest].
     * @param request Request supplied to [cancelRequest].
     */
    suspend fun cancelRequest(currentUid: String, request: FriendRequest) {
        require(request.senderUid == currentUid) { "Only the sender can cancel this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    /**
     * Opens the existing direct chat with a friend or creates a blank one.
     *
     * @param current Current supplied to [conversationWith].
     * @param friend Friend relationship record.
     * @return The resulting [ChatConversation] for conversation with.
     */
    suspend fun conversationWith(current: AccountIdentity, friend: Friend): ChatConversation {
        require(current.uid != friend.uid) { "Choose another user." }
        val friendship =
            firestore.collection("friendships").document(friend.friendshipId).get().await()
        check(friendship.exists()) { "This friendship is no longer available." }
        val ids =
            (friendship.get("participantIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
        check(ids.size == 2 && current.uid in ids && friend.uid in ids) { "Only friends can open this conversation." }
        val names = friendship.get("participantUsernames").cloudStringMap().orEmpty()
        val ref = firestore.collection("conversations").document(friend.friendshipId)
        val existing = ref.get().await()
        if (existing.exists()) return checkNotNull(conversationFrom(existing))
        ref.set(directConversationData(friend.friendshipId, ids, names)).await()
        return ChatConversation(friend.friendshipId, friend.friendshipId, ids, names)
    }

    /**
     * Streams direct and support conversations visible to the signed-in participant.
     *
     * @param currentUid Current user ID supplied to [observeConversations].
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @param onServerUpdate Callback invoked when server update changes or occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeConversations(
        currentUid: String,
        onUpdate: (List<ChatConversation>) -> Unit,
        onError: (String) -> Unit,
        onServerUpdate: ((List<ChatConversation>) -> Unit)? = null,
    ): ChatSubscription {
        val listener =
            firestore.collection("conversations").whereArrayContains("participantIds", currentUid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        onError(
                            error.localizedMessage ?: "Unable to load conversations."
                        )
                        return@addSnapshotListener
                    }
                    val conversations = snapshot?.documents.orEmpty().mapNotNull(::conversationFrom)
                        .sortedByDescending { it.updatedAt }
                    if (snapshot != null && !snapshot.metadata.isFromCache) {
                        onUpdate(conversations)
                        onServerUpdate?.invoke(conversations)
                    }
                }
        return listener.asSubscription()
    }

    /**
     * Starts observing membership events.
     *
     * @param id ID supplied to [observeMembershipEvents].
     * @param actorUid Firebase UID of the account performing the action, or null when not provided.
     * @param onUpdate Callback invoked when updated data is available.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    internal fun observeMembershipEvents(
        id: String,
        actorUid: String? = null,
        onUpdate: (List<ChatMembershipEvent>) -> Unit,
    ): ChatSubscription {
        val events = firestore.collection("conversations").document(id).collection("membershipEvents")
        val query = if (actorUid == null) events.orderBy("createdAt").limitToLast(250)
        else events.whereEqualTo("actorUid", actorUid)
        return query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                onUpdate(emptyList())
                return@addSnapshotListener
            }
            if (snapshot?.metadata?.isFromCache != false) return@addSnapshotListener
            onUpdate(snapshot.documents.mapNotNull { row ->
                val action = ChatMembershipAction.entries.firstOrNull { it.name == row.getString("action") }
                    ?: return@mapNotNull null
                ChatMembershipEvent(
                    row.id, action, row.getString("actorUid") ?: return@mapNotNull null,
                    row.getString("actorName").orEmpty(), row.getString("participantUid") ?: return@mapNotNull null,
                    row.getString("participantName").orEmpty(),
                    row.getTimestamp("createdAt")?.toDate()?.time ?: return@mapNotNull null,
                )
            })
        }.asSubscription()
    }

    /**
     * Loads one server-confirmed conversation for durable attachment authorization.
     *
     * @param id ID supplied to [getConversation].
     * @return The resulting [ChatConversation], or `null` if unavailable.
     */
    suspend fun getConversation(id: String): ChatConversation? =
        conversationFrom(firestore.collection("conversations").document(id)
            .get(Source.SERVER).await())

    /**
     * Loads a server-confirmed conversation index for background membership and key reconciliation.
     *
     * @param currentUid Current user ID supplied to [listConversations].
     * @return List of resulting list conversations items.
     */
    suspend fun listConversations(currentUid: String): List<ChatConversation> =
        firestore.collection("conversations").whereArrayContains("participantIds", currentUid)
            .get(Source.SERVER).await().documents
            .mapNotNull(::conversationFrom)
            .sortedByDescending(ChatConversation::updatedAt)

    /**
     * Loads incoming and outgoing friend requests for this account.
     *
     * @param row Row supplied to [requestFrom].
     * @return The resulting [FriendRequest], or `null` if unavailable.
     */
    private fun requestFrom(row: DocumentSnapshot): FriendRequest? {
        val senderUid = row.getString("senderUid") ?: return null
        val recipientUid = row.getString("recipientUid") ?: return null
        return FriendRequest(
            row.id,
            senderUid,
            row.getString("senderUsername").orEmpty(),
            recipientUid,
            row.getString("recipientUsername").orEmpty(),
            row.getTimestamp("createdAt")?.toDate()?.time ?: 0L
        )
    }

    /**
     * Converts a Firestore friend record into the app friend model.
     *
     * @param row Row supplied to [friendFrom].
     * @return The resulting [Friend], or `null` if unavailable.
     */
    private fun friendFrom(row: DocumentSnapshot): Friend? {
        val friendshipId = row.getString("friendshipId") ?: return null
        val friendUid = row.getString("friendUid") ?: return null
        val friendUsername = row.getString("friendUsername") ?: return null
        return Friend(friendshipId, friendUid, friendUsername)
    }

    /**
     * Converts a Firestore chat record into the app conversation model.
     *
     * @param row Row supplied to [conversationFrom].
     * @return The resulting [ChatConversation], or `null` if unavailable.
     */
    private fun conversationFrom(row: DocumentSnapshot): ChatConversation? {
        val ids = (row.get("participantIds") as? List<*>)?.filterIsInstance<String>() ?: return null
        val names = row.get("participantUsernames").cloudStringMap().orEmpty()
        val friendshipId = row.getString("friendshipId") ?: row.id.takeIf { row.getString("type") == "SUPPORT" }
            ?: return null
        return ChatConversation(
            row.id,
            friendshipId,
            ids,
            names,
            row.getString("lastMessage").orEmpty(),
            row.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
            row.getString("lastMessageId").orEmpty(),
            row.getString("lastSenderUid").orEmpty(),
            row.getString("type") ?: "DIRECT",
            row.getString("ownerUid").orEmpty(),
            row.getString("staffUid").orEmpty(),
            row.getString("adminUid").orEmpty(),
            row.getString("supportOrigin").orEmpty().ifBlank {
                if (row.getString("type") == "SUPPORT") SUPPORT_TAG_SUPPORT else ""
            },
            row.getString("supportTag").orEmpty().ifBlank {
                if (row.getString("type") == "SUPPORT") SUPPORT_TAG_SUPPORT else ""
            },
            row.getLong("ticketNumber") ?: -1L,
            row.getTimestamp("closedAt")?.toDate()?.time ?: 0L,
        )
    }

    /**
     * Messaging: Requests id.
     *
     * @param senderUid Firebase UID of the sending account.
     * @param recipientUid Firebase UID of the recipient account.
     * @return The resulting request ID.
     */
    private fun requestId(senderUid: String, recipientUid: String) = "$senderUid--$recipientUid"

    /**
     * Loads the direct conversation data needed by secure messaging.
     *
     * @param friendshipId Unique identifier of the friendship record.
     * @param participantIds Collection of participant IDs values to process.
     * @param participantNames Key-value mapping of participant names entries.
     * @return The resulting direct conversation data.
     */
    private fun directConversationData(
        friendshipId: String,
        participantIds: List<String>,
        participantNames: Map<String, String>,
    ) = mapOf(
        "friendshipId" to friendshipId,
        "participantIds" to participantIds.sorted(),
        "participantUsernames" to participantNames,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
        "lastMessage" to "",
        "lastMessageId" to "",
        "lastSenderUid" to "",
    )

    /**
     * Creates the same friendship ID regardless of which username comes first.
     *
     * @param firstUid First user ID supplied to [friendshipId].
     * @param secondUid Second user ID supplied to [friendshipId].
     * @return The resulting friendship ID.
     */
    private fun friendshipId(firstUid: String, secondUid: String) =
        listOf(firstUid, secondUid).sorted().joinToString("--")

    /**
     * Wraps a Firebase listener so the screen can close it safely.
     *
     * @receiver The Firestore listener registration to wrap as a chat subscription.
     * @return The resulting as subscription.
     */
    private fun ListenerRegistration.asSubscription() = ChatSubscription { remove() }
}

// All chat roles: persists queued ciphertext across process death and connectivity changes.

internal interface MessageOutboxVault {
    /**
     * Reads the message outbox vault.
     *
     * @return JSON object representing read.
     */
    fun read(): JSONObject
    /**
     * Updates the message outbox vault.
     *
     * @param change Action or callback executed for change.
     */
    fun update(change: (JSONObject) -> Unit)
}

private class EncryptedMessageOutboxVault(context: Context) : MessageOutboxVault {
    private val vault = EncryptedVault(context, "message-outbox")
    /**
     * Reads the encrypted message outbox vault.
     *
     * @return The resulting read.
     */
    override fun read() = vault.read()
    /**
     * Updates the encrypted message outbox vault.
     *
     * @param change Action or callback executed for change.
     */
    override fun update(change: (JSONObject) -> Unit) = vault.update(change)
}

/** Account-isolated access to the Keystore-encrypted outgoing-message vault. */
class MessageOutboxStore internal constructor(private val vault: MessageOutboxVault) {
    constructor(context: Context) : this(EncryptedMessageOutboxVault(context.applicationContext))

    /**
     * Returns the message outbox store.
     *
     * @param id ID supplied to [get].
     * @return The resulting [OutgoingMessage], or `null` if unavailable.
     */
    fun get(id: String): OutgoingMessage? {
        val accounts = vault.read().optJSONObject(ACCOUNTS) ?: return null
        val owner = accounts.keys().asSequence().firstOrNull { uid ->
            accounts.optJSONObject(uid)?.optJSONObject(MESSAGES)?.has(id) == true
        } ?: return null
        return all(owner).firstOrNull { it.id == id }
    }

    /**
     * Computes all.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return List of resulting all items.
     */
    fun all(ownerUid: String): List<OutgoingMessage> {
        var result = readMessages(ownerUid)
        if (result.any { it.clientSequence == 0L }) {
            vault.update { root ->
                root.optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)?.let(::migrateAccount)
            }
            result = readMessages(ownerUid)
        }
        return result.sortedWith(
            compareBy(
                OutgoingMessage::conversationId,
                OutgoingMessage::clientSequence,
                OutgoingMessage::clientSentAt,
                OutgoingMessage::id,
            )
        )
    }

    /**
     * Assigns and persists the next sequence atomically with the ciphertext record.
     *
     * @param message Chat message or remote payload.
     * @return The resulting [OutgoingMessage] instance.
     */
    fun write(message: OutgoingMessage): OutgoingMessage {
        var persisted: OutgoingMessage? = null
        vault.update { root ->
            val account = root.child(ACCOUNTS).child(message.ownerUid)
            migrateAccount(account)
            val collection = messages(account)
            collection.optJSONObject(message.id)?.let { existingJson ->
                val existing = OutgoingMessage.fromJson(existingJson)
                require(
                    existing.ownerUid == message.ownerUid &&
                            existing.conversationId == message.conversationId &&
                            existing.ciphertext == message.ciphertext &&
                            existing.attachment == message.attachment
                ) { "A queued message id cannot identify different encrypted content" }
                persisted = existing
                return@update
            }
            val counters = counters(account)
            val previous = counters.optLong(message.conversationId, 0)
            val sequence = if (message.clientSequence > 0) {
                require(message.clientSequence > previous) { "Message sequence must increase" }
                message.clientSequence
            } else {
                previous + 1
            }
            val sequenced = message.copy(clientSequence = sequence)
            counters.put(message.conversationId, sequence)
            collection.put(sequenced.id, sequenced.toJson())
            persisted = sequenced
        }
        changes.tryEmit(message.ownerUid)
        return checkNotNull(persisted)
    }

    /**
     * Updates the message outbox store.
     *
     * @param id ID supplied to [update].
     * @param change Function used to transform or provide change.
     * @return The resulting [OutgoingMessage] instance.
     */
    fun update(id: String, change: (OutgoingMessage) -> OutgoingMessage): OutgoingMessage {
        var result: OutgoingMessage? = null
        vault.update { root ->
            val accounts = root.optJSONObject(ACCOUNTS) ?: error("Message outbox is empty")
            val owner = accounts.keys().asSequence().firstOrNull { uid ->
                accounts.optJSONObject(uid)?.optJSONObject(MESSAGES)?.has(id) == true
            } ?: error("Outgoing message was not found")
            val account = accounts.getJSONObject(owner)
            migrateAccount(account)
            val collection = account.getJSONObject(MESSAGES)
            val current = OutgoingMessage.fromJson(collection.getJSONObject(id))
            val updated = change(current)
            require(
                updated.id == current.id && updated.ownerUid == current.ownerUid &&
                        updated.conversationId == current.conversationId &&
                        updated.clientSequence == current.clientSequence
            )
            result = updated
            collection.put(id, updated.toJson())
        }
        changes.tryEmit(checkNotNull(result).ownerUid)
        return result
    }

    /**
     * The first unresolved message is a hard barrier for every later sequence.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return The resulting [OutgoingMessage], or `null` if unavailable.
     */
    fun head(ownerUid: String, conversationId: String): OutgoingMessage? = all(ownerUid)
        .asSequence()
        .filter { it.conversationId == conversationId && it.status != MessageDeliveryStatus.SENT }
        .minByOrNull(OutgoingMessage::clientSequence)

    /**
     * Computes drainable conversation IDs.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return List of resulting drainable conversation IDs items.
     */
    fun drainableConversationIds(ownerUid: String): Set<String> = all(ownerUid)
        .asSequence()
        .filter { it.status != MessageDeliveryStatus.SENT }
        .groupBy(OutgoingMessage::conversationId)
        .mapNotNullTo(linkedSetOf()) { (conversationId, messages) ->
            messages.minByOrNull(OutgoingMessage::clientSequence)
                ?.takeUnless { it.status == MessageDeliveryStatus.FAILED_PERMANENT }
                ?.let { conversationId }
        }

    /**
     * Removes the message outbox store.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param id ID supplied to [remove].
     */
    fun remove(ownerUid: String, id: String) {
        vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
                ?.optJSONObject(MESSAGES)?.remove(id)
        }
        changes.tryEmit(ownerUid)
    }

    /**
     * Purges the message outbox store.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun purge(ownerUid: String) {
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.remove(ownerUid) }
        changes.tryEmit(ownerUid)
    }

    /**
     * Emits an initial snapshot and each process-local update made by the UI or worker.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(ownerUid: String): Flow<List<OutgoingMessage>> = changes
        .filter { it == ownerUid }
        .map { all(ownerUid) }
        .onStart { emit(all(ownerUid)) }

    /**
     * Computes messages.
     *
     * @param account Account supplied to [messages].
     * @return JSON object representing messages.
     */
    private fun messages(account: JSONObject): JSONObject =
        account.child(MESSAGES)

    /**
     * Computes counters.
     *
     * @param account Account supplied to [counters].
     * @return JSON object representing counters.
     */
    private fun counters(account: JSONObject): JSONObject =
        account.child(SEQUENCES)

    /**
     * Reads messages.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return List of resulting read messages items.
     */
    private fun readMessages(ownerUid: String): List<OutgoingMessage> {
        val messages = vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
            ?.optJSONObject(MESSAGES) ?: return emptyList()
        return messages.keys().asSequence().mapNotNull { id ->
            messages.optJSONObject(id)?.let(::safeMessage)
        }.toList()
    }

    /**
     * Gives records written by the previous schema a deterministic order before new sends.
     *
     * @param account Account supplied to [migrateAccount].
     */
    private fun migrateAccount(account: JSONObject) {
        val messages = messages(account)
        val counters = counters(account)
        val records = messages.keys().asSequence().mapNotNull { id ->
            messages.optJSONObject(id)?.let(::safeMessage)
        }.groupBy(OutgoingMessage::conversationId)
        records.forEach { (conversationId, conversationMessages) ->
            var sequence = maxOf(
                counters.optLong(conversationId, 0),
                conversationMessages.maxOfOrNull(OutgoingMessage::clientSequence) ?: 0,
            )
            conversationMessages.filter { it.clientSequence == 0L }
                .sortedWith(compareBy(OutgoingMessage::clientSentAt, OutgoingMessage::id))
                .forEach { legacy ->
                    sequence += 1
                    messages.put(legacy.id, legacy.copy(clientSequence = sequence).toJson())
                }
            counters.put(conversationId, sequence)
        }
    }

    /**
     * Computes safe message.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting safe message.
     */
    private fun safeMessage(value: JSONObject) = runCatching { OutgoingMessage.fromJson(value) }.getOrNull()

    private companion object {
        const val ACCOUNTS = "accounts"
        const val MESSAGES = "messages"
        const val SEQUENCES = "lastClientSequences"
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    }
}

data class FriendInvite(val code: String, val link: String, val expiresAt: Long)

class FriendInviteRepository(
    private val expectedUid: String? = null,
    private val functions: () -> FirebaseFunctions = { FirebaseFunctions.getInstance("northamerica-northeast1") },
) {

    /**
     * Requests a shareable friend invitation for the signed-in account.
     *
     * @param refresh True to replace the current invitation before it expires or is claimed.
     * @return The invitation code, download link, and expiration timestamp supplied by Firebase.
     */
    suspend fun create(refresh: Boolean = false): FriendInvite {
        val uid = currentUid()
        val data = functions().getHttpsCallable("friendInviteCreate")
            .call(mapOf("refresh" to refresh)).await().data.requireStringKeyedMap("friendInviteCreate")
        check(currentUid() == uid) { "Your account changed. Open Invite again." }
        return FriendInvite(data.requireString("code"), data.requireString("link"), data.requireLong("expiresAt"))
    }

    /**
     * Registers the signed-in account's messaging identity and claims a friend invitation.
     *
     * @param code The invitation code from the other person. Leading and trailing spaces are ignored.
     * @return The username of the person who created the invitation.
     */
    suspend fun claim(code: String): String {
        val uid = currentUid()
        val user = checkNotNull(FirebaseAuth.getInstance().currentUser)
        val username = checkNotNull(user.email).substringBefore('@')
        MessagingRepository().registerUser(AccountIdentity(uid, username))
        val data = functions().getHttpsCallable("friendInviteClaim")
            .call(mapOf("code" to code.trim())).await().data.requireStringKeyedMap("friendInviteClaim")
        check(currentUid() == uid) { "Your account changed. Reopen Messages." }
        return data.requireString("username")
    }

    /**
     * Watches the invitation for the account this repository was created for.
     *
     * @param code The invitation code currently shown to the account owner.
     * @param onUnavailable Runs when Firebase reports that the code changed or was claimed.
     * @return A subscription to close when observation ends, or an empty subscription if no account was supplied.
     */
    fun observeUnavailable(code: String, onUnavailable: () -> Unit): ChatSubscription {
        val uid = expectedUid ?: return ChatSubscription {}
        val listener = FirebaseFirestore.getInstance().collection("friendInviteOwners").document(uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null &&
                    (snapshot.getString("code") != code || snapshot.getString("claimedBy").orEmpty().isNotBlank())) {
                    onUnavailable()
                }
            }
        return ChatSubscription { listener.remove() }
    }

    /**
     * Checks that Firebase is signed into the account expected by this repository.
     *
     * @return The authenticated account's Firebase UID.
     * @throws IllegalStateException If no account is signed in or the authenticated account differs from the expected one.
     */
    private fun currentUid(): String {
        val uid = checkNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Sign in first." }
        check(expectedUid == null || uid == expectedUid) { "Sign in again to reconnect this account." }
        return uid
    }
}

/**
 * Requests server authorization before resolving a known participant's messaging keys.
 *
 * @param username The participant's username. The request uses its trimmed, lowercase form.
 */
internal suspend fun authorizeMessagingPeer(username: String) {
    FirebaseFunctions.getInstance("northamerica-northeast1").getHttpsCallable("messagingAuthorizePeer")
        .call(mapOf("username" to username.trim().lowercase())).await()
}

internal data class RecoveredChatAccess(
    val conversation: ChatConversation,
    val wrappedRoomKey: String,
)

internal data class ChatAccessRecoveryResult(
    val recovered: List<RecoveredChatAccess>,
    val failedIds: Set<String>,
)

/** Restores current-device chat envelopes without reading or downloading message ciphertext. */
internal class ChatAccountAccessRecovery(
    private val firestore: FirebaseFirestore,
) {
    /**
     * Finds conversations whose account wrapper can restore a missing current-device room key.
     *
     * @param conversations Conversations currently visible to the signed-in account.
     * @param currentUid Active Firebase account identifier.
     * @return Server-confirmed conversations that can be recovered without another device.
     */
    suspend fun candidates(
        conversations: List<ChatConversation>,
        currentUid: String,
    ): List<ChatConversation> = coroutineScope {
        val deviceKeyId = "$currentUid--${KeyManager.deviceId()}"
        val gate = Semaphore(4)
        conversations.distinctBy(ChatConversation::id).map { conversation ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val snapshot = firestore.collection("conversations").document(conversation.id)
                            .get(Source.SERVER).await()
                        val participants = snapshot.participantIds()
                        if (currentUid !in participants || participants != conversation.participantIds.toSet()) {
                            return@runCatching null
                        }
                        if (snapshot.accountRecoveryKeys()[currentUid] == null) return@runCatching null
                        val deviceKey = snapshot.reference.collection("deviceKeys")
                            .document(deviceKeyId).get(Source.SERVER).await()
                        conversation.takeIf { deviceKey.getString("wrappedKey").isNullOrBlank() }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Restores only this device's room-key envelopes with one unlocked account keyset.
     *
     * @param conversations Server-visible conversations selected for recovery.
     * @param currentUid Active Firebase account identifier.
     * @param keyset Account private key held only for this foreground recovery operation.
     * @return Successfully recovered cache access and isolated conversation failures.
     */
    suspend fun restore(
        conversations: List<ChatConversation>,
        currentUid: String,
        keyset: UnlockedAccountKeyset,
    ): ChatAccessRecoveryResult = coroutineScope {
        val localPublicKey = KeyManager.getOrCreatePublicKey()
        val encodedLocalKey = Base64.getEncoder().encodeToString(localPublicKey.encoded)
        val deviceId = KeyManager.deviceId(localPublicKey)
        val gate = Semaphore(4)
        val candidates = conversations.distinctBy(ChatConversation::id)
        val outcomes = candidates.map { conversation ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val reference = firestore.collection("conversations").document(conversation.id)
                        val snapshot = reference.get(Source.SERVER).await()
                        val participants = snapshot.participantIds()
                        check(currentUid in participants && participants == conversation.participantIds.toSet()) {
                            "Conversation membership changed"
                        }
                        val currentDevice = reference.collection("deviceKeys")
                            .document("$currentUid--$deviceId")
                        currentDevice.get(Source.SERVER).await().getString("wrappedKey")
                            ?.takeIf(String::isNotBlank)?.let { existing ->
                                return@runCatching RecoveredChatAccess(conversation, existing)
                            }
                        val roomKey = checkNotNull(snapshot.accountRecoveryKeys()[currentUid]).unwrap(keyset)
                        val username = checkNotNull(conversation.participantUsernames[currentUid])
                            .trim().lowercase()
                        val device = firestore.collection("messageDirectory").document(username)
                            .collection("devices").document(deviceId).get(Source.SERVER).await()
                        check(device.getString("uid") == currentUid && device.getString("deviceId") == deviceId) {
                            "Current device is not registered for this account"
                        }
                        check(device.getString("publicKey") == encodedLocalKey) {
                            "Registered device key does not match this device"
                        }
                        val digest = if (device.getString("mgf1Digest") == "SHA-256") {
                            MGF1ParameterSpec.SHA256
                        } else MGF1ParameterSpec.SHA1
                        val wrapped = KeyManager.wrapKey(roomKey, localPublicKey, digest)
                        currentDevice.set(
                            mapOf(
                                "uid" to currentUid,
                                "deviceId" to deviceId,
                                "publicKey" to encodedLocalKey,
                                "wrappedKey" to wrapped,
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                        ).await()
                        RecoveredChatAccess(conversation, wrapped)
                    }
                }
            }
        }.awaitAll()
        val recovered = outcomes.mapNotNull(Result<RecoveredChatAccess>::getOrNull)
        val restoredIds = recovered.mapTo(mutableSetOf()) { it.conversation.id }
        ChatAccessRecoveryResult(
            recovered = recovered,
            failedIds = candidates.mapTo(mutableSetOf(), ChatConversation::id) - restoredIds,
        )
    }

    private fun DocumentSnapshot.participantIds(): Set<String> =
        (get("participantIds") as? List<*>).orEmpty().filterIsInstance<String>().toSet()

    private fun DocumentSnapshot.accountRecoveryKeys(): Map<String, AccountKeysetWrappedKey> =
        (get("accountRoomKeys") as? Map<*, *>)?.mapNotNull { (uid, value) ->
            val owner = uid as? String ?: return@mapNotNull null
            val fields = value as? Map<*, *> ?: return@mapNotNull null
            runCatching { owner to AccountKeysetWrappedKey.fromMap(fields) }.getOrNull()
        }?.toMap().orEmpty()
}
