// All chat roles: drains one encrypted conversation queue without allowing overtaking.
package com.olive.app.data

import kotlinx.coroutines.CancellationException

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
