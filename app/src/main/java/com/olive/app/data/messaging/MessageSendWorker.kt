// All chat roles: delivers durable ciphertext messages when Firebase becomes reachable.
package com.olive.app.data.messaging

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentRepository
import com.olive.app.data.safety.callJsonObject
import com.olive.app.data.safety.isChatOnlyMedia
import com.olive.app.security.EncryptionUtils
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/** WorkManager boundary for idempotent encrypted-message delivery. */
class MessageSendWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val store = MessageOutboxStore(context)
    private val repository = SecureChatRepository()
    private val conversations = MessagingRepository()
    private val documents = SafetyDocumentRepository(media = MediaRepository())

    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result {
        val ownerUid = inputData.getString(OWNER_UID) ?: return Result.failure()
        val conversationId = inputData.getString(CONVERSATION_ID) ?: return Result.failure()
        val delivery = OutgoingDeliveryDispatcher(
            deliverText = repository::deliver,
            deliverAttachment = ::deliverAttachment,
        )
        val outcome = MessageQueueDrainer(
            store = store,
            activeOwnerUid = { FirebaseAuth.getInstance().currentUser?.uid },
            deliver = delivery::deliver,
            classifyFailure = ::messageDeliveryFailure,
            reportFailure = ::reportMessageDeliveryFailure,
        ).drain(ownerUid, conversationId)
        return when (outcome) {
            MessageDrainOutcome.COMPLETE -> Result.success()
            MessageDrainOutcome.RETRY -> Result.retry()
            MessageDrainOutcome.BLOCKED -> Result.failure()
        }
    }

    /**
     * Reports message delivery failure.
     *
     * @param message Chat message or remote payload.
     * @param failure Exception or failure reason encountered during execution.
     * @param cause Cause supplied to [reportMessageDeliveryFailure].
     * @param permanent Whether to apply permanent.
     */
    private fun reportMessageDeliveryFailure(
        message: OutgoingMessage,
        failure: MessageDeliveryFailure,
        cause: Throwable,
        permanent: Boolean,
    ) {
        Log.w(
            DELIVERY_LOG_TAG,
            messageDeliveryLogLine(message.id, failure, cause, permanent),
        )
    }

    /**
     * Computes deliver attachment.
     *
     * @param message Chat message or remote payload.
     * @param mediaId Unique identifier of the encrypted media object.
     */
    private suspend fun deliverAttachment(message: OutgoingMessage, mediaId: String) {
        try {
            val conversation = conversations.getConversation(message.conversationId)
                ?: throw AttachmentDeliveryRejected("CONVERSATION_NOT_FOUND")
            if (message.ownerUid !in conversation.participantIds) {
                throw AttachmentDeliveryRejected("PERMISSION_DENIED")
            }
            val usernames = if (conversation.type == "SUPPORT") {
                conversation.participantUsernames +
                    runCatching { conversations.prepareTicketParticipantNames(conversation.id) }.getOrDefault(emptyMap())
            } else conversation.participantUsernames
            val recipients = conversation.participantIds.filter { it != message.ownerUid }.associateWith { uid ->
                usernames[uid]?.takeIf(String::isNotBlank)
                    ?: throw AttachmentDeliveryRejected("RECIPIENT_UNAVAILABLE")
            }
            if (recipients.isEmpty()) throw AttachmentDeliveryRejected("RECIPIENT_UNAVAILABLE")
            val document = documents.getFor(message.ownerUid, mediaId)
            documents.shareInChat(document, message, recipients)
        } catch (failure: GeneralSecurityException) {
            throw attachmentKeyUnavailable(failure)
        }
    }

    companion object {
        private const val OWNER_UID = "ownerUid"
        private const val CONVERSATION_ID = "conversationId"
        private const val DELIVERY_LOG_TAG = "OliveMessageDelivery"

        /**
         * Computes work name.
         *
         * @param ownerUid Firebase UID of the resource owner.
         * @param conversationId Unique identifier of the conversation or support ticket.
         * @return The resulting work name string.
         */
        fun workName(ownerUid: String, conversationId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$ownerUid\u0000$conversationId".toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "olive_message_drain_$digest"
        }
        /**
         * Computes owner tag.
         *
         * @param ownerUid Firebase UID of the resource owner.
         * @return The resulting owner tag.
         */
        private fun ownerTag(ownerUid: String) = "olive_messages_owner_$ownerUid"

        /**
         * Enqueues the message send worker.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param message Chat message or remote payload.
         */
        fun enqueue(context: Context, message: OutgoingMessage) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(message.ownerUid, message.conversationId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(message),
            )
        }

        /**
         * Replaces only this conversation's backed-off drain after an explicit user retry.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param message Chat message or remote payload.
         */
        fun retryNow(context: Context, message: OutgoingMessage) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(message.ownerUid, message.conversationId),
                ExistingWorkPolicy.REPLACE,
                request(message),
            )
        }

        /**
         * Requests the message send worker.
         *
         * @param message Chat message or remote payload.
         * @return The resulting request.
         */
        private fun request(message: OutgoingMessage) =
            OneTimeWorkRequestBuilder<MessageSendWorker>()
                .setInputData(
                    workDataOf(
                        OWNER_UID to message.ownerUid,
                        CONVERSATION_ID to message.conversationId,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS,
                )
                .addTag(ownerTag(message.ownerUid))
                .build()

        /**
         * Enqueues pending.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param ownerUid Firebase UID of the resource owner.
         */
        fun enqueuePending(context: Context, ownerUid: String) {
            val store = MessageOutboxStore(context)
            store.drainableConversationIds(ownerUid).forEach { conversationId ->
                store.head(ownerUid, conversationId)?.let { enqueue(context, it) }
            }
        }

        /**
         * Cancels owner.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param ownerUid Firebase UID of the resource owner.
         */
        fun cancelOwner(context: Context, ownerUid: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ownerTag(ownerUid))
        }
    }
}

