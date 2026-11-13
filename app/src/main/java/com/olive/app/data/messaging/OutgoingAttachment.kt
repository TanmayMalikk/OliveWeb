// All chat roles: identifies encrypted media that must be published with an outbox message.
package com.olive.app.data.messaging

import com.olive.app.data.safety.SafetyDocument
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import kotlinx.serialization.Serializable

/** References ciphertext already published to the media service. */
@Serializable
data class OutgoingAttachment(
    val mediaId: String,
) {
    init {
        require(validId(mediaId)) { "Attachment media ID is invalid" }
    }

    companion object {
        private fun validId(value: String) =
            value.length <= 160 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}

/** Only immutable ciphertext already published to the media service can enter the chat outbox. */
internal fun isDurableChatAttachment(document: SafetyDocument, ownerUid: String?) =
    ownerUid != null && document.ownerUid == ownerUid && document.objectKey.isNotBlank()

/** Uses the locally opened conversation key without touching Firebase while the device is offline. */
internal suspend fun outgoingRoomKey(
    activeRoomKey: SecretKey?,
    loadRoomKey: suspend () -> SecretKey,
): SecretKey = activeRoomKey ?: loadRoomKey()

internal open class MessageDeliveryRejected(
    val failureCode: String,
    cause: Throwable? = null,
) : IllegalStateException(failureCode, cause)

internal class AttachmentDeliveryRejected(
    failureCode: String,
    cause: Throwable? = null,
) : MessageDeliveryRejected(failureCode, cause)

internal fun attachmentKeyUnavailable(cause: GeneralSecurityException) =
    AttachmentDeliveryRejected("ATTACHMENT_KEY_UNAVAILABLE", cause)

/** Routes one sequenced outbox head without allowing attachments to bypass the text-message queue. */
internal class OutgoingDeliveryDispatcher(
    private val deliverText: suspend (OutgoingMessage) -> Unit,
    private val deliverAttachment: suspend (OutgoingMessage, String) -> Unit,
) {
    suspend fun deliver(message: OutgoingMessage) {
        val attachment = message.attachment
        if (attachment == null) {
            deliverText(message)
            return
        }
        deliverAttachment(message, attachment.mediaId)
    }
}
