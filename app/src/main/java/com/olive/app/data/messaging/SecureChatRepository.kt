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
import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray
import org.json.JSONObject


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
        // An empty chat can safely replace stale setup keys because there is no ciphertext to lose.
        val encryptedMessagesExist = !ref.collection("messages").limit(1).get().await().isEmpty
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
                val existingKey = locallyAvailableKey ?: savedWrapped[currentUid]?.let { wrapped ->
                    runCatching { KeyManager.unwrapKey(wrapped) }.getOrNull()
                }
                if (existingKey == null) {
                    val hasMessageMetadata = snapshot.getString("lastMessageId").orEmpty().isNotBlank()
                    check(!encryptedMessagesExist && !hasMessageMetadata) {
                        "An existing authorized device must share this conversation key."
                    }
                    val regenerated = EncryptionUtils.generateSymmetricKey()
                    val wrapped = participantPublicKeys.mapValues { (_, key) ->
                        KeyManager.wrapKey(regenerated, key)
                    }
                    transaction.update(
                        ref, mapOf(
                            "roomKeys" to wrapped,
                            "participantPublicKeys" to encodedPublicKeys,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                    )
                    regenerated
                } else if (
                    savedKeys == encodedPublicKeys &&
                    savedWrapped.keys.containsAll(conversation.participantIds)
                ) {
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

// All chat roles: retains only encrypted chat material for offline cold starts.

internal interface ChatCacheVault {
    fun read(): JSONObject
    fun update(change: (JSONObject) -> Unit)
}

private class EncryptedChatCacheVault(context: Context) : ChatCacheVault {
    private val vault = EncryptedVault(context, "chat-cache")
    override fun read() = vault.read()
    override fun update(change: (JSONObject) -> Unit) = vault.update(change)
}

class ChatCacheException(message: String) : IllegalStateException(message)

/** Account-isolated encrypted index, wrapped room keys, and ciphertext message cache. */
class ChatCacheStore internal constructor(
    private val vault: ChatCacheVault,
    private val maxAccountBytes: Int = MAX_ACCOUNT_BYTES,
) {
    constructor(context: Context) : this(EncryptedChatCacheVault(context.applicationContext))

    fun conversations(ownerUid: String): List<ChatConversation> {
        val entries = vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
            ?.optJSONObject(CONVERSATIONS) ?: return emptyList()
        return entries.keys().asSequence().mapNotNull { id ->
            runCatching {
                conversationFromCacheJson(entries.getJSONObject(id).getJSONObject(CONVERSATION))
            }.getOrNull()
        }.filter { ownerUid in it.participantIds }
            .sortedByDescending(ChatConversation::updatedAt)
            .toList()
    }

    /** A server-confirmed list is authoritative, so absent conversations and their keys are purged. */
    fun replaceIndex(ownerUid: String, authoritative: List<ChatConversation>, now: Long) {
        require(authoritative.all { ownerUid in it.participantIds })
        vault.update { root ->
            val account = root.child(ACCOUNTS).child(ownerUid)
            val entries = conversations(account)
            val retained = authoritative.associateBy(ChatConversation::id)
            entries.keys().asSequence().toList().filterNot(retained::containsKey).forEach(entries::remove)
            authoritative.forEach { conversation ->
                val entry = entries.optJSONObject(conversation.id) ?: JSONObject().also {
                    entries.put(conversation.id, it)
                }
                entry.put(CONVERSATION, conversation.toCacheJson())
                if (!entry.has(LAST_ACCESSED_AT)) entry.put(LAST_ACCESSED_AT, now)
            }
            trim(account)
        }
    }

    fun saveAccess(
        ownerUid: String,
        conversation: ChatConversation,
        wrappedRoomKey: String,
        deviceId: String,
        validatedAt: Long,
    ) {
        require(ownerUid in conversation.participantIds)
        require(wrappedRoomKey.isNotBlank() && deviceId.isNotBlank())
        vault.update { root ->
            val account = root.child(ACCOUNTS).child(ownerUid)
            val entry = conversations(account).optJSONObject(conversation.id) ?: JSONObject().also {
                conversations(account).put(conversation.id, it)
            }
            entry.put(CONVERSATION, conversation.toCacheJson())
            entry.put(WRAPPED_ROOM_KEY, wrappedRoomKey)
            entry.put(DEVICE_ID, deviceId)
            entry.put(VALIDATED_AT, validatedAt)
            entry.put(LAST_ACCESSED_AT, validatedAt)
            if (!entry.has(MESSAGES)) entry.put(MESSAGES, JSONArray())
            trim(account)
        }
    }

    fun saveMessages(ownerUid: String, conversationId: String, messages: List<EncryptedChatMessage>, now: Long) {
        vault.update { root ->
            val account = root.child(ACCOUNTS).child(ownerUid)
            val entry = conversations(account).optJSONObject(conversationId)
                ?: throw ChatCacheException("Conversation access is not cached")
            val retained = messages.takeLast(MAX_MESSAGES_PER_CONVERSATION)
            entry.put(MESSAGES, JSONArray().apply { retained.forEach { put(it.toCacheJson()) } })
            entry.put(LAST_ACCESSED_AT, now)
            trim(account)
        }
    }

    fun open(ownerUid: String, conversationId: String, deviceId: String, now: Long): CachedChatAccess {
        val entry = vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
            ?.optJSONObject(CONVERSATIONS)?.optJSONObject(conversationId)
            ?: throw ChatCacheException("Conversation is not available offline")
        return try {
            val conversation = conversationFromCacheJson(entry.getJSONObject(CONVERSATION))
            if (ownerUid !in conversation.participantIds) throw ChatCacheException("Cached access was revoked")
            if (entry.getString(DEVICE_ID) != deviceId) throw ChatCacheException("Cached key belongs to another device")
            val validatedAt = entry.getLong(VALIDATED_AT)
            if (now < validatedAt || now - validatedAt > REVALIDATION_LEASE_MILLIS) {
                throw ChatCacheException("Offline access requires online revalidation")
            }
            val messages = entry.optJSONArray(MESSAGES)?.let { array ->
                List(array.length()) { index -> encryptedMessageFromCacheJson(array.getJSONObject(index)) }
            }.orEmpty().takeLast(MAX_MESSAGES_PER_CONVERSATION)
            CachedChatAccess(conversation, entry.getString(WRAPPED_ROOM_KEY), messages)
        } catch (expected: ChatCacheException) {
            throw expected
        } catch (_: Exception) {
            throw ChatCacheException("Cached conversation is corrupt")
        }
    }

    fun remove(ownerUid: String, conversationId: String) {
        vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
                ?.optJSONObject(CONVERSATIONS)?.remove(conversationId)
        }
    }

    fun purge(ownerUid: String) {
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.remove(ownerUid) }
    }

    private fun conversations(account: JSONObject): JSONObject =
        account.child(CONVERSATIONS)

    /** Evicts the least recently used ciphertext first, then metadata only if the account is still oversized. */
    private fun trim(account: JSONObject) {
        val entries = conversations(account)
        val oldest = entries.keys().asSequence().mapNotNull { id ->
            entries.optJSONObject(id)?.let { id to it.optLong(LAST_ACCESSED_AT) }
        }.sortedBy(Pair<String, Long>::second).map(Pair<String, Long>::first).toList()
        oldest.forEach { id ->
            if (sizeOf(account) <= maxAccountBytes) return
            entries.optJSONObject(id)?.put(MESSAGES, JSONArray())
        }
        oldest.forEach { id ->
            if (sizeOf(account) <= maxAccountBytes) return
            entries.remove(id)
        }
    }

    private fun sizeOf(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8).size

    companion object {
        const val MAX_MESSAGES_PER_CONVERSATION = 250
        const val REVALIDATION_LEASE_MILLIS = 24 * 60 * 60 * 1_000L
        const val MAX_ACCOUNT_BYTES = 25 * 1024 * 1024
        private const val ACCOUNTS = "accounts"
        private const val CONVERSATIONS = "conversations"
        private const val CONVERSATION = "conversation"
        private const val WRAPPED_ROOM_KEY = "wrappedRoomKey"
        private const val DEVICE_ID = "deviceId"
        private const val VALIDATED_AT = "validatedAt"
        private const val LAST_ACCESSED_AT = "lastAccessedAt"
        private const val MESSAGES = "messages"
    }
}