/**
 * Computes message delivery failure.
 *
 * @param failure Exception or failure reason encountered during execution.
 * @return The resulting [MessageDeliveryFailure] for message delivery failure.
 */
internal fun messageDeliveryFailure(failure: Throwable): MessageDeliveryFailure = when (failure) {
    is MessageDeliveryRejected -> MessageDeliveryFailure(failure.failureCode, permanent = true)
    is MediaAccessException -> MessageDeliveryFailure(
        code = "ATTACHMENT_${failure.reason.name}",
        permanent = failure.reason !in setOf(
            MediaAccessError.NETWORK_REQUIRED,
            MediaAccessError.LEASE_EXPIRED,
            MediaAccessError.DOWNLOAD_FAILED,
        ),
    )
    is FirebaseFirestoreException ->
        MessageDeliveryFailure(failure.code.name, isPermanentMessageFailure(failure.code))
    is FirebaseFunctionsException ->
        MessageDeliveryFailure(failure.code.name, isPermanentMessageFailure(failure.code.name))
    else -> MessageDeliveryFailure("TEMPORARY_FAILURE", permanent = false)
}

/**
 * Computes message delivery log line.
 *
 * @param messageId Unique identifier of the chat message.
 * @param failure Exception or failure reason encountered during execution.
 * @param cause Cause supplied to [messageDeliveryLogLine].
 * @param permanent Whether to apply permanent.
 * @return The resulting message delivery log line.
 */
internal fun messageDeliveryLogLine(
    messageId: String,
    failure: MessageDeliveryFailure,
    cause: Throwable,
    permanent: Boolean,
) = "messageId=$messageId code=${failure.code} permanent=$permanent " +
        "exception=${cause::class.java.simpleName}"

/**
 * Checks whether permanent message failure.
 *
 * @param code Verification, invitation, or error code.
 * @return `true` if is permanent message failure, or `false` otherwise.
 */
internal fun isPermanentMessageFailure(code: FirebaseFirestoreException.Code) =
    isPermanentMessageFailure(code.name)

/**
 * Checks whether permanent message failure.
 *
 * @param code Verification, invitation, or error code.
 * @return `true` if is permanent message failure, or `false` otherwise.
 */
internal fun isPermanentMessageFailure(code: String) = code in setOf(
    "INVALID_ARGUMENT",
    "PERMISSION_DENIED",
    "NOT_FOUND",
    "ALREADY_EXISTS",
    "FAILED_PRECONDITION",
    "OUT_OF_RANGE",
    "UNIMPLEMENTED",
)

