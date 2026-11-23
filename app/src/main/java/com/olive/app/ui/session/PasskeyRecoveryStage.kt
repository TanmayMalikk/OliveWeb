package com.olive.app.ui.session

import kotlinx.coroutines.CancellationException

internal enum class PasskeyRecoveryStage {
    VERIFY_CREATED_PASSKEY,
    SAVE,
    BEGIN_UNLOCK,
    REQUEST_PASSKEY,
    COMPLETE_UNLOCK,
    VERIFY_PRF_INPUT,
    OPEN_RECOVERY_ENVELOPE,
    VALIDATE_ACCOUNT_KEYSET,
    PROTECT_DEVICE_COPY,
}

internal class PasskeyRecoveryStageException(
    val stage: PasskeyRecoveryStage,
    cause: Exception,
) : Exception(cause)

/**
 * Preserves the non-secret stage at which a passkey recovery operation failed.
 *
 * @param stage Recovery stage currently being executed.
 * @param block Operation whose exception should be classified.
 * @return The successful operation result.
 */
internal suspend fun <T> atPasskeyRecoveryStage(
    stage: PasskeyRecoveryStage,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw PasskeyRecoveryStageException(stage, failure)
}
