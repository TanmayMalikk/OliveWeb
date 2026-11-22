package com.olive.app.security

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NetworkError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyCredentialFailureTest {
    @Test
    fun `credential manager failures retain actionable categories`() {
        val cases = listOf(
            CreateCredentialCancellationException() to PasskeyCredentialFailure.CANCELLED,
            CreateCredentialInterruptedException() to PasskeyCredentialFailure.INTERRUPTED,
            CreateCredentialNoCreateOptionException() to PasskeyCredentialFailure.PROVIDER_UNAVAILABLE,
            CreateCredentialProviderConfigurationException() to PasskeyCredentialFailure.PROVIDER_UNAVAILABLE,
            CreateCredentialUnsupportedException() to PasskeyCredentialFailure.UNSUPPORTED,
            NoCredentialException() to PasskeyCredentialFailure.NO_CREDENTIAL,
            CreatePublicKeyCredentialDomException(NetworkError()) to PasskeyCredentialFailure.NETWORK,
            CreatePublicKeyCredentialDomException(SecurityError()) to PasskeyCredentialFailure.SECURITY,
            CreatePublicKeyCredentialDomException(NotSupportedError()) to PasskeyCredentialFailure.UNSUPPORTED,
            CreatePublicKeyCredentialDomException(InvalidStateError()) to PasskeyCredentialFailure.ALREADY_EXISTS,
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, classifyPasskeyCredentialFailure(failure))
        }
    }

    @Test
    fun `unexpected failures remain bounded and deterministic under repetition`() {
        val failures = List(100) { index ->
            if (index % 2 == 0) {
                IllegalStateException("invalid response") to PasskeyCredentialFailure.INVALID_RESPONSE
            } else {
                RuntimeException("provider failure") to PasskeyCredentialFailure.UNKNOWN
            }
        }

        failures.forEach { (failure, expected) ->
            assertEquals(expected, classifyPasskeyCredentialFailure(failure))
        }
    }
}
