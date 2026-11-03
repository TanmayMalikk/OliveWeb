// All chat roles: stores encrypted chats between allowed participants.
package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.SecretKey

/** Stores message bodies with a per-conversation AES key wrapped for each participant. */
class SecureChatRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    // Opens the copy of an existing room key that was encrypted for this account.
    suspend fun existingRoomKey(conversationId: String, currentUid: String): SecretKey? {
        val snapshot = firestore.collection("conversations").document(conversationId).get().await()
        val deviceId = KeyManager.deviceId()
        val deviceKey = snapshot.reference.collection("deviceKeys")
            .document("$currentUid--$deviceId").get().await().getString("wrappedKey")
        val wrappedKey = deviceKey ?: snapshot.roomKeys().orEmpty()[currentUid] ?: return null
        return KeyManager.unwrapKey(wrappedKey)
    }

    // Creates or unwraps the encrypted room key required before this participant can read messages.
    suspend fun ensureRoomKey(
        conversation: ChatConversation, currentUid: String,
        participantPublicKeys: Map<String, PublicKey>
    ): SecretKey {
        require(participantPublicKeys.keys.containsAll(conversation.participantIds))
        val encodedPublicKeys = participantPublicKeys.mapValues { (_, key) ->
            java.util.Base64.getEncoder().encodeToString(key.encoded)
        }
        val ref = firestore.collection("conversations").document(conversation.id)
        val selected = firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val savedKeys = snapshot.participantPublicKeys().orEmpty()
            val savedWrapped = snapshot.roomKeys().orEmpty()
            if (savedKeys.isEmpty() && savedWrapped.isEmpty()) {
                val generated = EncryptionUtils.generateSymmetricKey()
                val wrapped = participantPublicKeys.mapValues { (_, key) ->
                    KeyManager.wrapKey(generated, key)
                }
                transaction.update(
                    ref, mapOf(
                        "roomKeys" to wrapped, "participantPublicKeys" to encodedPublicKeys,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                wrapped.getValue(currentUid)
            } else {
                val ownWrappedKey = savedWrapped[currentUid]
                    ?: error("An existing participant must open this chat to share its key.")
                if (savedKeys == encodedPublicKeys && savedWrapped.keys.containsAll(conversation.participantIds)) {
                    ownWrappedKey
                } else {
                    // Rewrap the same AES key for new participants and changed device keys.
                    val existingKey = KeyManager.unwrapKey(ownWrappedKey)
                    val updatedWrapped = participantPublicKeys.mapValues { (uid, publicKey) ->
                        if (savedKeys[uid] == encodedPublicKeys[uid]) savedWrapped[uid]
                            ?: KeyManager.wrapKey(existingKey, publicKey)
                        else KeyManager.wrapKey(existingKey, publicKey)
                    }
                    transaction.update(
                        ref, mapOf(
                            "roomKeys" to updatedWrapped,
                            "participantPublicKeys" to encodedPublicKeys,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    updatedWrapped.getValue(currentUid)
                }
            }
        }.await()
        val roomKey = KeyManager.unwrapKey(selected)
        shareWithRegisteredDevices(conversation, roomKey)
        return roomKey
    }

    // Secure messaging: gives each registered phone or browser its own wrapped copy of the room key.
    private suspend fun shareWithRegisteredDevices(
        conversation: ChatConversation,
        roomKey: SecretKey
    ) {
        val conversationRef = firestore.collection("conversations").document(conversation.id)
        val batch = firestore.batch()
        var writes = 0
        conversation.participantIds.forEach { uid ->
            val username = conversation.participantUsernames[uid] ?: return@forEach
            val devices = firestore.collection("messageDirectory").document(username)
                .collection("devices").get().await()
            devices.documents.forEach { device ->
                val deviceId = device.getString("deviceId") ?: return@forEach
                val encoded = device.getString("publicKey") ?: return@forEach
                val publicKey = runCatching { KeyManager.decodePublicKey(encoded) }.getOrNull()
                    ?: return@forEach
                val digest = if (device.getString("mgf1Digest") == "SHA-256")
                    MGF1ParameterSpec.SHA256 else MGF1ParameterSpec.SHA1
                batch.set(
                    conversationRef.collection("deviceKeys").document("$uid--$deviceId"),
                    mapOf(
                        "uid" to uid,
                        "deviceId" to deviceId,
                        "publicKey" to encoded,
                        "wrappedKey" to KeyManager.wrapKey(roomKey, publicKey, digest),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                )
                writes++
            }
        }
        if (writes > 0) batch.commit().await()
    }

    // Encrypts and sends one chat message to every current participant.
    suspend fun send(
        conversationId: String,
        sender: AccountIdentity,
        text: String,
        roomKey: SecretKey
    ) {
        val body = text.trim().filter { it == '\n' || it == '\t' || it >= ' ' }
        require(body.isNotEmpty())
        require(body.length <= 2_000)
        val conversation = firestore.collection("conversations").document(conversationId)
        val message = conversation.collection("messages").document()
        val batch = firestore.batch()
        batch.set(
            message, mapOf(
                "senderUid" to sender.uid,
                "senderUsername" to sender.username.lowercase(),
                "ciphertext" to EncryptionUtils.encryptText(body, roomKey),
                "deleted" to false,
                "sentAt" to FieldValue.serverTimestamp(),
                "clientSentAt" to System.currentTimeMillis()
            )
        )
        batch.update(
            conversation, mapOf(
                "lastMessage" to "Secure message", "lastMessageId" to message.id,
                "lastSenderUid" to sender.uid, "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    // Streams encrypted chat records and decrypts each message with the active room key.
    fun observeMessages(
        conversationId: String, roomKey: SecretKey, onUpdate: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        val listener = firestore.collection("conversations").document(conversationId)
            .collection("messages").orderBy("sentAt").limitToLast(250)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                onUpdate(snapshot?.documents.orEmpty().mapNotNull { row ->
                    val sender = row.getString("senderUid") ?: return@mapNotNull null
                    val deleted = row.getBoolean("deleted") ?: false
                    val body = when {
                        deleted -> ""
                        row.getString("ciphertext") != null -> runCatching {
                            EncryptionUtils.decryptText(
                                row.getString("ciphertext").orEmpty(),
                                roomKey
                            )
                        }.getOrDefault("")

                        else -> ""
                    }
                    ChatMessage(
                        row.id, sender, row.getString("senderUsername").orEmpty(), body,
                        row.getTimestamp("sentAt")?.toDate()?.time ?: row.getLong("clientSentAt")
                        ?: 0L, deleted
                    )
                })
            }
        return ChatSubscription { listener.remove() }
    }

    // Removes  from secure messaging.
    suspend fun unsend(conversationId: String, message: ChatMessage, currentUid: String) {
        require(message.senderUid == currentUid && !message.unsent)
        val conversation = firestore.collection("conversations").document(conversationId)
        val messageRef = conversation.collection("messages").document(message.id)
        firestore.runTransaction { transaction ->
            val savedMessage = transaction.get(messageRef)
            val savedConversation = transaction.get(conversation)
            check(savedMessage.getString("senderUid") == currentUid && savedMessage.getBoolean("deleted") != true)
            val updates =
                mutableMapOf("deleted" to true, "deletedAt" to FieldValue.serverTimestamp())
            if (savedMessage.contains("ciphertext")) updates["ciphertext"] =
                "Message unsent" else updates["body"] = "Message unsent"
            transaction.update(messageRef, updates)
            if (savedConversation.getString("lastMessageId") == message.id) transaction.update(
                conversation, mapOf(
                    "lastMessage" to "Secure message", "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    // Loads and unwraps the room key used to decrypt this conversation.
    @Suppress("UNCHECKED_CAST")
    private fun com.google.firebase.firestore.DocumentSnapshot.roomKeys() =
        get("roomKeys") as? Map<String, String>

    // Loads each participant's public key before sharing a room key.
    @Suppress("UNCHECKED_CAST")
    private fun com.google.firebase.firestore.DocumentSnapshot.participantPublicKeys() =
        get("participantPublicKeys") as? Map<String, String>
}
