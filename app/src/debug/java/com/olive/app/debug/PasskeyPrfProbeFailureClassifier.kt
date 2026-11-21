package com.olive.app.debug

import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.domerrors.ConstraintError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException

/**
 * Maps probe control-flow state to a non-sensitive user-facing outcome.
 *
 * Credential-provider exceptions can carry implementation details, so this class intentionally
 * examines only cancellation semantics and the local stage. It never retains an exception,
 * request JSON, credential ID, or PRF material.
 */
internal object PasskeyPrfProbeFailureClassifier {
    fun classify(
        stage: PasskeyPrfProbeStage,
        error: Exception,
    ): PasskeyPrfProbeFailure = when {
        error is PasskeyPrfProbeTimeout ->
            PasskeyPrfProbeFailure.ASSERTION_TIMED_OUT
        error.javaClass.simpleName.contains("Cancellation", ignoreCase = true) ->
            PasskeyPrfProbeFailure.CANCELLED
        error is CreateCredentialProviderConfigurationException ->
            PasskeyPrfProbeFailure.PROVIDER_CONFIGURATION
        error is CreateCredentialUnsupportedException ->
            PasskeyPrfProbeFailure.UNSUPPORTED
        error is CreatePublicKeyCredentialDomException -> when (error.domError) {
            is SecurityError -> PasskeyPrfProbeFailure.SECURITY_REJECTED
            is NotAllowedError -> PasskeyPrfProbeFailure.NOT_ALLOWED
            is ConstraintError -> PasskeyPrfProbeFailure.CONSTRAINT_REJECTED
            is InvalidStateError -> PasskeyPrfProbeFailure.INVALID_STATE
            else -> PasskeyPrfProbeFailure.REQUEST_REJECTED
        }
        stage == PasskeyPrfProbeStage.CREATING_PASSKEY ->
            PasskeyPrfProbeFailure.CREATION_FAILED
        else -> PasskeyPrfProbeFailure.ASSERTION_FAILED
    }
}

internal enum class PasskeyPrfProbeStage {
    CREATING_PASSKEY,
    REQUESTING_PRF_ASSERTION,
}

internal enum class PasskeyPrfProbeFailure {
    CANCELLED,
    ASSERTION_TIMED_OUT,
    PROVIDER_CONFIGURATION,
    UNSUPPORTED,
    SECURITY_REJECTED,
    NOT_ALLOWED,
    CONSTRAINT_REJECTED,
    INVALID_STATE,
    REQUEST_REJECTED,
    CREATION_FAILED,
    ASSERTION_FAILED,
}

/** A local deadline outcome only; it retains no provider data. */
internal data object PasskeyPrfProbeTimeout : Exception()
