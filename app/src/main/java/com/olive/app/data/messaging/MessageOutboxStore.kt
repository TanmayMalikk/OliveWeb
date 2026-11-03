// All chat roles: persists queued ciphertext across process death and connectivity changes.
package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject

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
            val account = account(root, message.ownerUid)
            migrateAccount(account)
            val collection = messages(account)
            collection.optJSONObject(message.id)?.let { existingJson ->
                val existing = OutgoingMessage.fromJson(existingJson)
                require(
                    existing.ownerUid == message.ownerUid &&
                            existing.conversationId == message.conversationId &&
                            existing.ciphertext == message.ciphertext
                ) { "A queued message id cannot identify different ciphertext" }
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
        return checkNotNull(result)
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

    private fun account(root: JSONObject, ownerUid: String): JSONObject {
        val accounts = root.optJSONObject(ACCOUNTS) ?: JSONObject().also { root.put(ACCOUNTS, it) }
        return accounts.optJSONObject(ownerUid) ?: JSONObject().also { accounts.put(ownerUid, it) }
    }

    private fun messages(account: JSONObject): JSONObject =
        account.optJSONObject(MESSAGES) ?: JSONObject().also { account.put(MESSAGES, it) }

    private fun counters(account: JSONObject): JSONObject =
        account.optJSONObject(SEQUENCES) ?: JSONObject().also { account.put(SEQUENCES, it) }

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
