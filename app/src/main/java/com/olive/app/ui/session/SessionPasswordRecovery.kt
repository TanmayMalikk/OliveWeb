package com.olive.app.ui.session

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetException
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.AccountRecoveryKeyDeviceProtector
import com.olive.app.security.AccountRecoveryKeyDeviceRecord
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/**
 * Controller managing primary zero-knowledge OPAQUE password recovery workflows.
 */
internal class SessionPasswordRecovery(private val runtime: SessionRuntime) {

    /**
     * Queries the backend to update whether OPAQUE password recovery is enrolled for this session.
     */
    fun refreshPasswordRecovery() = with(runtime) {
        val uid = enrollment?.uid ?: return@with
        scope.launch {
            val enrolled = runCatching {
                dataSources.accountKeysets?.isPasswordRecoveryEnrolled(uid) ?: false
            }.getOrNull() ?: return@launch
            if (enrollment?.uid == uid) {
                state.passwordRecoveryEnrolled = enrolled
            }
        }
    }

    /**
     * Enrolls OPAQUE after authentication, preserving any existing recovery enrollment.
     *
     * @param uid The account authenticated by the credentials flow.
     * @param password The account password already entered for sign-in or creation.
     * @param token The session epoch captured by that credentials request.
     * @param setupRequired Whether recovery must be configured before entering onboarding.
     */
    internal suspend fun enrollAfterAuthentication(uid: String, password: String, token: Long, setupRequired: Boolean) = with(runtime) {
        check(settings.currentRecoverySession(uid, token)) { "Recovery session changed" }
        val keysets = checkNotNull(accountKeysetRepository)
        val recovery = checkNotNull(opaqueRecoveryRepository)
        val enrolled = keysets.isPasswordRecoveryEnrolled(uid)
        check(settings.currentRecoverySession(uid, token)) { "Recovery session changed" }
        state.passwordRecoveryEnrolled = enrolled
        if (enrolled) return@with

        val local = withContext(Dispatchers.IO) { accountKeysets.read(uid) }
        val remote = keysets.fetch(uid)
        check(settings.currentRecoverySession(uid, token)) { "Recovery session changed" }
        val matchingLocal = local?.takeIf {
            remote == null || (remote.keyId == it.envelope.keyId && remote.epoch == it.envelope.epoch)
        }
        val appManagedArk = matchingLocal
            ?.takeIf { it.deviceRecord.version == AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED }
            ?.let { saved ->
                runCatching { settings.unlockDeviceRecoveryKey(saved.deviceRecord) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?.also { if (remote == null) settings.publishKeyset(saved.envelope) }
            }
        val ark = when {
            appManagedArk != null -> appManagedArk
            remote == null -> {
                if (local != null) {
                    withContext(Dispatchers.IO) {
                        accountKeysets.purge(uid)?.let(AccountRecoveryKeyDeviceProtector()::forget)
                    }
                }
                settings.createAccountRecoveryKey(uid, token)
            }
            setupRequired -> error("Account recovery key unavailable on this device")
            else -> return@with
        }
        check(settings.currentRecoverySession(uid, token)) { "Recovery session changed" }
        recovery.enroll(uid, password, ark)
        if (settings.currentRecoverySession(uid, token)) state.passwordRecoveryEnrolled = true
    }

    /**
     * Rotates the account's OPAQUE password recovery secret from [oldPassword] to [newPassword].
     *
     * @param oldPassword Current recovery password used to authorize the rotation.
     * @param newPassword Replacement recovery password to enroll.
     */
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

    /**
     * Prompts the signed-in user for their recovery password, unlocks the encrypted account keyset
     * via OPAQUE, validates it against the server-stored keyset, and seals a local device copy.
     *
     * @param uid Authenticated account whose encrypted keyset is being recovered.
     * @param keyset Server-fetched encrypted account keyset used to validate the recovered key.
     * @param token Session generation used to reject stale authentication results.
     * @return The validated account recovery key held only in client memory.
     */
    internal suspend fun recoverAccountRecoveryKey(
        uid: String,
        keyset: AccountKeysetEnvelope,
        token: Long,
    ): AccountRecoveryKey = with(runtime) {
        val recovery = checkNotNull(opaqueRecoveryRepository) { "Password recovery service is unavailable" }
        val password = requestPasswordRecoveryUnlock(uid, token)
        val recoveryKey = recovery.unlock(uid, password)
        withContext(Dispatchers.IO) { keyset.unlock(uid, recoveryKey) }
        settings.protectDeviceRecoveryKey(uid, recoveryKey, keyset, token)
        return recoveryKey
    }

    /**
     * Prompts the UI for the user's recovery password and suspends until entered or cancelled.
     *
     * @param uid Expected Firebase UID for the active session.
     * @param token Session generation epoch captured when the operation began.
     * @return Non-empty recovery password entered by the user.
     */
    private suspend fun requestPasswordRecoveryUnlock(uid: String, token: Long): String = with(runtime) {
        check(currentRecoverySession(uid, token)) { "Recovery session changed" }
        val request = PasswordRecoveryUnlockRequest()
        state.passwordRecoveryUnlockRequest = request
        return try {
            request.result.await()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Password recovery was cancelled")
        } finally {
            if (state.passwordRecoveryUnlockRequest === request) {
                state.passwordRecoveryUnlockRequest = null
            }
        }
    }

    /**
     * Unlocks and seals the account's recovery key locally using [password].
     *
     * @param password Recovery password entered to complete OPAQUE unlock.
     */
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
    internal fun passwordRecoveryFailure(failure: Throwable): String {
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
                R.string.password_recovery_error_session_expired
            networkEx != null ->
                R.string.password_recovery_error_connection
            causes.any { it is IllegalArgumentException && it.message?.contains("Incorrect recovery password", ignoreCase = true) == true } ->
                R.string.password_recovery_error_incorrect
            causes.any { it is AEADBadTagException || it is AccountKeysetException } ->
                R.string.password_recovery_error_corrupted
            causes.any { it.message?.contains("belongs to another", ignoreCase = true) == true || it.message?.contains("mismatch", ignoreCase = true) == true } ->
                R.string.password_recovery_error_account_mismatch
            causes.any { it is IllegalStateException && it.message?.contains("cancelled", ignoreCase = true) == true } ->
                R.string.password_recovery_unlock_cancelled
            causes.any { it.message?.contains("Secure lock screen", ignoreCase = true) == true || it.message?.contains("device lock", ignoreCase = true) == true } ->
                R.string.password_recovery_error_device_lock_required
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
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    R.string.password_recovery_error_throttled
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    if (functions.message?.contains("revoked", ignoreCase = true) == true) R.string.password_recovery_error_revoked
                    else R.string.password_recovery_error_incorrect
                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> when {
                    functions.message?.contains("OPAQUE", ignoreCase = true) == true ->
                        R.string.password_recovery_error_runtime_unavailable
                    functions.message?.contains("Sign in again", ignoreCase = true) == true ->
                        R.string.password_recovery_error_session_expired
                    else -> R.string.password_recovery_error_unsupported
                }
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    R.string.password_recovery_error_connection
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    R.string.password_recovery_error_corrupted
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    R.string.password_recovery_error_session_expired
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

    /**
     * Returns true while [uid] and [token] still match the active signed-in session.
     *
     * @param uid Expected Firebase UID captured before async work.
     * @param token Session epoch captured before async work.
     * @return True when neither the signed-in account nor session epoch changed.
     */
    private fun currentRecoverySession(uid: String, token: Long): Boolean = with(runtime) {
        enrollment?.uid == uid && epoch == token
    }
}
