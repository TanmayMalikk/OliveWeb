// All chat roles: models ciphertext-only messages waiting for confirmed Firebase delivery.
package com.olive.app.data

import com.olive.app.security.EncryptionUtils
import java.util.UUID
import javax.crypto.SecretKey
import org.json.JSONObject

enum class MessageDeliveryStatus {
    QUEUED,
    SENDING,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SENT,
}

/**
 * Durable client-side record for one encrypted message.
 *
 * [ciphertext] is produced before persistence. Plaintext and the room key never enter this model,
 * WorkManager input data, or the encrypted outbox file.
 */
data class OutgoingMessage(
    val id: String,
    val ownerUid: String,
    val conversationId: String,
    val senderUsername: String,
    val ciphertext: String,
    val clientSentAt: Long,
    val status: MessageDeliveryStatus = MessageDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val failureCode: String? = null,
) {
    init {
        require(id.isNotBlank() && ownerUid.isNotBlank() && conversationId.isNotBlank())
        require(senderUsername.isNotBlank() && ciphertext.isNotBlank())
        require(clientSentAt >= 0 && attemptCount >= 0)
    }

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("ownerUid", ownerUid)
        put("conversationId", conversationId)
        put("senderUsername", senderUsername)
        put("ciphertext", ciphertext)
        put("clientSentAt", clientSentAt)
        put("status", status.name)
        put("attemptCount", attemptCount)
        failureCode?.let { put("failureCode", it) }
    }

    companion object {
        fun fromJson(value: JSONObject) = OutgoingMessage(
            id = value.getString("id"),
            ownerUid = value.getString("ownerUid"),
            conversationId = value.getString("conversationId"),
            senderUsername = value.getString("senderUsername"),
            ciphertext = value.getString("ciphertext"),
            clientSentAt = value.getLong("clientSentAt"),
            status = MessageDeliveryStatus.valueOf(value.getString("status")),
            attemptCount = value.optInt("attemptCount", 0),
            failureCode = value.optString("failureCode").takeIf(String::isNotBlank),
        )
    }
}

internal fun sanitizedMessageBody(text: String): String {
    val body = text.trim().filter { it == '\n' || it == '\t' || it >= ' ' }
    require(body.isNotEmpty())
    require(body.length <= 2_000)
    return body
}

internal fun prepareOutgoingMessage(
    conversationId: String,
    sender: AccountIdentity,
    text: String,
    roomKey: SecretKey,
    id: String = UUID.randomUUID().toString(),
    now: Long = System.currentTimeMillis(),
) = OutgoingMessage(
    id = id,
    ownerUid = sender.uid,
    conversationId = conversationId,
    senderUsername = sender.username.trim().lowercase(),
    ciphertext = EncryptionUtils.encryptText(sanitizedMessageBody(text), roomKey),
    clientSentAt = now,
)
