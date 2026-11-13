// All chat roles: loads and saves direct messages and friend requests.
package com.olive.app.data.messaging

import com.google.firebase.firestore.DocumentSnapshot
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
import com.google.firebase.firestore.Source
import com.olive.app.data.account.AccountIdentity
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject


class MessagingRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun prepareSupportTickets() {
        FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable("supportTicketsPrepare").call().await()
    }
    // Publishes this account's messaging key and device details to Firebase.
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

    // Searches Firebase for an exact username that may be messaged.
    suspend fun searchUsers(query: String, currentUid: String): List<DirectoryUser> {
        val normalized = query.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Enter the exact 4-24 character username." }
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

    // Streams friend requests, accepted friends, and directory changes for one signed-in account.
    fun observeSocial(
        uid: String,
        onUpdate: (SocialSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        var friends = emptyList<Friend>()
        var incoming = emptyList<FriendRequest>()
        var outgoing = emptyList<FriendRequest>()
        // Sends the newest friend and request lists after any listener changes.
        fun publish() = onUpdate(SocialSnapshot(friends, incoming, outgoing))
        val listeners = mutableListOf<ListenerRegistration>()
        listeners += firestore.collection("friendLists").document(uid).collection("friends")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load friends."
                    )
                    return@addSnapshotListener
                }
                friends =
                    snapshot?.documents.orEmpty().mapNotNull(::friendFrom).sortedBy { it.username }
                publish()
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("recipientUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load incoming requests."
                    )
                    return@addSnapshotListener
                }
                incoming = snapshot?.documents.orEmpty().mapNotNull(::requestFrom)
                    .sortedByDescending { it.createdAt }
                publish()
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("senderUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(
                        error.localizedMessage ?: "Unable to load sent requests."
                    )
                    return@addSnapshotListener
                }
                outgoing = snapshot?.documents.orEmpty().mapNotNull(::requestFrom)
                    .sortedByDescending { it.createdAt }
                publish()
            }
        return ChatSubscription { listeners.forEach(ListenerRegistration::remove) }
    }

    // Creates a pending request after confirming both accounts and their roles may connect.
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

    // Accepts a friend request and creates the shared friendship record.
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

    // Declines an incoming friend request and removes its pending record.
    suspend fun declineRequest(currentUid: String, request: FriendRequest) {
        require(request.recipientUid == currentUid) { "Only the recipient can decline this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    // Removes an outgoing request only when the signed-in account originally sent it.
    suspend fun cancelRequest(currentUid: String, request: FriendRequest) {
        require(request.senderUid == currentUid) { "Only the sender can cancel this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    // Opens the existing direct chat with a friend or creates a blank one.
    suspend fun conversationWith(current: AccountIdentity, friend: Friend): ChatConversation {
        require(current.uid != friend.uid) { "Choose another user." }
        val friendship =
            firestore.collection("friendships").document(friend.friendshipId).get().await()
        check(friendship.exists()) { "This friendship is no longer available." }
        val ids =
            (friendship.get("participantIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
        check(ids.size == 2 && current.uid in ids && friend.uid in ids) { "Only friends can open this conversation." }
        val names =
            (friendship.get("participantUsernames") as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }?.toMap().orEmpty()
        val ref = firestore.collection("conversations").document(friend.friendshipId)
        val existing = ref.get().await()
        if (existing.exists()) return checkNotNull(conversationFrom(existing))
        ref.set(directConversationData(friend.friendshipId, ids, names)).await()
        return ChatConversation(friend.friendshipId, friend.friendshipId, ids, names)
    }

    // Streams direct and support conversations visible to the signed-in participant.
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

    // Loads one server-confirmed conversation for durable attachment authorization.
    suspend fun getConversation(id: String): ChatConversation? =
        conversationFrom(firestore.collection("conversations").document(id)
            .get(Source.SERVER).await())

    // Loads a server-confirmed conversation index for background membership and key reconciliation.
    suspend fun listConversations(currentUid: String): List<ChatConversation> =
        firestore.collection("conversations").whereArrayContains("participantIds", currentUid)
            .get(Source.SERVER).await().documents
            .mapNotNull(::conversationFrom)
            .sortedByDescending(ChatConversation::updatedAt)

    // Loads incoming and outgoing friend requests for this account.
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

    // Converts a Firestore friend record into the app friend model.
    private fun friendFrom(row: DocumentSnapshot): Friend? {
        val friendshipId = row.getString("friendshipId") ?: return null
        val friendUid = row.getString("friendUid") ?: return null
        val friendUsername = row.getString("friendUsername") ?: return null
        return Friend(friendshipId, friendUid, friendUsername)
    }

    // Converts a Firestore chat record into the app conversation model.
    private fun conversationFrom(row: DocumentSnapshot): ChatConversation? {
        val ids = (row.get("participantIds") as? List<*>)?.filterIsInstance<String>() ?: return null
        val names =
            (row.get("participantUsernames") as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }?.toMap().orEmpty()
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

    // Messaging: Requests id.
    private fun requestId(senderUid: String, recipientUid: String) = "$senderUid--$recipientUid"

    // Loads the direct conversation data needed by secure messaging.
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

    // Creates the same friendship ID regardless of which username comes first.
    private fun friendshipId(firstUid: String, secondUid: String) =
        listOf(firstUid, secondUid).sorted().joinToString("--")

    // Wraps a Firebase listener so the screen can close it safely.
    private fun ListenerRegistration.asSubscription() = ChatSubscription { remove() }
}

// All chat roles: persists queued ciphertext across process death and connectivity changes.

internal interface MessageOutboxVault {
    fun read(): JSONObject
    fun update(change: (JSONObject) -> Unit)
}

private class EncryptedMessageOutboxVault(context: Context) : MessageOutboxVault {
    private val vault = EncryptedVault(context, "message-outbox")
    override fun read() = vault.read()
    override fun update(change: (JSONObject) -> Unit) = vault.update(change)
}

/** Account-isolated access to the Keystore-encrypted outgoing-message vault. */
class MessageOutboxStore internal constructor(private val vault: MessageOutboxVault) {
    constructor(context: Context) : this(EncryptedMessageOutboxVault(context.applicationContext))

    fun get(id: String): OutgoingMessage? {
        val accounts = vault.read().optJSONObject(ACCOUNTS) ?: return null
        val owner = accounts.keys().asSequence().firstOrNull { uid ->
            accounts.optJSONObject(uid)?.optJSONObject(MESSAGES)?.has(id) == true
        } ?: return null
        return all(owner).firstOrNull { it.id == id }
    }

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

    /** Assigns and persists the next sequence atomically with the ciphertext record. */
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

    /** The first unresolved message is a hard barrier for every later sequence. */
    fun head(ownerUid: String, conversationId: String): OutgoingMessage? = all(ownerUid)
        .asSequence()
        .filter { it.conversationId == conversationId && it.status != MessageDeliveryStatus.SENT }
        .minByOrNull(OutgoingMessage::clientSequence)

    fun drainableConversationIds(ownerUid: String): Set<String> = all(ownerUid)
        .asSequence()
        .filter { it.status != MessageDeliveryStatus.SENT }
        .groupBy(OutgoingMessage::conversationId)
        .mapNotNullTo(linkedSetOf()) { (conversationId, messages) ->
            messages.minByOrNull(OutgoingMessage::clientSequence)
                ?.takeUnless { it.status == MessageDeliveryStatus.FAILED_PERMANENT }
                ?.let { conversationId }
        }

    fun remove(ownerUid: String, id: String) {
        vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
                ?.optJSONObject(MESSAGES)?.remove(id)
        }
        changes.tryEmit(ownerUid)
    }

    fun purge(ownerUid: String) {
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.remove(ownerUid) }
        changes.tryEmit(ownerUid)
    }

    /** Emits an initial snapshot and each process-local update made by the UI or worker. */
    fun observe(ownerUid: String): Flow<List<OutgoingMessage>> = changes
        .filter { it == ownerUid }
        .map { all(ownerUid) }
        .onStart { emit(all(ownerUid)) }

    private fun messages(account: JSONObject): JSONObject =
        account.child(MESSAGES)

    private fun counters(account: JSONObject): JSONObject =
        account.child(SEQUENCES)

    private fun readMessages(ownerUid: String): List<OutgoingMessage> {
        val messages = vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
            ?.optJSONObject(MESSAGES) ?: return emptyList()
        return messages.keys().asSequence().mapNotNull { id ->
            messages.optJSONObject(id)?.let(::safeMessage)
        }.toList()
    }

    /** Gives records written by the previous schema a deterministic order before new sends. */
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

    private fun safeMessage(value: JSONObject) = runCatching { OutgoingMessage.fromJson(value) }.getOrNull()

    private companion object {
        const val ACCOUNTS = "accounts"
        const val MESSAGES = "messages"
        const val SEQUENCES = "lastClientSequences"
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    }
}