// All chat roles: defines friends, conversations, and message records.

data class DirectoryUser(val uid: String, val username: String, val publicKey: String = "")

data class FriendRequest(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val recipientUid: String,
    val recipientUsername: String,
    val createdAt: Long,
)

data class Friend(val friendshipId: String, val uid: String, val username: String)

// A private note belongs only to the signed-in account on this device.
data class PrivateContactNote(val uid: String, val username: String, val body: String)

data class SocialSnapshot(
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

enum class FriendRelationship { AddFriend, RequestSent, IncomingRequest, Friends }

data class ChatConversation(
    val id: String,
    val friendshipId: String,
    val participantIds: List<String>,
    val participantUsernames: Map<String, String>,
    val lastMessage: String = "",
    val updatedAt: Long = 0L,
    val lastMessageId: String = "",
    val lastSenderUid: String = "",
    val type: String = "DIRECT",
    val ownerUid: String = "",
    val staffUid: String = "",
    val adminUid: String = "",
    val supportOrigin: String = "",
    val supportTag: String = "",
    val closedAt: Long = 0L,
) {
    // Chooses the private display name shown for this conversation.
    fun displayName(
        currentUid: String,
        viewerRole: AccountRole,
        anonymousStaffName: String = "Your Olive Branch",
    ) = if (type == "SUPPORT") {
        if (viewerRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            participantUsernames[ownerUid].orEmpty().ifBlank { "User" }
        } else anonymousStaffName
    } else {
        participantIds.mapNotNull { uid ->
            participantUsernames[uid]?.takeIf { uid != currentUid }
        }.joinToString(", ").ifBlank { "Conversation" }
    }

    // Personal accounts see why the anonymous Olive Staff conversation was opened.
    fun anonymousStaffDisplayName(
        staffName: String,
        consultationName: String,
        supportName: String,
        escalationName: String,
    ): String {
        val ticketName = when {
            supportTag == SUPPORT_TAG_NEEDS_ADMIN -> escalationName
            supportOrigin == SUPPORT_TAG_PLAN_CONSULT || supportTag == SUPPORT_TAG_PLAN_CONSULT -> consultationName
            else -> supportName
        }
        return "$staffName • $ticketName"
    }

    // Personal accounts see a role label instead of an organization member's account name.
    fun senderDisplayName(
        senderUid: String,
        senderUsername: String,
        viewerRole: AccountRole,
        anonymousStaffName: String,
    ) = if (
        type == "SUPPORT" &&
        viewerRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
        senderUid in setOf(staffUid, adminUid).filter { it.isNotBlank() }
    ) anonymousStaffName else senderUsername

}

internal const val SUPPORT_TAG_PLAN_CONSULT = "PLAN_CONSULT"
internal const val SUPPORT_TAG_SUPPORT = "SUPPORT"
internal const val SUPPORT_TAG_NEEDS_ADMIN = "NEEDS_ADMIN"

data class ChatMessage(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val body: String,
    val sentAt: Long,
    val unsent: Boolean = false,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
    val deliveryFailureCode: String? = null,
)

// Messaging: Represents a live chat listener. It gives each screen one operation for stopping that listener safely.
fun interface ChatSubscription {
    // Removes the Firebase listener that was delivering live chat updates.
    fun close()
}

// Firestore handles friend state and live DMs while message bodies stay out of its on-device disk cache.