// All chat roles: models ciphertext-only messages waiting for confirmed Firebase delivery.

enum class MessageDeliveryStatus {
    QUEUED,
    SENDING,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SENT,
}

/**
 * Checks whether the caller can manually retry message.
 *
 * @param status Status supplied to [canManuallyRetryMessage].
 * @return `true` if can manually retry message, or `false` otherwise.
 */
internal fun canManuallyRetryMessage(status: MessageDeliveryStatus) =
    status in setOf(MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.FAILED_RETRYABLE)

/**
 * Checks whether the caller can discard failed message.
 *
 * @param status Status supplied to [canDiscardFailedMessage].
 * @return `true` if can discard failed message, or `false` otherwise.
 */
internal fun canDiscardFailedMessage(status: MessageDeliveryStatus) =
    status == MessageDeliveryStatus.FAILED_PERMANENT

/**
 * Durable client-side record for one encrypted message.
 *
 * [ciphertext] is produced before persistence. Plaintext and the room key never enter this model,
 * WorkManager input data, or the encrypted outbox file.
 */
@Serializable
data class OutgoingMessage(
    val id: String,
    val ownerUid: String,
    val conversationId: String,
    val senderUsername: String,
    val ciphertext: String,
    val clientSentAt: Long,
    val clientSequence: Long = 0,
    @Required val status: MessageDeliveryStatus = MessageDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val failureCode: String? = null,
    val attachment: OutgoingAttachment? = null,
) {
    init {
        require(id.isNotBlank() && ownerUid.isNotBlank() && conversationId.isNotBlank())
        require(senderUsername.isNotBlank() && ciphertext.isNotBlank())
        require(clientSentAt >= 0 && clientSequence >= 0 && attemptCount >= 0)
    }

    /**
     * Serializes this outgoing message into a JSON object.
     *
     * @return JSON representation of this object.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Deserializes a outgoing message from the provided JSON representation.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): OutgoingMessage {
            val message = value.decodeJsonObject<OutgoingMessage>()
            return message.copy(failureCode = message.failureCode?.takeIf(String::isNotBlank))
        }
    }
}

/**
 * Computes sanitized message body.
 *
 * @param text Text string to display, parse, or send.
 * @return The resulting sanitized message body string.
 */
internal fun sanitizedMessageBody(text: String): String {
    val body = text.trim().filter { it == '\n' || it == '\t' || it >= ' ' }
    require(body.isNotEmpty())
    require(body.length <= 2_000)
    return body
}

/**
 * Prepares outgoing message.
 *
 * @param conversationId Unique identifier of the conversation or support ticket.
 * @param sender Sender supplied to [prepareOutgoingMessage].
 * @param text Text string to display, parse, or send.
 * @param roomKey Room key supplied to [prepareOutgoingMessage].
 * @param id ID supplied to [prepareOutgoingMessage].
 * @param now Current epoch timestamp in milliseconds.
 * @return The resulting prepare outgoing message.
 */
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

// All chat roles: defines ciphertext-only records retained for offline chat access.

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

/**
 * Converts chat conversation into cache JSON.
 *
 * @receiver The conversation from which to derive the stored or displayed data.
 * @return The resulting to cache JSON.
 */
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
    put("ticketSequence", ticketSequence)
    put("closedAt", closedAt)
}

/**
 * Computes conversation from cache JSON.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting [ChatConversation] for conversation from cache JSON.
 */
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
        ticketSequence = value.optLong("ticketSequence", -1L),
        closedAt = value.optLong("closedAt"),
    )
}

/**
 * Converts encrypted chat message into cache JSON.
 *
 * @receiver The encrypted message to serialize for the cache.
 * @return The resulting to cache JSON.
 */
internal fun EncryptedChatMessage.toCacheJson() = JSONObject().apply {
    put("id", id)
    put("senderUid", senderUid)
    put("senderUsername", senderUsername)
    put("ciphertext", ciphertext)
    put("sentAt", sentAt)
    put("deleted", deleted)
}

/**
 * Computes encrypted message from cache JSON.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting encrypted message from cache JSON.
 */
