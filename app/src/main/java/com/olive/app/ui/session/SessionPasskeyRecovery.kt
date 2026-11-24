package com.olive.app.ui.session

import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.PasskeyCredentialFailure
import com.olive.app.security.PasskeyPrfProtocol
import com.olive.app.security.PasskeyRecoveryEnvelope
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller managing passkey PRF recovery workflows.
 * Passkeys are always preferred first over password recovery fallback.
 */
internal class SessionPasskeyRecovery(private val runtime: SessionRuntime) {

    fun refreshPasskeyRecovery() = with(runtime) {
        val uid = enrollment?.uid ?: return@with
        val repository = passkeyRecoveryRepository ?: return@with
        scope.launch {
            val credentials = runCatching { repository.listCredentials() }.getOrNull() ?: return@launch
            if (enrollment?.uid == uid) state.passkeyRecoveryCredentials = credentials
        }
    }

    fun enablePasskeyRecovery() = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = passkeyRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(
                application.getString(R.string.passkey_recovery_setup_failed),
                timeoutMillis = 180_000,
                failureFor = { passkeyRecoveryFailureMessage(it) },
            ) { token ->
                val ark = settings.accountRecoveryKey(uid, token)
                val registration = recovery.beginEnrollment()
                val registrationResult = requestPasskey(
                    PasskeyPrfProtocol.registrationRequest(registration.options),
                    assertion = false,
                    uid = uid,
                    token = token,
                ) as? PasskeyCredentialResult.Registration
                    ?: error("Passkey registration was cancelled")
                val proof = atPasskeyRecoveryStage(PasskeyRecoveryStage.VERIFY_CREATED_PASSKEY) {
                    recovery.continueEnrollment(
                        registration.challengeId,
                        registrationResult.serverResponseJson,
                    )
                }
                val assertion = requestPasskey(
                    PasskeyPrfProtocol.assertionRequest(proof.options, proof.prfInputs),
                    assertion = true,
                    uid = uid,
                    token = token,
                ) as? PasskeyCredentialResult.Assertion
                    ?: error("Passkey verification was cancelled")
                val result = assertion.value
                try {
                    check(proof.prfInputs.containsKey(result.credentialId)) {
                        "Passkey proof selected an unexpected credential"
                    }
                    val envelope = PasskeyRecoveryEnvelope.create(
                        uid,
                        result.credentialId,
                        result.prfOutput,
                        ark,
                    )
                    atPasskeyRecoveryStage(PasskeyRecoveryStage.SAVE) {
                        recovery.completeEnrollment(
                            proof.challengeId,
                            result.serverResponseJson,
                            envelope,
                        )
                    }
                } finally {
                    result.prfOutput.fill(0)
                }
                val credentials = runCatching { recovery.listCredentials() }.getOrNull()
                if (currentRecoverySession(uid, token)) {
                    passkeyRecoveryCredentials = credentials
                    message = application.getString(R.string.passkey_recovery_enabled)
                }
            }
        }
    }

    fun removePasskeyRecovery(credentialId: String, password: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = passkeyRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(
                application.getString(R.string.passkey_recovery_remove_failed),
                failureFor = ::passkeyRecoveryRemovalFailure,
            ) { token ->
                recovery.reauthenticate(uid, password)
                recovery.removeCredential(credentialId)
                val credentials = recovery.listCredentials()
                if (currentRecoverySession(uid, token)) {
                    passkeyRecoveryCredentials = credentials
                    message = application.getString(R.string.passkey_recovery_removed)
                }
            }
        }
    }

    internal suspend fun recoverAccountRecoveryKey(
        uid: String,
        keyset: AccountKeysetEnvelope,
        token: Long,
    ): AccountRecoveryKey = with(runtime) {
        val repository = checkNotNull(passkeyRecoveryRepository)
        val start = atPasskeyRecoveryStage(PasskeyRecoveryStage.BEGIN_UNLOCK) {
            repository.beginUnlock()
        }
        val assertion = atPasskeyRecoveryStage(PasskeyRecoveryStage.REQUEST_PASSKEY) {
            requestPasskey(
                PasskeyPrfProtocol.assertionRequest(start.options, start.prfInputs),
                assertion = true,
                uid = uid,
                token = token,
            ) as? PasskeyCredentialResult.Assertion ?: error("Passkey recovery was cancelled")
        }
        val result = assertion.value
        try {
            val completion = atPasskeyRecoveryStage(PasskeyRecoveryStage.COMPLETE_UNLOCK) {
                repository.completeUnlock(start.challengeId, result.serverResponseJson)
            }
            atPasskeyRecoveryStage(PasskeyRecoveryStage.VERIFY_PRF_INPUT) {
                check(start.prfInputs[result.credentialId] == completion.prfInput) {
                    "Passkey recovery input mismatch"
                }
            }
            val recoveryKey = atPasskeyRecoveryStage(PasskeyRecoveryStage.OPEN_RECOVERY_ENVELOPE) {
                completion.envelope.open(uid, result.credentialId, result.prfOutput)
            }
            atPasskeyRecoveryStage(PasskeyRecoveryStage.VALIDATE_ACCOUNT_KEYSET) {
                withContext(Dispatchers.IO) { keyset.unlock(uid, recoveryKey) }
            }
            atPasskeyRecoveryStage(PasskeyRecoveryStage.PROTECT_DEVICE_COPY) {
                settings.protectDeviceRecoveryKey(uid, recoveryKey, keyset, token)
            }
            return recoveryKey
        } finally {
            result.prfOutput.fill(0)
        }
    }

    internal suspend fun requestPasskey(
        requestJson: String,
        assertion: Boolean,
        uid: String,
        token: Long,
    ): PasskeyCredentialResult = with(runtime) {
        check(currentRecoverySession(uid, token)) { "Recovery session changed" }
        val request = PasskeyCredentialRequest(requestJson, assertion)
        state.passkeyCredentialRequest = request
        return try {
            when (val result = request.result.await()) {
                null -> throw PasskeyCredentialFailureException(PasskeyCredentialFailure.CANCELLED)
                is PasskeyCredentialResult.Failure -> throw PasskeyCredentialFailureException(result.reason)
                else -> result
            }
        } finally {
            if (state.passkeyCredentialRequest === request) {
                state.passkeyCredentialRequest = null
            }
        }
    }

    private fun passkeyRecoveryRemovalFailure(failure: Exception): String = with(runtime.application) {
        when (failure) {
            is FirebaseAuthInvalidCredentialsException ->
                getString(R.string.passkey_recovery_reauth_incorrect_password)
            is FirebaseAuthInvalidUserException ->
                getString(R.string.passkey_recovery_reauth_session_expired)
            else -> getString(R.string.passkey_recovery_remove_failed)
        }
    }

    private fun passkeyRecoveryFailureMessage(failure: Exception): String {
        android.util.Log.w("SessionPasskeyRecovery", "Passkey recovery failure: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val stage = causes.filterIsInstance<PasskeyRecoveryStageException>().firstOrNull()
        val credential = causes.filterIsInstance<PasskeyCredentialFailureException>().firstOrNull()
        val functions = causes.filterIsInstance<FirebaseFunctionsException>().firstOrNull()
        val firestore = causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        val message = when {
            stage?.stage == PasskeyRecoveryStage.VERIFY_CREATED_PASSKEY ->
                R.string.passkey_recovery_created_not_verified
            stage?.stage == PasskeyRecoveryStage.SAVE -> R.string.passkey_recovery_verified_not_saved
            credential != null -> when (credential.reason) {
                PasskeyCredentialFailure.CANCELLED -> R.string.passkey_recovery_setup_cancelled
                PasskeyCredentialFailure.INTERRUPTED -> R.string.passkey_recovery_setup_interrupted
                PasskeyCredentialFailure.PROVIDER_UNAVAILABLE -> R.string.passkey_recovery_provider_unavailable
                PasskeyCredentialFailure.UNSUPPORTED -> R.string.passkey_recovery_unsupported
                PasskeyCredentialFailure.NO_CREDENTIAL -> R.string.passkey_recovery_no_credential
                PasskeyCredentialFailure.NETWORK -> R.string.passkey_recovery_network_error
                PasskeyCredentialFailure.SECURITY -> R.string.passkey_recovery_security_error
                PasskeyCredentialFailure.ALREADY_EXISTS -> R.string.passkey_recovery_already_exists
                PasskeyCredentialFailure.INVALID_RESPONSE -> R.string.passkey_recovery_invalid_response
                PasskeyCredentialFailure.UNKNOWN -> R.string.passkey_recovery_setup_failed
            }
            functions != null -> when (functions.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> R.string.passkey_recovery_sign_in_again
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> R.string.passkey_recovery_network_error
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    if (functions.message?.contains("existing", ignoreCase = true) == true) {
                        R.string.passkey_recovery_already_exists
                    } else {
                        R.string.passkey_recovery_server_rejected
                    }
                else -> R.string.passkey_recovery_server_rejected
            }
            firestore != null -> R.string.passkey_recovery_storage_denied
            else -> R.string.passkey_recovery_setup_failed
        }
        return runtime.application.getString(message)
    }

    private fun currentRecoverySession(uid: String, token: Long): Boolean = with(runtime) {
        enrollment?.uid == uid && epoch == token
    }
}
