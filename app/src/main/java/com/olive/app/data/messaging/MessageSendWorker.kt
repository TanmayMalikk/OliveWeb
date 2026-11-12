// All chat roles: delivers durable ciphertext messages when Firebase becomes reachable.
package com.olive.app.data.messaging

import android.content.Context
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
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaRepository
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.olive.app.security.EncryptionUtils
import java.util.UUID
import javax.crypto.SecretKey
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.account.AccountIdentity
import kotlinx.coroutines.tasks.await


/** WorkManager boundary for idempotent encrypted-message delivery. */
class MessageSendWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val store = MessageOutboxStore(context)
    private val repository = SecureChatRepository()
    private val conversations = MessagingRepository()
    private val documents = SafetyDocumentRepository(media = MediaRepository())

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
        ).drain(ownerUid, conversationId)
        return when (outcome) {
            MessageDrainOutcome.COMPLETE -> Result.success()
            MessageDrainOutcome.RETRY -> Result.retry()
            MessageDrainOutcome.BLOCKED -> Result.failure()
        }
    }

    private suspend fun deliverAttachment(message: OutgoingMessage, mediaId: String) {
        val conversation = conversations.getConversation(message.conversationId)
            ?: throw AttachmentDeliveryRejected("CONVERSATION_NOT_FOUND")
        if (message.ownerUid !in conversation.participantIds) {
            throw AttachmentDeliveryRejected("PERMISSION_DENIED")
        }
        val recipients = conversation.participantIds.filter { it != message.ownerUid }.associateWith { uid ->
            conversation.participantUsernames[uid]
                ?: throw AttachmentDeliveryRejected("RECIPIENT_UNAVAILABLE")
        }
        if (recipients.isEmpty()) throw AttachmentDeliveryRejected("RECIPIENT_UNAVAILABLE")
        val document = documents.getFor(message.ownerUid, mediaId)
        documents.shareInChat(document, message, recipients)
    }

    companion object {
        private const val OWNER_UID = "ownerUid"
        private const val CONVERSATION_ID = "conversationId"

        fun workName(ownerUid: String, conversationId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$ownerUid\u0000$conversationId".toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "olive_message_drain_$digest"
        }
        private fun ownerTag(ownerUid: String) = "olive_messages_owner_$ownerUid"

        fun enqueue(context: Context, message: OutgoingMessage) {
            val request = OneTimeWorkRequestBuilder<MessageSendWorker>()
                .setInputData(
                    workDataOf(
                        OWNER_UID to message.ownerUid,
                        CONVERSATION_ID to message.conversationId,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ownerTag(message.ownerUid))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(message.ownerUid, message.conversationId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun enqueuePending(context: Context, ownerUid: String) {
            val store = MessageOutboxStore(context)
            store.drainableConversationIds(ownerUid).forEach { conversationId ->
                store.head(ownerUid, conversationId)?.let { enqueue(context, it) }
            }
        }

        fun cancelOwner(context: Context, ownerUid: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ownerTag(ownerUid))
        }
    }
}

internal fun messageDeliveryFailure(failure: Throwable): MessageDeliveryFailure = when (failure) {
    is AttachmentDeliveryRejected -> MessageDeliveryFailure(failure.failureCode, permanent = true)
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

internal fun isPermanentMessageFailure(code: FirebaseFirestoreException.Code) =
    isPermanentMessageFailure(code.name)

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

internal fun canManuallyRetryMessage(status: MessageDeliveryStatus) =
    status == MessageDeliveryStatus.FAILED_RETRYABLE

internal fun canDiscardFailedMessage(status: MessageDeliveryStatus) =
    status == MessageDeliveryStatus.FAILED_PERMANENT

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
    val clientSequence: Long = 0,
    val status: MessageDeliveryStatus = MessageDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val failureCode: String? = null,
    val attachment: OutgoingAttachment? = null,
) {
    init {
        require(id.isNotBlank() && ownerUid.isNotBlank() && conversationId.isNotBlank())
        require(senderUsername.isNotBlank() && ciphertext.isNotBlank())
        require(clientSentAt >= 0 && clientSequence >= 0 && attemptCount >= 0)
    }

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("ownerUid", ownerUid)
        put("conversationId", conversationId)
        put("senderUsername", senderUsername)
        put("ciphertext", ciphertext)
        put("clientSentAt", clientSentAt)
        put("clientSequence", clientSequence)
        put("status", status.name)
        put("attemptCount", attemptCount)
        failureCode?.let { put("failureCode", it) }
        attachment?.let { put("attachment", it.toJson()) }
    }

    companion object {
        fun fromJson(value: JSONObject) = OutgoingMessage(
            id = value.getString("id"),
            ownerUid = value.getString("ownerUid"),
            conversationId = value.getString("conversationId"),
            senderUsername = value.getString("senderUsername"),
            ciphertext = value.getString("ciphertext"),
            clientSentAt = value.getLong("clientSentAt"),
            clientSequence = value.optLong("clientSequence", 0),
            status = MessageDeliveryStatus.valueOf(value.getString("status")),
            attemptCount = value.optInt("attemptCount", 0),
            failureCode = value.optString("failureCode").takeIf(String::isNotBlank),
            attachment = value.optJSONObject("attachment")?.let(OutgoingAttachment::fromJson),
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
    private val maxAutomaticAttempts: Int = 6,
) {
    init {
        require(maxAutomaticAttempts > 0)
    }

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
    // Calls a protected support-ticket function and checks its response.
    private suspend fun call(name: String, data: Map<String, Any>): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    // Creates a new support ticket without sharing the safety plan.
    suspend fun requestHelp(): StaffSupportTicket = updateRequest("request")

    // Returns true when the user still has a staff request that can be cancelled.
    suspend fun cancelRequest(conversationId: String): StaffSupportTicket =
        updateRequest("stop", conversationId)

    // Updates one support ticket without replacing its existing chat history.
    private suspend fun updateRequest(action: String, conversationId: String = ""): StaffSupportTicket {
        val ticket = call(
            "staffSupportRequest",
            mapOf("action" to action, "conversationId" to conversationId),
        ).getJSONObject("ticket")
        return StaffSupportTicket(
            requested = ticket.optBoolean("requested"),
            staffUid = ticket.optString("staffUid"),
            conversationId = ticket.optString("conversationId"),
        )
    }

    // Adds an admin to a support ticket that needs escalation.
    suspend fun requestAdmin(conversationId: String) {
        call("safetySupportAdmin", mapOf("conversationId" to conversationId))
    }

    // Adds one accepted contact while the server keeps the existing conversation ID and history.
    suspend fun addParticipant(conversationId: String, participantUid: String) {
        call(
            "conversationAddParticipant",
            mapOf("conversationId" to conversationId, "participantUid" to participantUid),
        )
    }

    // Opens a separate staff ticket containing everyone from the selected personal chat.
    suspend fun requestStaffForConversation(conversationId: String): String =
        call("conversationRequestStaff", mapOf("conversationId" to conversationId))
            .optString("conversationId")
            .takeIf(String::isNotBlank)
            ?: error("The support chat was not created.")

    // Removes one member after the server confirms the caller owns the group.
    suspend fun removeParticipant(conversationId: String, participantUid: String) {
        call(
            "conversationRemoveParticipant",
            mapOf("conversationId" to conversationId, "participantUid" to participantUid),
        )
    }

    // Closes the support ticket and archives it for every participant.
    suspend fun close(conversationId: String) {
        call("staffSupportClose", mapOf("conversationId" to conversationId))
    }
}

internal data class StaffSupportTicket(
    val requested: Boolean,
    val staffUid: String,
    val conversationId: String,
)