internal fun encryptedMessageFromCacheJson(value: JSONObject) = EncryptedChatMessage(
    id = value.getString("id"),
    senderUid = value.getString("senderUid"),
    senderUsername = value.optString("senderUsername"),
    ciphertext = value.optString("ciphertext"),
    sentAt = value.getLong("sentAt"),
    deleted = value.optBoolean("deleted"),
)

// All chat roles: drains one encrypted conversation queue without allowing overtaking.

internal enum class MessageDrainOutcome {
    COMPLETE,
    RETRY,
    BLOCKED,
}

internal data class MessageDeliveryFailure(
    val code: String,
    val permanent: Boolean,
)

/**
 * Delivers ciphertext in persisted sequence order. A failed head remains the queue barrier, so a
 * later message can never be attempted first after reconnection or process restart.
 */
internal class MessageQueueDrainer(
    private val store: MessageOutboxStore,
    private val activeOwnerUid: () -> String?,
    private val deliver: suspend (OutgoingMessage) -> Unit,
    private val classifyFailure: (Throwable) -> MessageDeliveryFailure,
    private val reportFailure: (OutgoingMessage, MessageDeliveryFailure, Throwable, Boolean) -> Unit =
        { _, _, _, _ -> },
    private val maxAutomaticAttempts: Int = 6,
) {
    init {
        require(maxAutomaticAttempts > 0)
    }

    /**
     * Computes drain.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return The resulting [MessageDrainOutcome] instance.
     */
    suspend fun drain(ownerUid: String, conversationId: String): MessageDrainOutcome {
        while (true) {
            val head = store.head(ownerUid, conversationId) ?: return MessageDrainOutcome.COMPLETE
            if (head.status == MessageDeliveryStatus.FAILED_PERMANENT) {
                return MessageDrainOutcome.BLOCKED
            }
            if (activeOwnerUid() != ownerUid) {
                store.update(head.id) {
                    it.copy(
                        status = MessageDeliveryStatus.FAILED_RETRYABLE,
                        failureCode = "ACCOUNT_UNAVAILABLE",
                    )
                }
                return MessageDrainOutcome.RETRY
            }

            val sending = store.update(head.id) {
                it.copy(
                    status = MessageDeliveryStatus.SENDING,
                    attemptCount = it.attemptCount + 1,
                    failureCode = null,
                )
            }
            try {
                deliver(sending)
                store.update(sending.id) {
                    it.copy(status = MessageDeliveryStatus.SENT, failureCode = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val deliveryFailure = classifyFailure(failure)
                val permanent = deliveryFailure.permanent ||
                        sending.attemptCount >= maxAutomaticAttempts
                reportFailure(sending, deliveryFailure, failure, permanent)
                store.update(sending.id) {
                    it.copy(
                        status = if (permanent) {
                            MessageDeliveryStatus.FAILED_PERMANENT
                        } else {
                            MessageDeliveryStatus.FAILED_RETRYABLE
                        },
                        failureCode = deliveryFailure.code,
                    )
                }
                return if (permanent) MessageDrainOutcome.BLOCKED else MessageDrainOutcome.RETRY
            }
        }
    }
}

// Users and staff: saves support tickets separately from plan consultations.

internal class SupportRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) {
    /**
     * Creates a new support ticket without sharing the safety plan.
     *
     * @param requestKey The same UUID for each retry of this request.
     * @return The assigned or waiting support ticket.
     */
    suspend fun requestHelp(requestKey: String): StaffSupportTicket =
        updateRequest("request", requestKey = requestKey)

    /**
     * Returns true when the user still has a staff request that can be cancelled.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return `true` if cancel request, or `false` otherwise.
     */
    suspend fun cancelRequest(conversationId: String): StaffSupportTicket =
        updateRequest("stop", conversationId)

    /**
     * Updates one support ticket without replacing its existing chat history.
     *
     * @param action Action supplied to [updateRequest].
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param requestKey Stable request UUID for a newly opened ticket.
     * @return The assigned or waiting support ticket.
     */
    private suspend fun updateRequest(
        action: String,
        conversationId: String = "",
        requestKey: String = "",
    ): StaffSupportTicket {
        val payload = buildMap {
            put("action", action)
            put("conversationId", conversationId)
            if (requestKey.isNotEmpty()) put("requestKey", requestKey)
        }
        val ticket = functions.callJsonObject("staffSupportRequest", payload).getJSONObject("ticket")
        return StaffSupportTicket(
            requested = ticket.optBoolean("requested"),
            staffUid = ticket.optString("staffUid"),
            conversationId = ticket.optString("conversationId"),
        )
    }

    /**
     * Adds an admin to a support ticket that needs escalation.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    suspend fun requestAdmin(conversationId: String) {
        functions.callJsonObject("safetySupportAdmin", mapOf("conversationId" to conversationId))
    }

    /**
     * Adds one accepted contact while the server keeps the existing conversation ID and history.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [addParticipant].
     */
    suspend fun addParticipant(conversationId: String, participantUid: String) {
        functions.callJsonObject(
            "conversationAddParticipant",
            mapOf("conversationId" to conversationId, "participantUid" to participantUid),
        )
    }

    /**
     * Opens a separate staff ticket containing everyone from the selected personal chat.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @return The resulting request staff for conversation string.
     */
    suspend fun requestStaffForConversation(conversationId: String): String =
        functions.callJsonObject("conversationRequestStaff", mapOf("conversationId" to conversationId))
            .optString("conversationId")
            .takeIf(String::isNotBlank)
            ?: error("The support chat was not created.")

    /**
     * Removes one member after the server confirms the caller owns the group.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [removeParticipant].
     */
    suspend fun removeParticipant(conversationId: String, participantUid: String) {
        functions.callJsonObject(
            "conversationRemoveParticipant",
            mapOf("conversationId" to conversationId, "participantUid" to participantUid),
        )
    }

    /**
     * Closes the support ticket and archives it for every participant.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param planCompleted Whether to apply plan completed.
     */
    suspend fun close(conversationId: String, planCompleted: Boolean = false) {
        functions.callJsonObject("staffSupportClose", mapOf("conversationId" to conversationId, "planCompleted" to planCompleted))
    }
}

internal data class StaffSupportTicket(
    val requested: Boolean,
    val staffUid: String,
    val conversationId: String,
)

/** References ciphertext already published to the media service. */
@Serializable
data class OutgoingAttachment(
    val mediaId: String,
) {
    init {
        require(validId(mediaId)) { "Attachment media ID is invalid" }
    }

    companion object {
        /**
         * Validates ID.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting valid ID.
         */
        private fun validId(value: String) =
            value.length <= 160 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}

/**
 * Only immutable ciphertext already published to the media service can enter the chat outbox.
 *
 * @param document Safety document metadata record.
 * @param ownerUid Firebase UID of the resource owner, or null when not provided.
 * @return `true` if is durable chat attachment, or `false` otherwise.
 */
internal fun isDurableChatAttachment(document: SafetyDocument, ownerUid: String?) =
    ownerUid != null && document.ownerUid == ownerUid && document.objectKey.isNotBlank() &&
        !document.isChatOnlyMedia()

/**
 * Uses the locally opened conversation key without touching Firebase while the device is offline.
 *
 * @param activeRoomKey Optional active room key supplied to [outgoingRoomKey].
 * @param loadRoomKey Function used to transform or provide load room key.
 * @return The resulting [SecretKey] for outgoing room key.
 */
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

/**
 * Computes attachment key unavailable.
 *
 * @param cause Cause supplied to [attachmentKeyUnavailable].
 * @return The resulting attachment key unavailable.
 */
internal fun attachmentKeyUnavailable(cause: GeneralSecurityException) =
    AttachmentDeliveryRejected("ATTACHMENT_KEY_UNAVAILABLE", cause)

/** Routes one sequenced outbox head without allowing attachments to bypass the text-message queue. */
internal class OutgoingDeliveryDispatcher(
    private val deliverText: suspend (OutgoingMessage) -> Unit,
    private val deliverAttachment: suspend (OutgoingMessage, String) -> Unit,
) {
    /**
     * Computes deliver.
     *
     * @param message Chat message or remote payload.
     */
    suspend fun deliver(message: OutgoingMessage) {
        val attachment = message.attachment
        if (attachment == null) {
            deliverText(message)
            return
        }
        deliverAttachment(message, attachment.mediaId)
    }
}
