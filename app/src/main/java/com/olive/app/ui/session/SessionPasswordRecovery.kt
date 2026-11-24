package com.olive.app.ui.session

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.security.AccountKeysetException
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/**
 * Controller managing zero-knowledge password recovery failsafe workflows.
 *
 * Implements failsafe-only recovery when no passkeys are enrolled, when passkeys fail,
 * or when the user explicitly chooses password recovery. Passkeys are always preferred first.
 */
internal class SessionPasswordRecovery(private val runtime: SessionRuntime) {

    fun refreshPasswordRecovery() = with(runtime) {
        val uid = enrollment?.uid ?: return@with
        scope.launch {
            val enrolled = runCatching {
                dataSources.accountKeysets?.isPasswordRecoveryEnrolled(uid) ?: false
            }.getOrNull() ?: false
            if (enrollment?.uid == uid) {
                state.passwordRecoveryEnrolled = enrolled
            }
        }
    }

    fun enablePasswordRecovery(password: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = opaqueRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(
                application.getString(R.string.password_recovery_error_unsupported),
                failureFor = ::passwordRecoveryFailure,
            ) { token ->
                val ark = settings.accountRecoveryKey(uid, token)
                recovery.enroll(uid, password, ark)
                if (currentRecoverySession(uid, token)) {
                    passwordRecoveryEnrolled = true
                    message = application.getString(R.string.password_recovery_added)
                }
            }
        }
    }

    fun rotatePasswordRecovery(oldPassword: String, newPassword: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = opaqueRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(
                application.getString(R.string.password_recovery_error_unsupported),
                failureFor = ::passwordRecoveryFailure,
            ) { token ->
                val ark = settings.accountRecoveryKey(uid, token)
                recovery.rotate(uid, oldPassword, newPassword, ark)
                if (currentRecoverySession(uid, token)) {
                    passwordRecoveryEnrolled = true
                    message = application.getString(R.string.password_recovery_changed)
                }
            }
        }
    }

    fun removePasswordRecovery(password: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = opaqueRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with

            // Safeguard: Block removal if no passkey recovery factors are enrolled
            val passkeys = passkeyRecoveryCredentials
            if (passkeys.isNullOrEmpty()) {
                message = application.getString(R.string.password_recovery_remove_blocked_last)
                return@with
            }

            operations.runOperation(
                application.getString(R.string.password_recovery_error_unsupported),
                failureFor = ::passwordRecoveryFailure,
            ) { token ->
                recovery.remove(uid, password)
                if (currentRecoverySession(uid, token)) {
                    passwordRecoveryEnrolled = false
                    message = application.getString(R.string.password_recovery_removed)
                }
            }
        }
    }

    fun unlockWithPasswordRecovery(password: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = opaqueRecoveryRepository ?: return@with
            operations.runOperation(
                application.getString(R.string.password_recovery_error_unsupported),
                failureFor = ::passwordRecoveryFailure,
            ) { token ->
                val keyset = dataSources.accountKeysets?.fetch(uid)
                    ?: error("Missing account keyset")
                val ark = recovery.unlock(uid, password)
                withContext(Dispatchers.IO) { keyset.unlock(uid, ark) }
                settings.protectDeviceRecoveryKey(uid, ark, keyset, token)
            }
        }
    }

    /**
     * Resolves a user-friendly error message for password recovery failures based on root causes.
     *
     * @param failure Throwable thrown during a password recovery operation.
     * @return Human-readable string suitable for display in the UI.
     */
    private fun passwordRecoveryFailure(failure: Throwable): String {
        android.util.Log.w("SessionPasswordRecovery", "Password recovery failure: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val functions = causes.filterIsInstance<FirebaseFunctionsException>().firstOrNull()
        val firestore = causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        val badCredentials = causes.filterIsInstance<FirebaseAuthInvalidCredentialsException>().firstOrNull()
        val invalidUser = causes.filterIsInstance<FirebaseAuthInvalidUserException>().firstOrNull()
        val networkEx = causes.filterIsInstance<FirebaseNetworkException>().firstOrNull()

        val stringRes = when {
            badCredentials != null ->
                R.string.password_recovery_error_incorrect
            invalidUser != null ->
                R.string.passkey_recovery_reauth_session_expired
            networkEx != null ->
                R.string.password_recovery_error_connection
            causes.any { it is IllegalArgumentException && it.message?.contains("Incorrect recovery password", ignoreCase = true) == true } ->
                R.string.password_recovery_error_incorrect
            causes.any { it is AEADBadTagException || it is AccountKeysetException } ->
                R.string.password_recovery_error_corrupted
            causes.any { it.message?.contains("belongs to another", ignoreCase = true) == true || it.message?.contains("mismatch", ignoreCase = true) == true } ->
                R.string.password_recovery_error_account_mismatch
            causes.any { it.message?.contains("active passkey", ignoreCase = true) == true } ->
                R.string.password_recovery_remove_blocked_last
            causes.any { it is IllegalStateException && it.message?.contains("cancelled", ignoreCase = true) == true } ->
                R.string.passkey_recovery_setup_cancelled
            causes.any { it.message?.contains("Secure lock screen", ignoreCase = true) == true || it.message?.contains("device lock", ignoreCase = true) == true } ->
                R.string.passkey_recovery_provider_unavailable
            causes.any { it.message?.contains("No recovery passkeys", ignoreCase = true) == true } ->
                R.string.password_recovery_remove_blocked_last
            causes.any {
                it is IllegalStateException && (
                    it.message?.contains("OPAQUE WebAssembly", ignoreCase = true) == true ||
                        it.message?.contains("OPAQUE client operation", ignoreCase = true) == true
                    )
            } ->
                R.string.password_recovery_error_runtime_unavailable
            causes.any { it.message?.contains("Account recovery key", ignoreCase = true) == true } ->
                R.string.password_recovery_error_runtime_unavailable
            functions != null -> when (functions.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    if (functions.message?.contains("revoked", ignoreCase = true) == true) R.string.password_recovery_error_revoked
                    else R.string.password_recovery_error_incorrect
                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> when {
                    functions.message?.contains("passkey", ignoreCase = true) == true ->
                        R.string.password_recovery_remove_blocked_last
                    functions.message?.contains("OPAQUE", ignoreCase = true) == true ->
                        R.string.password_recovery_error_runtime_unavailable
                    functions.message?.contains("Sign in again", ignoreCase = true) == true ->
                        R.string.passkey_recovery_sign_in_again
                    else -> R.string.password_recovery_error_unsupported
                }
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    R.string.password_recovery_error_connection
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    R.string.password_recovery_error_corrupted
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    R.string.passkey_recovery_reauth_session_expired
                else -> R.string.password_recovery_error_unsupported
            }
            firestore != null -> when (firestore.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    R.string.password_recovery_error_connection
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    R.string.password_recovery_error_revoked
                else -> R.string.password_recovery_error_unsupported
            }
            else -> R.string.password_recovery_error_unsupported
        }

        return runtime.application.getString(stringRes)
    }

    private fun currentRecoverySession(uid: String, token: Long): Boolean = with(runtime) {
        enrollment?.uid == uid && epoch == token
    }
}
