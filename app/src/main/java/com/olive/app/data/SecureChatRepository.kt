package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import java.security.PublicKey
import javax.crypto.SecretKey

/** Stores message bodies with a per-conversation AES key wrapped for each participant. */
class SecureChatRepository(private val firestore:FirebaseFirestore=FirebaseFirestore.getInstance()) {
    suspend fun ensureRoomKey(conversation:ChatConversation,currentUid:String,
        participantPublicKeys:Map<String,PublicKey>):SecretKey {
        require(participantPublicKeys.keys.containsAll(conversation.participantIds))
        val generated=EncryptionUtils.generateSymmetricKey()
        val wrapped=participantPublicKeys.mapValues { (_,key) -> KeyManager.wrapKey(generated,key) }
        val encodedPublicKeys=participantPublicKeys.mapValues { (_,key) ->
            java.util.Base64.getEncoder().encodeToString(key.encoded)
        }
        val ref=firestore.collection("conversations").document(conversation.id)
        val selected=firestore.runTransaction { transaction ->
            val snapshot=transaction.get(ref)
            val savedKeys=snapshot.participantPublicKeys()
            if(savedKeys==encodedPublicKeys) snapshot.wrappedKey(currentUid) ?: error("Secure conversation key unavailable.")
            else {
                transaction.update(ref,mapOf("roomKeys" to wrapped,"participantPublicKeys" to encodedPublicKeys,
                    "updatedAt" to FieldValue.serverTimestamp()))
                wrapped.getValue(currentUid)
            }
        }.await()
        return KeyManager.unwrapKey(selected)
    }

    suspend fun send(conversationId:String,sender:AccountIdentity,text:String,roomKey:SecretKey) {
        val body=text.trim().filter { it=='\n' || it=='\t' || it>=' ' }
        require(body.isNotEmpty())
        require(body.length<=2_000)
        val conversation=firestore.collection("conversations").document(conversationId)
        val message=conversation.collection("messages").document()
        val batch=firestore.batch()
        batch.set(message,mapOf("senderUid" to sender.uid,"senderUsername" to sender.username.lowercase(),
            "ciphertext" to EncryptionUtils.encryptText(body,roomKey),"deleted" to false,
            "sentAt" to FieldValue.serverTimestamp(),"clientSentAt" to System.currentTimeMillis()))
        batch.update(conversation,mapOf("lastMessage" to "Secure message","lastMessageId" to message.id,
            "lastSenderUid" to sender.uid,"updatedAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
    }

    fun observeMessages(conversationId:String,roomKey:SecretKey,onUpdate:(List<ChatMessage>)->Unit,
        onError:(String)->Unit):ChatSubscription {
        val listener=firestore.collection("conversations").document(conversationId)
            .collection("messages").orderBy("sentAt").limitToLast(250).addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage.orEmpty());return@addSnapshotListener }
                onUpdate(snapshot?.documents.orEmpty().mapNotNull { row ->
                    val sender=row.getString("senderUid") ?: return@mapNotNull null
                    val deleted=row.getBoolean("deleted") ?: false
                    val body=when {
                        deleted -> ""
                        row.getString("ciphertext")!=null -> runCatching {
                            EncryptionUtils.decryptText(row.getString("ciphertext").orEmpty(),roomKey)
                        }.getOrDefault("")
                        else -> ""
                    }
                    ChatMessage(row.id,sender,row.getString("senderUsername").orEmpty(),body,
                        row.getTimestamp("sentAt")?.toDate()?.time ?: row.getLong("clientSentAt") ?: 0L,deleted)
                })
            }
        return ChatSubscription { listener.remove() }
    }

    suspend fun unsend(conversationId:String,message:ChatMessage,currentUid:String) {
        require(message.senderUid==currentUid && !message.unsent)
        val conversation=firestore.collection("conversations").document(conversationId)
        val messageRef=conversation.collection("messages").document(message.id)
        firestore.runTransaction { transaction ->
            val savedMessage=transaction.get(messageRef)
            val savedConversation=transaction.get(conversation)
            check(savedMessage.getString("senderUid")==currentUid && savedMessage.getBoolean("deleted")!=true)
            val updates=mutableMapOf("deleted" to true,"deletedAt" to FieldValue.serverTimestamp())
            if(savedMessage.contains("ciphertext")) updates["ciphertext"]="Message unsent" else updates["body"]="Message unsent"
            transaction.update(messageRef,updates)
            if(savedConversation.getString("lastMessageId")==message.id) transaction.update(conversation,mapOf(
                "lastMessage" to "Secure message","updatedAt" to FieldValue.serverTimestamp()))
        }.await()
    }

    @Suppress("UNCHECKED_CAST")
    private fun com.google.firebase.firestore.DocumentSnapshot.wrappedKey(uid:String)=
        (get("roomKeys") as? Map<String,String>)?.get(uid)

    @Suppress("UNCHECKED_CAST")
    private fun com.google.firebase.firestore.DocumentSnapshot.participantPublicKeys()=
        get("participantPublicKeys") as? Map<String,String>
}
