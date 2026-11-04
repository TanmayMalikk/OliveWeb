// All chat roles: stores encrypted chats between allowed participants.
package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
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
        return roomKeyAccess(conversationId, currentUid)?.secretKey
    }

    // Returns a verified envelope for protected offline retention.
    suspend fun wrappedRoomKey(conversationId: String, currentUid: String): String? {
        return roomKeyAccess(conversationId, currentUid)?.wrappedKeyBase64
    }

    // Tries the device envelope first, then the same account's recovery envelope.
    private suspend fun roomKeyAccess(
        conversationId: String,
        currentUid: String,
    ): KeyManager.UnwrappedKeyEnvelope? {
        val snapshot = firestore.collection("conversations").document(conversationId).get().await()
        val deviceId = KeyManager.deviceId()
        val deviceKey = snapshot.reference.collection("deviceKeys")
            .document("$currentUid--$deviceId").get().await().getString("wrappedKey")
        val candidates = listOfNotNull(deviceKey, snapshot.roomKeys().orEmpty()[currentUid])
        return runCatching { KeyManager.unwrapFirstWithEnvelope(candidates) }.getOrNull()
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
        val locallyAvailableKey = existingRoomKey(conversation.id, currentUid)
        val roomKey = firestore.runTransaction { transaction ->
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
                generated
            } else {
                val ownWrappedKey = savedWrapped[currentUid]
                    ?: error("An existing participant must open this chat to share its key.")
                val existingKey = locallyAvailableKey ?: KeyManager.unwrapKey(ownWrappedKey)
                if (savedKeys == encodedPublicKeys && savedWrapped.keys.containsAll(conversation.participantIds)) {
                    existingKey
                } else {
                    // Rewrap the same AES key for new participants and changed device keys.
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
                    existingKey
                }
            }
        }.await()
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
        deliver(prepareOutgoingMessage(conversationId, sender, text, roomKey))
    }

    /** Sends a previously encrypted outbox record using its deterministic idempotency key. */
    suspend fun deliver(outgoing: OutgoingMessage) {
        val conversation = firestore.collection("conversations").document(outgoing.conversationId)
        val message = conversation.collection("messages").document(outgoing.id)
        firestore.runTransaction { transaction ->
            val saved = transaction.get(message)
            if (saved.exists()) {
                check(saved.getString("senderUid") == outgoing.ownerUid)
                check(saved.getString("ciphertext") == outgoing.ciphertext)
                check(saved.getLong("clientSentAt") == outgoing.clientSentAt)
                return@runTransaction
            }
            transaction.set(message, mapOf(
                "senderUid" to outgoing.ownerUid,
                "senderUsername" to outgoing.senderUsername,
                "ciphertext" to outgoing.ciphertext,
                "deleted" to false,
                "sentAt" to FieldValue.serverTimestamp(),
                "clientSentAt" to outgoing.clientSentAt,
            ))
            transaction.update(conversation, mapOf(
                "lastMessage" to "Secure message", "lastMessageId" to message.id,
                "lastSenderUid" to outgoing.ownerUid, "updatedAt" to FieldValue.serverTimestamp()
            ))
        }.await()
    }

    // Streams encrypted chat records and decrypts each message with the active room key.
    fun observeMessages(
        conversationId: String, roomKey: SecretKey, onUpdate: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit,
        onServerCiphertext: ((List<EncryptedChatMessage>) -> Unit)? = null,
    ): ChatSubscription {
        val listener = firestore.collection("conversations").document(conversationId)
            .collection("messages").orderBy("sentAt").limitToLast(250)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                val encrypted = snapshot?.documents.orEmpty().mapNotNull { row ->
                    val sender = row.getString("senderUid") ?: return@mapNotNull null
                    EncryptedChatMessage(
                        id = row.id,
                        senderUid = sender,
                        senderUsername = row.getString("senderUsername").orEmpty(),
                        ciphertext = row.getString("ciphertext").orEmpty(),
                        sentAt = row.getTimestamp("sentAt")?.toDate()?.time
                            ?: row.getLong("clientSentAt") ?: 0L,
                        deleted = row.getBoolean("deleted") ?: false,
                    )
                }
                val decrypted = runCatching { decryptMessages(encrypted, roomKey) }.getOrElse {
                    onError("Encrypted message authentication failed.")
                    return@addSnapshotListener
                }
                if (snapshot?.metadata?.isFromCache != true || encrypted.isNotEmpty()) onUpdate(decrypted)
                if (snapshot != null && !snapshot.metadata.isFromCache) onServerCiphertext?.invoke(encrypted)
            }
        return ChatSubscription { listener.remove() }
    }

    // Authenticates ciphertext before any message is exposed to the UI.
    fun decryptMessages(messages: List<EncryptedChatMessage>, roomKey: SecretKey): List<ChatMessage> =
        messages.map { message ->
            require(message.deleted || message.ciphertext.isNotBlank()) { "Missing encrypted message body" }
            ChatMessage(
                id = message.id,
                senderUid = message.senderUid,
                senderUsername = message.senderUsername,
                body = if (message.deleted) "" else EncryptionUtils.decryptText(message.ciphertext, roomKey),
                sentAt = message.sentAt,
                unsent = message.deleted,
            )
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
