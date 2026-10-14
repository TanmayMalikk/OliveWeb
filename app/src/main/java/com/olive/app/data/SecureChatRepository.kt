package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import java.security.PublicKey
import javax.crypto.SecretKey

class SecureChatRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /**
     * Replaces the plaintext send function.
     */
    suspend fun sendSecureMessage(
        conversationId: String,
        sender: AccountIdentity,
        text: String,
        roomKey: SecretKey
    ) {
        val body = sanitizeMessage(text)
        require(body.isNotEmpty()) { "Write a message first." }
        require(body.length <= 2_000) { "Messages can be up to 2,000 characters." }

        val ciphertext = EncryptionUtils.encryptText(body, roomKey)

        val conversation = firestore.collection("conversations").document(conversationId)
        val message = conversation.collection("messages").document()
        val batch = firestore.batch()

        batch.set(
            message, mapOf(
                "senderUid" to sender.uid,
                "senderUsername" to sender.username.lowercase(),
                "ciphertext" to ciphertext,
                "deleted" to false,
                "sentAt" to FieldValue.serverTimestamp(),
                "clientSentAt" to System.currentTimeMillis()
            )
        )

        batch.update(
            conversation, mapOf(
                "lastMessage" to "Secure message",
                "lastMessageId" to message.id,
                "lastSenderUid" to sender.uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit().await()
    }

    /**
     * Replaces the plaintext observeMessages function.
     * Decrypts incoming ciphertext in real-time before passing it to the UI.
     */
    fun observeSecureMessages(
        conversationId: String,
        roomKey: SecretKey,
        onUpdate: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        val listener =
            firestore.collection("conversations").document(conversationId).collection("messages")
                .orderBy("sentAt").limitToLast(250).addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        onError(error.localizedMessage ?: "Unable to load messages.")
                        return@addSnapshotListener
                    }

                    onUpdate(snapshot?.documents.orEmpty().mapNotNull { row ->
                        val sender = row.getString("senderUid") ?: return@mapNotNull null
                        val deleted = row.getBoolean("deleted") ?: false

                        // Decrypt the payload safely
                        val bodyText = if (deleted) {
                            "Message unsent"
                        } else {
                            val ciphertext = row.getString("ciphertext") ?: ""
                            try {
                                if (ciphertext.isNotEmpty()) EncryptionUtils.decryptText(
                                    ciphertext,
                                    roomKey
                                ) else ""
                            } catch (e: Exception) {
                                "Encrypted message (Decryption failed)"
                            }
                        }

                        ChatMessage(
                            id = row.id,
                            senderUid = sender,
                            senderUsername = row.getString("senderUsername").orEmpty(),
                            body = bodyText,
                            sentAt = row.getTimestamp("sentAt")?.toDate()?.time
                                ?: row.getLong("clientSentAt") ?: 0L,
                            unsent = deleted
                        )
                    })
                }
        return ChatSubscription { listener.remove() }
    }

    /**
     * Replaces the plaintext unsend function.
     */
    suspend fun unsendSecureMessage(
        conversationId: String,
        message: ChatMessage,
        currentUid: String
    ) {
        require(message.senderUid == currentUid) { "You can only unsend messages that you sent." }
        require(!message.unsent) { "This message is already unsent." }

        val conversation = firestore.collection("conversations").document(conversationId)
        val messageRef = conversation.collection("messages").document(message.id)

        firestore.runTransaction { transaction ->
            val savedMessage = transaction.get(messageRef)
            val savedConversation = transaction.get(conversation)

            check(savedMessage.exists() && savedMessage.getString("senderUid") == currentUid) { "You can only unsend messages that you sent." }
            check(savedMessage.getBoolean("deleted") != true) { "This message is already unsent." }

            // Overwrite the ciphertext key instead of the body key
            transaction.update(
                messageRef, mapOf(
                    "ciphertext" to "Message unsent",
                    "deleted" to true,
                    "deletedAt" to FieldValue.serverTimestamp()
                )
            )

            if (savedConversation.getString("lastMessageId") == message.id) {
                transaction.update(
                    conversation, mapOf(
                        "lastMessage" to "Secure message",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()
    }

    private fun sanitizeMessage(value: String) =
        value.trim().filter { it == '\n' || it == '\t' || it >= ' ' }


    /**
     * Called the very first time a chat is created.
     * Generates the AES Room Key and wraps it for all participants.
     *
     * @param participantPublicKeys A map of User UIDs to their RSA Public Keys (including the KMS Master Key)
     */
    suspend fun initializeConversationKeys(
        conversationId: String,
        participantIds: List<String>,
        participantUsernames: Map<String, String>,
        participantPublicKeys: Map<String, java.security.PublicKey>
    ): javax.crypto.SecretKey {
        val roomKey = EncryptionUtils.generateSymmetricKey()
        val wrappedRoomKeysMap = mutableMapOf<String, String>()
        val encodedPublicKeysMap = mutableMapOf<String, String>()

        for ((uid, publicKey) in participantPublicKeys) {
            wrappedRoomKeysMap[uid] = KeyManager.wrapKey(roomKey, publicKey)
            encodedPublicKeysMap[uid] = java.util.Base64.getEncoder().encodeToString(publicKey.encoded)
        }

        // STRICT UPDATE: Only modify the exact fields allowed by your Firestore 'affectedKeys' rule
        firestore.collection("conversations").document(conversationId)
            .update(mapOf(
                "roomKeys" to wrappedRoomKeysMap,
                "participantPublicKeys" to encodedPublicKeysMap,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ))
            .await()

        return roomKey
    }

    /**
     * Called whenever a user opens an existing chat.
     * Pulls their specific wrapped key from Firestore and unwraps it using their hardware Private Key.
     */
    suspend fun loadRoomKey(conversationId: String, currentUid: String): javax.crypto.SecretKey {
        val snapshot = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("conversations")
            .document(conversationId)
            .get()
            .await()

        // CRITICAL: This string must be exactly "roomKeys" to match the database
        val roomKeysMap = snapshot.get("roomKeys") as? Map<String, String>
            ?: throw Exception("Room keys have not been initialized.")

        val wrappedKeyBase64 = roomKeysMap[currentUid]
            ?: throw Exception("No room key found for your user ID.")

        return KeyManager.unwrapKey(wrappedKeyBase64)
    }
}