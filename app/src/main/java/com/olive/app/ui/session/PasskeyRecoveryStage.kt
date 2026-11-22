package com.olive.app.ui.session

import kotlinx.coroutines.CancellationException

internal enum class PasskeyRecoveryStage {
    VERIFY_CREATED_PASSKEY,
    SAVE,
}

internal class PasskeyRecoveryStageException(
    val stage: PasskeyRecoveryStage,
    cause: Exception,
) : Exception(cause)

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
