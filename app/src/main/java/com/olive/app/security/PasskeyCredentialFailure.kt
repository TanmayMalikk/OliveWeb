package com.olive.app.security

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.AbortError
import androidx.credentials.exceptions.domerrors.DataError
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NetworkError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.domerrors.SyntaxError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException

internal enum class PasskeyCredentialFailure {
    CANCELLED,
    INTERRUPTED,
    PROVIDER_UNAVAILABLE,
    UNSUPPORTED,
    NO_CREDENTIAL,
    NETWORK,
    SECURITY,
    ALREADY_EXISTS,
    INVALID_RESPONSE,
    UNKNOWN,
}

/** Reduces provider-specific exceptions to bounded, non-secret recovery diagnostics. */
internal fun classifyPasskeyCredentialFailure(failure: Throwable): PasskeyCredentialFailure = when (failure) {
    is CreateCredentialCancellationException,
    is GetCredentialCancellationException -> PasskeyCredentialFailure.CANCELLED

    is CreateCredentialInterruptedException,
    is GetCredentialInterruptedException -> PasskeyCredentialFailure.INTERRUPTED

    is CreateCredentialNoCreateOptionException,
    is CreateCredentialProviderConfigurationException,
    is GetCredentialProviderConfigurationException -> PasskeyCredentialFailure.PROVIDER_UNAVAILABLE

    is CreateCredentialUnsupportedException,
    is GetCredentialUnsupportedException -> PasskeyCredentialFailure.UNSUPPORTED

    is NoCredentialException -> PasskeyCredentialFailure.NO_CREDENTIAL
    is CreatePublicKeyCredentialDomException -> classifyDomFailure(failure.domError)
    is GetPublicKeyCredentialDomException -> classifyDomFailure(failure.domError)
    is IllegalStateException -> PasskeyCredentialFailure.INVALID_RESPONSE
    else -> PasskeyCredentialFailure.UNKNOWN
}

private fun classifyDomFailure(failure: DomError): PasskeyCredentialFailure = when (failure) {
    is AbortError, is NotAllowedError -> PasskeyCredentialFailure.CANCELLED
    is NetworkError -> PasskeyCredentialFailure.NETWORK
    is SecurityError -> PasskeyCredentialFailure.SECURITY
    is NotSupportedError -> PasskeyCredentialFailure.UNSUPPORTED
    is InvalidStateError -> PasskeyCredentialFailure.ALREADY_EXISTS
    is DataError, is SyntaxError -> PasskeyCredentialFailure.INVALID_RESPONSE
    else -> PasskeyCredentialFailure.UNKNOWN
}
