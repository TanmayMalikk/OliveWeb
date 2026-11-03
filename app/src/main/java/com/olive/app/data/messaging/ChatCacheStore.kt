// All chat roles: retains only encrypted chat material for offline cold starts.
package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import org.json.JSONArray
import org.json.JSONObject

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
            val account = account(root, ownerUid)
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
            val account = account(root, ownerUid)
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
            val account = account(root, ownerUid)
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

    private fun account(root: JSONObject, ownerUid: String): JSONObject {
        val accounts = root.optJSONObject(ACCOUNTS) ?: JSONObject().also { root.put(ACCOUNTS, it) }
        return accounts.optJSONObject(ownerUid) ?: JSONObject().also { accounts.put(ownerUid, it) }
    }

    private fun conversations(account: JSONObject): JSONObject =
        account.optJSONObject(CONVERSATIONS) ?: JSONObject().also { account.put(CONVERSATIONS, it) }

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
