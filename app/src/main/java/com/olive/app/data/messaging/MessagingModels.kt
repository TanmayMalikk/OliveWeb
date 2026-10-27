// Contains the messaging models code for this part of Olive.
package com.olive.app.data

// Groups the data and actions for directory user.
data class DirectoryUser(val uid: String, val username: String, val publicKey: String = "")

// Groups the data and actions for friend request.
data class FriendRequest(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val recipientUid: String,
    val recipientUsername: String,
    val createdAt: Long,
)

// Groups the data and actions for friend.
data class Friend(val friendshipId: String, val uid: String, val username: String)

// A private note belongs only to the signed-in account on this device.
data class PrivateContactNote(val uid: String, val username: String, val body: String)

// Groups the data and actions for social snapshot.
data class SocialSnapshot(
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

// Groups the data and actions for friend relationship.
enum class FriendRelationship { AddFriend, RequestSent, IncomingRequest, Friends }

// Groups the data and actions for chat conversation.
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
) {
    fun displayName(currentUid: String) = if (type == "SUPPORT") {
        participantUsernames.filterKeys { it != currentUid }.values.joinToString(" • ")
            .ifBlank { "Support chat" }
    } else participantUsernames.entries.firstOrNull { it.key != currentUid }?.value ?: "Conversation"
}

// Groups the data and actions for chat message.
data class ChatMessage(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val body: String,
    val sentAt: Long,
    val unsent: Boolean = false,
)

fun interface ChatSubscription {
    fun close()
}

// Firestore handles friend state and live DMs while message bodies stay out of its on-device disk cache.
