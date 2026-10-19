package com.olive.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.olive.app.security.KeyManager
import java.util.Base64

class MessagingRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    init {
        firestore.firestoreSettings=FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
    }

    suspend fun registerUser(identity: AccountIdentity) {
        val username=identity.username.trim().lowercase()
        val publicKey=withContext(Dispatchers.Default) { KeyManager.getOrCreatePublicKey() }
        firestore.collection("messageDirectory").document(username).set(mapOf(
            "uid" to identity.uid,
            "username" to username,
            "publicKey" to Base64.getEncoder().encodeToString(publicKey.encoded),
            "updatedAt" to FieldValue.serverTimestamp(),
        ),SetOptions.merge()).await()
    }

    suspend fun searchUsers(query: String, currentUid: String): List<DirectoryUser> {
        val normalized=query.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Enter the exact 4-24 character username." }
        val row=firestore.collection("messageDirectory").document(normalized).get().await()
        val uid=row.getString("uid") ?: return emptyList()
        val username=row.getString("username") ?: return emptyList()
        val publicKey=row.getString("publicKey").orEmpty()
        return if(uid==currentUid) emptyList() else listOf(DirectoryUser(uid,username,publicKey))
    }

    fun observeSocial(uid: String, onUpdate: (SocialSnapshot)->Unit, onError: (String)->Unit): ChatSubscription {
        var friends=emptyList<Friend>()
        var incoming=emptyList<FriendRequest>()
        var outgoing=emptyList<FriendRequest>()
        fun publish()=onUpdate(SocialSnapshot(friends,incoming,outgoing))
        val listeners=mutableListOf<ListenerRegistration>()
        listeners += firestore.collection("friendLists").document(uid).collection("friends")
            .addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage ?: "Unable to load friends.");return@addSnapshotListener }
                friends=snapshot?.documents.orEmpty().mapNotNull(::friendFrom).sortedBy { it.username }
                publish()
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("recipientUid",uid)
            .addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage ?: "Unable to load incoming requests.");return@addSnapshotListener }
                incoming=snapshot?.documents.orEmpty().mapNotNull(::requestFrom).sortedByDescending { it.createdAt }
                publish()
            }
        listeners += firestore.collection("friendRequests").whereEqualTo("senderUid",uid)
            .addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage ?: "Unable to load sent requests.");return@addSnapshotListener }
                outgoing=snapshot?.documents.orEmpty().mapNotNull(::requestFrom).sortedByDescending { it.createdAt }
                publish()
            }
        return ChatSubscription { listeners.forEach(ListenerRegistration::remove) }
    }

    suspend fun sendFriendRequest(current: AccountIdentity, other: DirectoryUser) {
        require(current.uid!=other.uid) { "You cannot add yourself." }
        require(other.username.matches(Regex("[a-z0-9_]{4,24}"))) { "That username is not valid." }
        val forward=firestore.collection("friendRequests").document(requestId(current.uid,other.uid))
        // Rules reject duplicates, reverse requests, and existing friendships atomically without private missing-document reads.
        forward.set(mapOf(
            "senderUid" to current.uid,
            "senderUsername" to current.username.lowercase(),
            "recipientUid" to other.uid,
            "recipientUsername" to other.username,
            "friendshipId" to friendshipId(current.uid,other.uid),
            "createdAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    suspend fun acceptRequest(current: AccountIdentity, request: FriendRequest) {
        require(request.recipientUid==current.uid) { "Only the recipient can accept this request." }
        val requestRef=firestore.collection("friendRequests").document(request.id)
        val friendshipId=friendshipId(current.uid,request.senderUid)
        val friendshipRef=firestore.collection("friendships").document(friendshipId)
        val currentFriendRef=firestore.collection("friendLists").document(current.uid).collection("friends").document(friendshipId)
        val senderFriendRef=firestore.collection("friendLists").document(request.senderUid).collection("friends").document(friendshipId)
        val conversationRef=firestore.collection("conversations").document(friendshipId)
        val participantIds=listOf(current.uid,request.senderUid).sorted()
        val participantNames=mapOf(current.uid to current.username.lowercase(),request.senderUid to request.senderUsername)
        val batch=firestore.batch()
        batch.set(friendshipRef,mapOf("participantIds" to participantIds,"participantUsernames" to participantNames,
            "acceptedRequestId" to request.id,"createdAt" to FieldValue.serverTimestamp()))
        batch.set(currentFriendRef,mapOf("friendshipId" to friendshipId,"friendUid" to request.senderUid,
            "friendUsername" to request.senderUsername,"createdAt" to FieldValue.serverTimestamp()))
        batch.set(senderFriendRef,mapOf("friendshipId" to friendshipId,"friendUid" to current.uid,
            "friendUsername" to current.username.lowercase(),"createdAt" to FieldValue.serverTimestamp()))
        batch.set(conversationRef,mapOf("friendshipId" to friendshipId,"participantIds" to participantIds,
            "participantUsernames" to participantNames,"createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),"lastMessage" to "","lastMessageId" to "",
            "lastSenderUid" to ""))
        batch.delete(requestRef)
        batch.commit().await()
    }

    suspend fun declineRequest(currentUid: String, request: FriendRequest) {
        require(request.recipientUid==currentUid) { "Only the recipient can decline this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    suspend fun cancelRequest(currentUid: String, request: FriendRequest) {
        require(request.senderUid==currentUid) { "Only the sender can cancel this request." }
        firestore.collection("friendRequests").document(request.id).delete().await()
    }

    suspend fun conversationWith(current: AccountIdentity, friend: Friend): ChatConversation {
        require(current.uid!=friend.uid) { "Choose another user." }
        val friendship=firestore.collection("friendships").document(friend.friendshipId).get().await()
        check(friendship.exists()) { "This friendship is no longer available." }
        val ids=(friendship.get("participantIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
        check(ids.size==2 && current.uid in ids && friend.uid in ids) { "Only friends can open this conversation." }
        val names=(friendship.get("participantUsernames") as? Map<*,*>)?.entries?.mapNotNull { (key,value) ->
            if(key is String && value is String) key to value else null
        }?.toMap().orEmpty()
        val ref=firestore.collection("conversations").document(friend.friendshipId)
        if(!ref.get().await().exists()) ref.set(mapOf(
                "friendshipId" to friend.friendshipId,
                "participantIds" to ids.sorted(),
                "participantUsernames" to names,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastMessage" to "",
                "lastMessageId" to "",
                "lastSenderUid" to "",
            )).await()
        return ChatConversation(friend.friendshipId,friend.friendshipId,ids,names)
    }

    fun observeConversations(friendshipIds: List<String>, onUpdate: (List<ChatConversation>)->Unit, onError: (String)->Unit): ChatSubscription {
        if(friendshipIds.isEmpty()) { onUpdate(emptyList());return ChatSubscription {} }
        val rows=mutableMapOf<String,ChatConversation>()
        val listeners=friendshipIds.distinct().map { id ->
            firestore.collection("conversations").document(id).addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage ?: "Unable to load conversations.");return@addSnapshotListener }
                val conversation=snapshot?.takeIf(DocumentSnapshot::exists)?.let(::conversationFrom)
                if(conversation==null) rows.remove(id) else rows[id]=conversation
                onUpdate(rows.values.sortedByDescending { it.updatedAt })
            }
        }
        return ChatSubscription { listeners.forEach(ListenerRegistration::remove) }
    }

    fun observeMessages(conversationId: String, onUpdate: (List<ChatMessage>)->Unit, onError: (String)->Unit): ChatSubscription {
        val listener=firestore.collection("conversations").document(conversationId).collection("messages")
            .orderBy("sentAt").limitToLast(250).addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage ?: "Unable to load messages.");return@addSnapshotListener }
                onUpdate(snapshot?.documents.orEmpty().mapNotNull { row ->
                    val sender=row.getString("senderUid") ?: return@mapNotNull null
                    val deleted=row.getBoolean("deleted") ?: false
                    ChatMessage(row.id,sender,row.getString("senderUsername").orEmpty(),
                        if(deleted) "Message unsent" else row.getString("body").orEmpty(),
                        row.getTimestamp("sentAt")?.toDate()?.time ?: row.getLong("clientSentAt") ?: 0L,deleted)
                })
            }
        return listener.asSubscription()
    }

    suspend fun send(conversationId: String, sender: AccountIdentity, text: String) {
        val body=sanitizeMessage(text)
        require(body.isNotEmpty()) { "Write a message first." }
        require(body.length<=2_000) { "Messages can be up to 2,000 characters." }
        val conversation=firestore.collection("conversations").document(conversationId)
        val message=conversation.collection("messages").document()
        val batch=firestore.batch()
        batch.set(message,mapOf(
            "senderUid" to sender.uid,
            "senderUsername" to sender.username.lowercase(),
            "body" to body,
            "deleted" to false,
            "sentAt" to FieldValue.serverTimestamp(),
            "clientSentAt" to System.currentTimeMillis(),
        ))
        batch.update(conversation,mapOf("lastMessage" to body.take(160),"lastMessageId" to message.id,
            "lastSenderUid" to sender.uid,
            "updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
    }

    suspend fun unsend(conversationId: String, message: ChatMessage, currentUid: String) {
        require(message.senderUid==currentUid) { "You can only unsend messages that you sent." }
        require(!message.unsent) { "This message is already unsent." }
        val conversation=firestore.collection("conversations").document(conversationId)
        val messageRef=conversation.collection("messages").document(message.id)
        firestore.runTransaction { transaction ->
            val savedMessage=transaction.get(messageRef)
            val savedConversation=transaction.get(conversation)
            check(savedMessage.exists() && savedMessage.getString("senderUid")==currentUid) { "You can only unsend messages that you sent." }
            check(savedMessage.getBoolean("deleted")!=true) { "This message is already unsent." }
            transaction.update(messageRef,mapOf("body" to "Message unsent","deleted" to true,"deletedAt" to FieldValue.serverTimestamp()))
            if(savedConversation.getString("lastMessageId")==message.id) transaction.update(conversation,
                mapOf("lastMessage" to "Message unsent","updatedAt" to FieldValue.serverTimestamp()))
        }.await()
    }

    private fun requestFrom(row: DocumentSnapshot): FriendRequest? {
        val senderUid=row.getString("senderUid") ?: return null
        val recipientUid=row.getString("recipientUid") ?: return null
        return FriendRequest(row.id,senderUid,row.getString("senderUsername").orEmpty(),recipientUid,
            row.getString("recipientUsername").orEmpty(),row.getTimestamp("createdAt")?.toDate()?.time ?: 0L)
    }

    private fun friendFrom(row: DocumentSnapshot): Friend? {
        val friendshipId=row.getString("friendshipId") ?: return null
        val friendUid=row.getString("friendUid") ?: return null
        val friendUsername=row.getString("friendUsername") ?: return null
        return Friend(friendshipId,friendUid,friendUsername)
    }

    private fun conversationFrom(row: DocumentSnapshot): ChatConversation? {
        val ids=(row.get("participantIds") as? List<*>)?.filterIsInstance<String>() ?: return null
        val names=(row.get("participantUsernames") as? Map<*,*>)?.entries?.mapNotNull { (key,value) ->
            if(key is String && value is String) key to value else null
        }?.toMap().orEmpty()
        val friendshipId=row.getString("friendshipId") ?: return null
        return ChatConversation(row.id,friendshipId,ids,names,row.getString("lastMessage").orEmpty(),
            row.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,row.getString("lastMessageId").orEmpty(),
            row.getString("lastSenderUid").orEmpty())
    }

    private fun requestId(senderUid: String, recipientUid: String)="$senderUid--$recipientUid"
    private fun friendshipId(firstUid: String, secondUid: String)=listOf(firstUid,secondUid).sorted().joinToString("--")
    private fun sanitizeMessage(value: String)=value.trim().filter { it=='\n' || it=='\t' || it>=' ' }
    private fun ListenerRegistration.asSubscription()=ChatSubscription { remove() }
}
