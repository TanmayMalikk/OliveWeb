package com.olive.app.data.messaging

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
    fun close()
}
