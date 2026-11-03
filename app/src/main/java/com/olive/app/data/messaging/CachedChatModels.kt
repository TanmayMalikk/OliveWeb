// All chat roles: defines ciphertext-only records retained for offline chat access.
package com.olive.app.data

import org.json.JSONArray
import org.json.JSONObject

data class EncryptedChatMessage(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val ciphertext: String,
    val sentAt: Long,
    val deleted: Boolean = false,
)

data class CachedChatAccess(
    val conversation: ChatConversation,
    val wrappedRoomKey: String,
    val messages: List<EncryptedChatMessage>,
)

internal fun ChatConversation.toCacheJson() = JSONObject().apply {
    put("id", id)
    put("friendshipId", friendshipId)
    put("participantIds", JSONArray(participantIds))
    put("participantUsernames", JSONObject(participantUsernames))
    put("lastMessage", lastMessage)
    put("updatedAt", updatedAt)
    put("lastMessageId", lastMessageId)
    put("lastSenderUid", lastSenderUid)
    put("type", type)
    put("ownerUid", ownerUid)
    put("staffUid", staffUid)
    put("adminUid", adminUid)
    put("supportOrigin", supportOrigin)
    put("supportTag", supportTag)
    put("closedAt", closedAt)
}

internal fun conversationFromCacheJson(value: JSONObject): ChatConversation {
    val participantIds = value.getJSONArray("participantIds").let { array ->
        List(array.length()) { index -> array.getString(index) }
    }
    val participantUsernames = value.getJSONObject("participantUsernames").let { names ->
        names.keys().asSequence().associateWith(names::getString)
    }
    return ChatConversation(
        id = value.getString("id"),
        friendshipId = value.getString("friendshipId"),
        participantIds = participantIds,
        participantUsernames = participantUsernames,
        lastMessage = value.optString("lastMessage"),
        updatedAt = value.optLong("updatedAt"),
        lastMessageId = value.optString("lastMessageId"),
        lastSenderUid = value.optString("lastSenderUid"),
        type = value.optString("type", "DIRECT"),
        ownerUid = value.optString("ownerUid"),
        staffUid = value.optString("staffUid"),
        adminUid = value.optString("adminUid"),
        supportOrigin = value.optString("supportOrigin"),
        supportTag = value.optString("supportTag"),
        closedAt = value.optLong("closedAt"),
    )
}

internal fun EncryptedChatMessage.toCacheJson() = JSONObject().apply {
    put("id", id)
    put("senderUid", senderUid)
    put("senderUsername", senderUsername)
    put("ciphertext", ciphertext)
    put("sentAt", sentAt)
    put("deleted", deleted)
}

internal fun encryptedMessageFromCacheJson(value: JSONObject) = EncryptedChatMessage(
    id = value.getString("id"),
    senderUid = value.getString("senderUid"),
    senderUsername = value.optString("senderUsername"),
    ciphertext = value.optString("ciphertext"),
    sentAt = value.getLong("sentAt"),
    deleted = value.optBoolean("deleted"),
)
