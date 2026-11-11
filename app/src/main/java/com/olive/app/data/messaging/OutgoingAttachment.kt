// All chat roles: identifies encrypted media that must be published with an outbox message.
package com.olive.app.data

import org.json.JSONObject

/**
 * References either ciphertext already published to the media service or a durable upload that must
 * finish first. Plaintext bytes, filenames, and encryption keys never enter the message outbox.
 */
data class OutgoingAttachment(
    val mediaId: String? = null,
    val uploadJobId: String? = null,
) {
    init {
        val sources = listOf(mediaId, uploadJobId).count { !it.isNullOrBlank() }
        require(sources == 1) { "An attachment must identify exactly one encrypted media source" }
        mediaId?.let { require(validId(it)) }
        uploadJobId?.let { require(validId(it)) }
    }

    internal fun toJson() = JSONObject().apply {
        mediaId?.let { put(MEDIA_ID, it) }
        uploadJobId?.let { put(UPLOAD_JOB_ID, it) }
    }

    companion object {
        internal fun fromJson(value: JSONObject) = OutgoingAttachment(
            mediaId = value.optString(MEDIA_ID).takeIf(String::isNotBlank),
            uploadJobId = value.optString(UPLOAD_JOB_ID).takeIf(String::isNotBlank),
        )

        private fun validId(value: String) =
            value.length <= 160 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        private const val MEDIA_ID = "mediaId"
        private const val UPLOAD_JOB_ID = "uploadJobId"
    }
}

/** A pending upload keeps its sequence position and asks WorkManager to retry later. */
internal class AttachmentDependencyPending : IllegalStateException("Encrypted media upload is still pending")

/** Routes one sequenced outbox head without allowing attachments to bypass the text-message queue. */
internal class OutgoingDeliveryDispatcher(
    private val deliverText: suspend (OutgoingMessage) -> Unit,
    private val resolveUpload: suspend (String) -> String?,
    private val deliverAttachment: suspend (OutgoingMessage, String) -> Unit,
) {
    suspend fun deliver(message: OutgoingMessage) {
        val attachment = message.attachment
        if (attachment == null) {
            deliverText(message)
            return
        }
        val mediaId = attachment.mediaId
            ?: resolveUpload(checkNotNull(attachment.uploadJobId))
            ?: throw AttachmentDependencyPending()
        deliverAttachment(message, mediaId)
    }
}
