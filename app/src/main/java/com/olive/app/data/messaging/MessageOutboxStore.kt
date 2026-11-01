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
        return accounts.keys().asSequence().mapNotNull { uid ->
            accounts.optJSONObject(uid)?.optJSONObject(MESSAGES)?.optJSONObject(id)
        }.mapNotNull(::safeMessage).firstOrNull()
    }

    fun all(ownerUid: String): List<OutgoingMessage> {
        val messages = vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(ownerUid)
            ?.optJSONObject(MESSAGES) ?: return emptyList()
        return messages.keys().asSequence().mapNotNull { id ->
            messages.optJSONObject(id)?.let(::safeMessage)
        }.sortedWith(compareBy(OutgoingMessage::clientSentAt, OutgoingMessage::id)).toList()
    }

    fun write(message: OutgoingMessage) {
        vault.update { root -> messages(root, message.ownerUid).put(message.id, message.toJson()) }
        changes.tryEmit(message.ownerUid)
    }

    fun update(id: String, change: (OutgoingMessage) -> OutgoingMessage): OutgoingMessage {
        var result: OutgoingMessage? = null
        vault.update { root ->
            val accounts = root.optJSONObject(ACCOUNTS) ?: error("Message outbox is empty")
            val owner = accounts.keys().asSequence().firstOrNull { uid ->
                accounts.optJSONObject(uid)?.optJSONObject(MESSAGES)?.has(id) == true
            } ?: error("Outgoing message was not found")
            val collection = accounts.getJSONObject(owner).getJSONObject(MESSAGES)
            val current = OutgoingMessage.fromJson(collection.getJSONObject(id))
            val updated = change(current)
            require(updated.id == current.id && updated.ownerUid == current.ownerUid)
            result = updated
            collection.put(id, updated.toJson())
        }
        changes.tryEmit(checkNotNull(result).ownerUid)
        return checkNotNull(result)
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

    private fun messages(root: JSONObject, ownerUid: String): JSONObject {
        val accounts = root.optJSONObject(ACCOUNTS) ?: JSONObject().also { root.put(ACCOUNTS, it) }
        val account = accounts.optJSONObject(ownerUid) ?: JSONObject().also { accounts.put(ownerUid, it) }
        return account.optJSONObject(MESSAGES) ?: JSONObject().also { account.put(MESSAGES, it) }
    }

    private fun safeMessage(value: JSONObject) = runCatching { OutgoingMessage.fromJson(value) }.getOrNull()

    private companion object {
        const val ACCOUNTS = "accounts"
        const val MESSAGES = "messages"
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    }
}
