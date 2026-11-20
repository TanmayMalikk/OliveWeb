package com.olive.app.debug

import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.domerrors.ConstraintError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.domerrors.UnknownError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyPrfProbeFailureClassifierTest {
    @Test
    fun creationFailureIsReportedSeparatelyFromAssertionFailure() {
        assertEquals(
            PasskeyPrfProbeFailure.CREATION_FAILED,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.CREATING_PASSKEY,
                IllegalStateException(),
            ),
        )
        assertEquals(
            PasskeyPrfProbeFailure.ASSERTION_FAILED,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.REQUESTING_PRF_ASSERTION,
                IllegalStateException(),
            ),
        )
    }

    @Test
    fun cancellationIsReportedWithoutExposingProviderDetails() {
        assertEquals(
            PasskeyPrfProbeFailure.CANCELLED,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.CREATING_PASSKEY,
                ProviderCancellationException(),
            ),
        )
    }

    @Test
    fun assertionTimeoutIsSeparatedFromUserCancellation() {
        assertEquals(
            PasskeyPrfProbeFailure.ASSERTION_TIMED_OUT,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.REQUESTING_PRF_ASSERTION,
                PasskeyPrfProbeTimeout,
            ),
        )
    }

    @Test
    fun typedCredentialManagerCreationErrorsUseSafeActionableCategories() {
        assertEquals(
            PasskeyPrfProbeFailure.PROVIDER_CONFIGURATION,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.CREATING_PASSKEY,
                CreateCredentialProviderConfigurationException(),
            ),
        )
        assertEquals(
            PasskeyPrfProbeFailure.UNSUPPORTED,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.CREATING_PASSKEY,
                CreateCredentialUnsupportedException(),
            ),
        )
        assertEquals(
            PasskeyPrfProbeFailure.REQUEST_REJECTED,
            PasskeyPrfProbeFailureClassifier.classify(
                PasskeyPrfProbeStage.CREATING_PASSKEY,
                CreatePublicKeyCredentialDomException(UnknownError()),
            ),
        )
    }

    @Test
    fun domRejectionsExposeOnlyTheirStandardSafeCategory() {
        assertEquals(
            PasskeyPrfProbeFailure.SECURITY_REJECTED,
            classifyDom(SecurityError()),
        )
        assertEquals(
            PasskeyPrfProbeFailure.NOT_ALLOWED,
            classifyDom(NotAllowedError()),
        )
        assertEquals(
            PasskeyPrfProbeFailure.CONSTRAINT_REJECTED,
            classifyDom(ConstraintError()),
        )
        assertEquals(
            PasskeyPrfProbeFailure.INVALID_STATE,
            classifyDom(InvalidStateError()),
        )
    }

    private class ProviderCancellationException : Exception()

    private fun classifyDom(error: androidx.credentials.exceptions.domerrors.DomError) =
        PasskeyPrfProbeFailureClassifier.classify(
            PasskeyPrfProbeStage.CREATING_PASSKEY,
            CreatePublicKeyCredentialDomException(error),
        )
}
