// All chat roles: identifies encrypted media that must be published with an outbox message.
package com.olive.app.data

import org.json.JSONObject

/** References ciphertext already published to the media service. */
data class OutgoingAttachment(
    val mediaId: String,
) {
    init {
        require(validId(mediaId)) { "Attachment media ID is invalid" }
    }

    internal fun toJson() = JSONObject().put(MEDIA_ID, mediaId)

    companion object {
        internal fun fromJson(value: JSONObject) = OutgoingAttachment(
            mediaId = value.getString(MEDIA_ID),
        )

        private fun validId(value: String) =
            value.length <= 160 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        private const val MEDIA_ID = "mediaId"
    }
}

internal class AttachmentDeliveryRejected(
    val failureCode: String,
) : IllegalStateException(failureCode)

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
