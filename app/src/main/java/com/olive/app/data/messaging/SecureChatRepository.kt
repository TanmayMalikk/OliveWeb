// All chat roles: stores encrypted chats between allowed participants.
package com.olive.app.data.messaging

import com.olive.app.data.network.cloudStringMap

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.SecretKey
import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64


/** Stores message bodies with a per-conversation AES key wrapped for each participant. */
class SecureChatRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val authorizePeer: suspend (String) -> Unit = ::authorizeMessagingPeer,
) {
    /**
     * Starts observing key changes.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param uid Firebase UID of the target user account.
     * @param onKey Callback invoked when key changes or occurs.
     * @param onDevices Callback invoked when devices changes or occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    internal fun observeKeyChanges(
        conversation: ChatConversation,
        uid: String,
        onKey: (path: String, fingerprint: String) -> Unit,
        onDevices: (path: String, fingerprint: String) -> Unit,
    ): ChatSubscription {
        val members = conversation.participantUsernames.values.distinct().map {
            firestore.collection("messageDirectory").document(it.trim().lowercase())
        }
        val localKey = firestore.collection("conversations").document(conversation.id)
            .collection("deviceKeys").document("$uid--${KeyManager.deviceId()}")
        val keyListeners = (listOf(localKey) + members).map { document ->
            document.addSnapshotListener { snapshot, failure ->
                if (failure != null || snapshot == null) return@addSnapshotListener
                val key = snapshot.getString("wrappedKey") ?: snapshot.getString("publicKey") ?: return@addSnapshotListener
                onKey(document.path, key)
            }
        }
        val deviceListeners = members.map { member ->
            val devices = member.collection("devices")
            devices.addSnapshotListener { snapshot, failure ->
                if (failure != null || snapshot == null) return@addSnapshotListener
                val fingerprint = snapshot.documents.joinToString("|") {
                    "${it.id}:${it.getString("publicKey")}:${it.getString("mgf1Digest")}"
                }
                onDevices(devices.path, fingerprint)
            }
        }
        return ChatSubscription { (keyListeners + deviceListeners).forEach { it.remove() } }
    }

    /**
     * Reads established keys directly after confirming current membership on the server.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param uid Firebase UID of the target user account.
     * @return The resulting [UnwrappedKeyEnvelope], or `null` if unavailable.
     */
    internal suspend fun readyRoomKey(conversation: ChatConversation, uid: String): KeyManager.UnwrappedKeyEnvelope? {
        val snapshot = firestore.collection("conversations").document(conversation.id).get(Source.SERVER).await()
        val participants = (snapshot.get("participantIds") as? List<*>)?.toSet() ?: return null
        if (uid !in participants || participants != conversation.participantIds.toSet()) return null
        val roomKeys = snapshot.roomKeys().orEmpty()
        if (roomKeys.keys != participants || snapshot.participantPublicKeys()?.keys != participants) return null
        val accountEnvelope = roomKeys[uid] ?: return null
        val accountKey = withContext(Dispatchers.Default) {
            runCatching { KeyManager.unwrapFirstWithEnvelope(listOf(accountEnvelope)) }.getOrNull()
        }
        if (accountKey != null) return accountKey
        val deviceEnvelope = snapshot.reference.collection("deviceKeys")
            .document("$uid--${KeyManager.deviceId()}").get(Source.SERVER).await().getString("wrappedKey") ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { KeyManager.unwrapFirstWithEnvelope(listOf(deviceEnvelope)) }.getOrNull()
        }
    }

    /**
     * Opens the copy of an existing room key that was encrypted for this account.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param currentUid Current user ID supplied to [existingRoomKey].
     * @return The resulting [SecretKey], or `null` if unavailable.
     */
    suspend fun existingRoomKey(conversationId: String, currentUid: String): SecretKey? {
        return roomKeyAccess(conversationId, currentUid)?.secretKey
    }

    /**
     * Returns a verified envelope for protected offline retention.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param currentUid Current user ID supplied to [wrappedRoomKey].
     * @return The resulting wrapped room key string, or `null` when unavailable.
     */
    suspend fun wrappedRoomKey(conversationId: String, currentUid: String): String? {
        return roomKeyAccess(conversationId, currentUid, Source.SERVER)?.wrappedKeyBase64
    }

    /**
     * Tries the device envelope first, then the same account's recovery envelope.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param currentUid Current user ID supplied to [roomKeyAccess].
     * @param source Source input or object to read from.
     * @return The resulting [UnwrappedKeyEnvelope], or `null` if unavailable.
     */
    private suspend fun roomKeyAccess(
        conversationId: String,
        currentUid: String,
        source: Source = Source.DEFAULT,
    ): KeyManager.UnwrappedKeyEnvelope? {
        val snapshot = firestore.collection("conversations").document(conversationId).get(source).await()
        if (currentUid !in (snapshot.get("participantIds") as? List<*>).orEmpty()) return null
        val deviceId = KeyManager.deviceId()
        val deviceKey = snapshot.reference.collection("deviceKeys")
            .document("$currentUid--$deviceId").get(source).await().getString("wrappedKey")
        val candidates = listOfNotNull(deviceKey, snapshot.roomKeys().orEmpty()[currentUid])
        return runCatching { KeyManager.unwrapFirstWithEnvelope(candidates) }.getOrNull()
    }

    /**
     * Creates or unwraps the encrypted room key required before this participant can read messages.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param currentUid Current user ID supplied to [ensureRoomKey].
     * @param participantPublicKeys Key-value mapping of participant public keys entries.
     * @return The resulting [SecretKey] for ensure room key.
     */
    suspend fun ensureRoomKey(
        conversation: ChatConversation, currentUid: String,
        participantPublicKeys: Map<String, PublicKey>
    ): SecretKey {
        require(participantPublicKeys.keys.containsAll(conversation.participantIds))
        val encodedPublicKeys = participantPublicKeys.mapValues { (_, key) ->
            Base64.getEncoder().encodeToString(key.encoded)
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

    /**
     * Secure messaging: gives each registered phone or browser its own wrapped copy of the room key.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param roomKey Room key supplied to [shareWithRegisteredDevices].
     */
    suspend fun shareWithRegisteredDevices(
        conversation: ChatConversation,
        roomKey: SecretKey
    ) {
        val conversationRef = firestore.collection("conversations").document(conversation.id)
        var batch = firestore.batch()
        var writes = 0
        conversation.participantIds.forEach { uid ->
            val username = conversation.participantUsernames[uid] ?: return@forEach
            authorizePeer(username)
            val devices = firestore.collection("messageDirectory").document(username.trim().lowercase())
                .collection("devices").get().await()
            devices.documents.forEach { device ->
                if (device.getString("uid") != uid) return@forEach
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
                if (writes == 10) {
                    batch.commit().await()
                    batch = firestore.batch()
                    writes = 0
                }
            }
        }
        if (writes > 0) batch.commit().await()
    }

    /**
     * Encrypts and sends one chat message to every current participant.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param sender Sender supplied to [send].
     * @param text Text string to display, parse, or send.
     * @param roomKey Room key supplied to [send].
     */
    suspend fun send(
        conversationId: String,
        sender: AccountIdentity,
        text: String,
        roomKey: SecretKey
    ) {
        deliver(prepareOutgoingMessage(conversationId, sender, text, roomKey))
    }

    /**
     * Sends a previously encrypted outbox record using its deterministic idempotency key.
     *
     * @param outgoing Outgoing supplied to [deliver].
     */
    suspend fun deliver(outgoing: OutgoingMessage) {
        val conversation = firestore.collection("conversations").document(outgoing.conversationId)
        val message = conversation.collection("messages").document(outgoing.id)
        firestore.runTransaction { transaction ->
            val saved = transaction.get(message)
            if (saved.exists()) {
                if (!existingMessageMatches(
                        senderUid = saved.getString("senderUid"),
                        ciphertext = saved.getString("ciphertext"),
                        clientSentAt = saved.getLong("clientSentAt"),
                        outgoing = outgoing,
                    )
                ) {
                    throw MessageDeliveryRejected("IDEMPOTENCY_CONFLICT")
                }
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

    /**
     * Streams encrypted chat records and decrypts each message with the active room key.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param roomKey Room key supplied to [observeMessages].
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @param onServerCiphertext Callback invoked when server ciphertext changes or occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeMessages(
        conversationId: String, roomKey: SecretKey, onUpdate: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit,
        onServerCiphertext: ((List<EncryptedChatMessage>) -> Unit)? = null,
    ): ChatSubscription {
        val deliveryScope = CoroutineScope(Dispatchers.Main.immediate)
        var delivery: Job? = null
        var lastEncrypted: List<EncryptedChatMessage>? = null
        var lastDecoded = emptyList<ChatMessage>()
        val listener = firestore.collection("conversations").document(conversationId)
            .collection("messages").orderBy("sentAt").limitToLast(250)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                delivery?.cancel()
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
                delivery = deliveryScope.launch {
                    if (lastEncrypted != encrypted) {
                        val decoded = withContext(Dispatchers.Default) {
                            runCatching { decryptMessages(encrypted, roomKey) }
                        }.getOrElse {
                            onError("Encrypted message authentication failed.")
                            return@launch
                        }
                        lastEncrypted = encrypted
                        lastDecoded = decoded
                    }
                    if (snapshot?.metadata?.isFromCache != true || encrypted.isNotEmpty()) onUpdate(lastDecoded)
                    if (snapshot != null && !snapshot.metadata.isFromCache) onServerCiphertext?.invoke(encrypted)
                }
            }
        return ChatSubscription {
            listener.remove()
            deliveryScope.cancel()
        }
    }

    /**
     * Authenticates ciphertext before any message is exposed to the UI.
     *
     * @param messages Collection of messages values to process.
     * @param roomKey Room key supplied to [decryptMessages].
     * @return List of resulting decrypt messages items.
     */
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

    /**
     * Removes from secure messaging.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param message Chat message or remote payload.
     * @param currentUid Current user ID supplied to [unsend].
     */
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

    /**
     * Loads and unwraps the room key used to decrypt this conversation.
     *
     * @return The resulting room keys.
     */
    private fun DocumentSnapshot.roomKeys() =
        get("roomKeys").cloudStringMap()

    /**
     * Loads each participant's public key before sharing a room key.
     *
     * @return The resulting participant public keys.
     */
    private fun DocumentSnapshot.participantPublicKeys() =
        get("participantPublicKeys").cloudStringMap()
}

/**
 * Computes existing message matches.
 *
 * @param senderUid Firebase UID of the sending account, or null when not provided.
 * @param ciphertext Optional ciphertext supplied to [existingMessageMatches].
 * @param clientSentAt Optional client sent at supplied to [existingMessageMatches].
 * @param outgoing Outgoing supplied to [existingMessageMatches].
 * @return The resulting existing message matches.
 */
internal fun existingMessageMatches(
    senderUid: String?,
    ciphertext: String?,
    clientSentAt: Long?,
    outgoing: OutgoingMessage,
) = senderUid == outgoing.ownerUid &&
        ciphertext == outgoing.ciphertext &&
        clientSentAt == outgoing.clientSentAt

// All chat roles: retains only encrypted chat material for offline cold starts.

internal interface ChatCacheVault {
    /**
     * Reads the chat cache vault.
     *
     * @return JSON object representing read.
     */
    fun read(): JSONObject
    /**
     * Updates the chat cache vault.
     *
     * @param change Action or callback executed for change.
     */
    fun update(change: (JSONObject) -> Unit)
}

private class EncryptedChatCacheVault(context: Context) : ChatCacheVault {
    private val vault = EncryptedVault(context, "chat-cache")
    /**
     * Reads the encrypted chat cache vault.
     *
     * @return The resulting read.
     */
    override fun read() = vault.read()
    /**
     * Updates the encrypted chat cache vault.
     *
     * @param change Action or callback executed for change.
     */
    override fun update(change: (JSONObject) -> Unit) = vault.update(change)
}

class ChatCacheException(message: String) : IllegalStateException(message)

/** Account-isolated encrypted index, wrapped room keys, and ciphertext message cache. */
class ChatCacheStore internal constructor(
    private val vault: ChatCacheVault,
    private val maxAccountBytes: Int = MAX_ACCOUNT_BYTES,
) {
    constructor(context: Context) : this(EncryptedChatCacheVault(context.applicationContext))

    /**
     * Computes conversations.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return List of resulting conversations items.
     */
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

    /**
     * A server-confirmed list is authoritative, so absent conversations and their keys are purged.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param authoritative Collection of authoritative values to process.
     * @param now Current epoch timestamp in milliseconds.
     */
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

    /**
     * Saves access.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversation Conversation metadata including participants and ticket status.
     * @param wrappedRoomKey Wrapped room key supplied to [saveAccess].
     * @param deviceId Stable identifier for this device installation.
     * @param validatedAt Validated at supplied to [saveAccess].
     * @param requireIndexed Whether to apply require indexed.
     */
    fun saveAccess(
        ownerUid: String,
        conversation: ChatConversation,
        wrappedRoomKey: String,
        deviceId: String,
        validatedAt: Long,
        requireIndexed: Boolean = false,
    ) {
        require(ownerUid in conversation.participantIds)
        require(wrappedRoomKey.isNotBlank() && deviceId.isNotBlank())
        vault.update { root ->
            val account = root.child(ACCOUNTS).child(ownerUid)
            val entries = conversations(account)
            val entry = entries.optJSONObject(conversation.id) ?: run {
                if (requireIndexed) return@update
                JSONObject().also { entries.put(conversation.id, it) }
            }
            if (!requireIndexed) entry.put(CONVERSATION, conversation.toCacheJson())
            entry.put(WRAPPED_ROOM_KEY, wrappedRoomKey)
            entry.put(DEVICE_ID, deviceId)
            entry.put(VALIDATED_AT, validatedAt)
            entry.put(LAST_ACCESSED_AT, validatedAt)
            if (!entry.has(MESSAGES)) entry.put(MESSAGES, JSONArray())
            trim(account)
        }
    }

    /**
     * Saves messages.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param messages Collection of messages values to process.
     * @param now Current epoch timestamp in milliseconds.
     */
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

    /**
     * Opens the chat cache store.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param deviceId Stable identifier for this device installation.
     * @param now Current epoch timestamp in milliseconds.
     * @return The resulting [CachedChatAccess] instance.
     */
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
                List(array.length()) { index ->
                    encryptedMessageFromCacheJson(
                        array.getJSONObject(
                            index
                        )
                    )
                }
            }.orEmpty().takeLast(MAX_MESSAGES_PER_CONVERSATION)
            CachedChatAccess(conversation, entry.getString(WRAPPED_ROOM_KEY), messages)
        } catch (expected: ChatCacheException) {
            throw expected
        } catch (_: Exception) {
            throw ChatCacheException("Cached conversation is corrupt")
        }
    }

    /**
     * Removes the chat cache store.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    fun remove(ownerUid: String, conversationId: String) {
        vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
                ?.optJSONObject(CONVERSATIONS)?.remove(conversationId)
        }
    }

    /**
     * Purges the chat cache store.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun purge(ownerUid: String) {
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.remove(ownerUid) }
    }

    /**
     * Computes conversations.
     *
     * @param account Account supplied to [conversations].
     * @return JSON object representing conversations.
     */
    private fun conversations(account: JSONObject): JSONObject =
        account.child(CONVERSATIONS)

    /**
     * Evicts the least recently used ciphertext first, then metadata only if the account is still oversized.
     *
     * @param account Account supplied to [trim].
     */
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

    /**
     * Computes size of.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting size of.
     */
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

internal enum class ChatMembershipAction { ADDED, REMOVED, ADMIN_HELP_REQUESTED }

internal data class ChatMembershipEvent(
    val id: String,
    val action: ChatMembershipAction,
    val actorUid: String,
    val actorName: String,
    val participantUid: String,
    val participantName: String,
    val createdAt: Long,
)

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
    val ticketSequence: Long = -1L,
    val closedAt: Long = 0L,
) {
    /**
     * Gives support staff an anonymous, stable label without exposing the user's account name.
     *
     * @return The resulting ticket number string.
     */
    fun ticketNumber(): String =
        if (ticketSequence >= 0L) "#$ticketSequence" else supportTicketNumber(id)

    /**
     * Keeps the header neutral until Firestore returns the real ticket number.
     *
     * @return The resulting ticket display name string.
     */
    fun ticketDisplayName(): String =
        (when {
            supportTag == SUPPORT_TAG_NEEDS_ADMIN -> "Admin Ticket"
            supportOrigin == SUPPORT_TAG_PLAN_CONSULT || supportTag == SUPPORT_TAG_PLAN_CONSULT -> "Consult Ticket"
            else -> "Support Ticket"
        }) + if (ticketSequence >= 0L) " #$ticketSequence" else ""

    /**
     * Gives each personal participant a stable anonymous label inside organization support views. Chooses the private display name shown for this conversation.
     *
     * @param currentUid Current user ID supplied to [displayName].
     * @param viewerRole Viewer role supplied to [displayName].
     * @param anonymousStaffName Anonymous staff name supplied to [displayName].
     * @return The resulting display name.
     */
    fun displayName(
        currentUid: String,
        viewerRole: AccountRole,
        anonymousStaffName: String = "Your Olive Branch",
    ) = if (type == "SUPPORT") {
        ticketDisplayName()
    } else {
        participantIds.mapNotNull { uid ->
            participantUsernames[uid]?.takeIf { uid != currentUid }
        }.joinToString(", ").ifBlank { "Conversation" }
    }

    /**
     * Personal accounts see why the anonymous Olive Staff conversation was opened.
     *
     * @param staffName Staff name supplied to [anonymousStaffDisplayName].
     * @param consultationName Consultation name supplied to [anonymousStaffDisplayName].
     * @param supportName Support name supplied to [anonymousStaffDisplayName].
     * @param escalationName Escalation name supplied to [anonymousStaffDisplayName].
     * @return The resulting anonymous staff display name string.
     */
    fun anonymousStaffDisplayName(
        staffName: String,
        consultationName: String,
        supportName: String,
        escalationName: String,
    ): String {
        return ticketDisplayName()
    }

    /**
     * Personal accounts see a role label instead of an organization member's account name. suppressing unused since linter believes viewerRole is never used, It is.
     *
     * @param uid Firebase UID of the target user account.
     * @return `true` if is chat admin, or `false` otherwise.
     */
    fun isChatAdmin(uid: String): Boolean = type == "SUPPORT" && adminUid.isNotBlank() && uid == adminUid

    /**
     * Computes sender display name.
     *
     * @param senderUid Firebase UID of the sending account.
     * @param senderUsername Sender username supplied to [senderDisplayName].
     * @param viewerRole Viewer role supplied to [senderDisplayName].
     * @param anonymousStaffName Anonymous staff name supplied to [senderDisplayName].
     * @param anonymousAdminName Anonymous admin name supplied to [senderDisplayName].
     * @return The resulting sender display name.
     */
    fun senderDisplayName(
        senderUid: String,
        senderUsername: String,
        viewerRole: AccountRole,
        anonymousStaffName: String,
        anonymousAdminName: String,
    ) = when {
        isChatAdmin(senderUid) -> anonymousAdminName
        type == "SUPPORT" && viewerRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
                staffUid.isNotBlank() && senderUid == staffUid -> anonymousStaffName
        type == "SUPPORT" && viewerRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
                senderUid !in setOf(staffUid, adminUid).filter { it.isNotBlank() } ->
            if (senderUid == ownerUid) "Ticket ${ticketNumber()}" else "Friend"
        else -> senderUsername
    }

}

/**
 * Builds the same short ticket label on every device from the unique support conversation ID.
 *
 * @param conversationId Unique identifier of the conversation or support ticket.
 * @return The resulting support ticket number string.
 */
internal fun supportTicketNumber(conversationId: String): String {
    val number = conversationId.hashCode().toUInt() % 1_000_000u
    return "#${number.toString().padStart(6, '0')}"
}

internal const val SUPPORT_TAG_PLAN_CONSULT = "PLAN_CONSULT"
internal const val SUPPORT_TAG_SUPPORT = "SUPPORT"
internal const val SUPPORT_TAG_NEEDS_ADMIN = "NEEDS_ADMIN"

// Represents one message in an encrypted conversation.
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

// Gives each screen one handle for stopping a live chat listener safely.
fun interface ChatSubscription {
    /**
     * Closes the chat subscription.
     */
    fun close()
}

