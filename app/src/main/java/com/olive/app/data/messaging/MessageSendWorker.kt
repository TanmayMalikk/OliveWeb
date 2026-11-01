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
import java.util.concurrent.TimeUnit

/** WorkManager boundary for idempotent encrypted-message delivery. */
class MessageSendWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val store = MessageOutboxStore(context)
    private val repository = SecureChatRepository()

    override suspend fun doWork(): Result {
        val id = inputData.getString(MESSAGE_ID) ?: return Result.failure()
        var message = store.get(id) ?: return Result.success()
        if (message.status == MessageDeliveryStatus.SENT) return Result.success()

        val activeUid = FirebaseAuth.getInstance().currentUser?.uid
        if (activeUid != message.ownerUid) {
            store.update(id) {
                it.copy(status = MessageDeliveryStatus.FAILED_RETRYABLE, failureCode = "ACCOUNT_UNAVAILABLE")
            }
            return Result.retry()
        }

        message = store.update(id) {
            it.copy(
                status = MessageDeliveryStatus.SENDING,
                attemptCount = it.attemptCount + 1,
                failureCode = null,
            )
        }
        return try {
            repository.deliver(message)
            store.update(id) { it.copy(status = MessageDeliveryStatus.SENT, failureCode = null) }
            Result.success()
        } catch (failure: FirebaseFirestoreException) {
            if (isPermanentMessageFailure(failure.code) || runAttemptCount >= MAX_AUTOMATIC_RETRIES) {
                store.update(id) {
                    it.copy(
                        status = MessageDeliveryStatus.FAILED_PERMANENT,
                        failureCode = failure.code.name,
                    )
                }
                Result.failure()
            } else {
                store.update(id) {
                    it.copy(
                        status = MessageDeliveryStatus.FAILED_RETRYABLE,
                        failureCode = failure.code.name,
                    )
                }
                Result.retry()
            }
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_AUTOMATIC_RETRIES) {
                store.update(id) {
                    it.copy(status = MessageDeliveryStatus.FAILED_PERMANENT, failureCode = "DELIVERY_FAILED")
                }
                Result.failure()
            } else {
                store.update(id) {
                    it.copy(status = MessageDeliveryStatus.FAILED_RETRYABLE, failureCode = "TEMPORARY_FAILURE")
                }
                Result.retry()
            }
        }
    }

    companion object {
        private const val MESSAGE_ID = "messageId"
        private const val MAX_AUTOMATIC_RETRIES = 5

        fun workName(messageId: String) = "olive_message_$messageId"
        private fun ownerTag(ownerUid: String) = "olive_messages_owner_$ownerUid"

        fun enqueue(
            context: Context,
            message: OutgoingMessage,
            replaceDelayedWork: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<MessageSendWorker>()
                .setInputData(workDataOf(MESSAGE_ID to message.id))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ownerTag(message.ownerUid))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(message.id),
                if (replaceDelayedWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueuePending(context: Context, ownerUid: String) {
            MessageOutboxStore(context).all(ownerUid)
                .filter { it.status !in setOf(MessageDeliveryStatus.SENT, MessageDeliveryStatus.FAILED_PERMANENT) }
                .forEach { enqueue(context, it) }
        }

        fun cancelOwner(context: Context, ownerUid: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ownerTag(ownerUid))
        }
    }
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
