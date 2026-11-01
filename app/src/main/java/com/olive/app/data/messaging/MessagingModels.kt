// All chat roles: defines friends, conversations, and message records.
package com.olive.app.data

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
    // Messaging: returns the display name.
    fun displayName(
        currentUid: String,
        viewerRole: AccountRole,
        anonymousStaffName: String = "Your Olive Branch",
    ) = if (type == "SUPPORT") {
        if (viewerRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            participantUsernames[ownerUid].orEmpty().ifBlank { "User" }
        } else anonymousStaffName
    } else participantUsernames.entries.firstOrNull { it.key != currentUid }?.value ?: "Conversation"

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
)

// Messaging: Represents a live chat listener. It gives each screen one operation for stopping that listener safely.
fun interface ChatSubscription {
    // Messaging: Stops live chat updates. It checks the chat and uses its secure data before reading or saving a message.
    fun close()
}

// Firestore handles friend state and live DMs while message bodies stay out of its on-device disk cache.
