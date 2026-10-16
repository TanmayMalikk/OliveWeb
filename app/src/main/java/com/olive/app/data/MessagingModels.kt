package com.olive.app.data

data class DirectoryUser(val uid: String, val username: String)

data class FriendRequest(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val recipientUid: String,
    val recipientUsername: String,
    val createdAt: Long,
)

data class Friend(val friendshipId: String, val uid: String, val username: String)

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
    val participantUsernames: Map<String,String>,
    val lastMessage: String = "",
    val updatedAt: Long = 0L,
    val lastMessageId: String = "",
    val lastSenderUid: String = "",
) {
    fun peerUsername(currentUid: String) = participantUsernames.entries
        .firstOrNull { it.key != currentUid }?.value ?: "Conversation"
}

data class ChatMessage(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val body: String,
    val sentAt: Long,
    val unsent: Boolean = false,
)

fun interface ChatSubscription { fun close() }

// Firestore handles friend state and live DMs while message bodies stay out of its on-device disk cache.
