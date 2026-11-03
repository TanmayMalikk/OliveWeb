// All chat roles: delivers durable ciphertext messages when Firebase becomes reachable.
package com.olive.app.data

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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** WorkManager boundary for idempotent encrypted-message delivery. */
class MessageSendWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val store = MessageOutboxStore(context)
    private val repository = SecureChatRepository()

    override suspend fun doWork(): Result {
        val ownerUid = inputData.getString(OWNER_UID) ?: return Result.failure()
        val conversationId = inputData.getString(CONVERSATION_ID) ?: return Result.failure()
        val outcome = MessageQueueDrainer(
            store = store,
            activeOwnerUid = { FirebaseAuth.getInstance().currentUser?.uid },
            deliver = repository::deliver,
            classifyFailure = ::messageDeliveryFailure,
        ).drain(ownerUid, conversationId)
        return when (outcome) {
            MessageDrainOutcome.COMPLETE -> Result.success()
            MessageDrainOutcome.RETRY -> Result.retry()
            MessageDrainOutcome.BLOCKED -> Result.failure()
        }
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

private fun messageDeliveryFailure(failure: Throwable): MessageDeliveryFailure =
    if (failure is FirebaseFirestoreException) {
        MessageDeliveryFailure(failure.code.name, isPermanentMessageFailure(failure.code))
    } else {
        MessageDeliveryFailure("TEMPORARY_FAILURE", permanent = false)
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
