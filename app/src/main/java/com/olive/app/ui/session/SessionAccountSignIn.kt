// All account roles: checks credentials, loads the account, and opens the next screen.
package com.olive.app.ui.session

import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.AccountPolicy
import com.olive.app.data.AccountRole
import com.olive.app.security.InputPolicy
import com.olive.app.data.UserProfile
import com.olive.app.ui.shared.ResourceText
import com.olive.app.ui.shared.localizedTitle
import com.olive.app.ui.users.shared.navigation.CredentialFieldError
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SessionAccountSignIn(private val runtime: SessionRuntime) {
    // Session account sign in: Authenticates the account.
    fun authenticate(username: String, password: String, create: Boolean) {
        if (!canStart()) return
        if (!credentialsAreValid(username, password, create)) return

        val failureMessage = runtime.application.getString(
            R.string.unable_to_sign_in_check_your_credentials_and_connection_then_try_again
        )
        runtime.operations.runOperation(failureMessage) { sessionVersion ->
            val identity = try {
                if (create) {
                    runtime.accounts.create(username, password, runtime.state.accountRole)
                } else {
                    runtime.accounts.authenticate(username, password, runtime.state.accountRole)
                }
            } catch (failure: Exception) {
                if (!showCredentialFailure(failure, create, sessionVersion)) throw failure
                return@runOperation
            }

            val profile = loadProfile(identity)
            if (runtime.epoch != sessionVersion) return@runOperation
            if (!validateAccountRole(profile, create)) return@runOperation

            runtime.pendingIdentity = identity
            runtime.pendingProfile = profile
            openNextScreen(identity, profile)
        }
    }

    // Stops a second sign-in request while the first request is still running.
    private fun canStart(): Boolean {
        val state = runtime.state
        return !state.busy && runtime.enrollment == null &&
            state.screen in setOf(SessionScreen.NewCredentials, SessionScreen.ReturningCredentials)
    }

    // Check the form before sending credentials to the account repository.
    private fun credentialsAreValid(username: String, password: String, create: Boolean): Boolean {
        val state = runtime.state
        state.credentialFieldError = null
        val organizationEmail = state.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) && '@' in username
        val validUsername = if (create) {
            InputPolicy.validUsername(username)
        } else {
            InputPolicy.validExistingUsername(username)
        }
        if (!organizationEmail && !validUsername) {
            state.credentialFieldError = CredentialFieldError.InvalidUsername
            state.message = runtime.application.getString(R.string.ui_invalid_username_10e538)
            return false
        }
        if (create && !InputPolicy.passwordExcludesUsername(password, username)) {
            state.message = runtime.application.getString(R.string.password_excludes_username)
            return false
        }
        if (create && !InputPolicy.validAccountPassword(password, username)) {
            state.credentialFieldError = CredentialFieldError.InvalidPassword
            state.message = runtime.application.getString(R.string.ui_invalid_password_a2df22)
            return false
        }
        if (password.isEmpty()) {
            state.credentialFieldError = CredentialFieldError.IncorrectPassword
            state.message = runtime.application.getString(R.string.ui_password_required_ec4e2c)
            return false
        }
        return true
    }

    // Keep the old sign-in error messages while letting unexpected failures reach the operation handler.
    private fun showCredentialFailure(failure: Exception, create: Boolean, sessionVersion: Long): Boolean {
        val details = failure.message.orEmpty()
        val usernameUnavailable = create && (
            failure is FirebaseAuthUserCollisionException ||
                (failure is FirebaseFunctionsException &&
                    failure.code == FirebaseFunctionsException.Code.ALREADY_EXISTS) ||
                details.contains("unavailable", ignoreCase = true)
            )
        val incorrectLogin = !create && (
            failure is FirebaseAuthInvalidCredentialsException ||
                failure is FirebaseAuthInvalidUserException ||
                details.contains("sign-in failed", ignoreCase = true)
            )
        if (!usernameUnavailable && !incorrectLogin && !(create && failure is FirebaseFunctionsException)) {
            return false
        }
        // Ignore a sign-in result after the session has been cancelled or replaced.
        if (runtime.epoch == sessionVersion) {
            runtime.state.credentialFieldError = if (usernameUnavailable) {
                CredentialFieldError.UsernameTaken
            } else {
                CredentialFieldError.IncorrectPassword
            }
            runtime.state.message = when {
                usernameUnavailable -> runtime.application.getString(R.string.ui_username_taken_c04650)
                incorrectLogin -> runtime.application.getString(R.string.ui_incorrect_password_b68db3)
                else -> failure.message?.takeIf { it.isNotBlank() }
                    ?: runtime.application.getString(
                        R.string.unable_to_sign_in_check_your_credentials_and_connection_then_try_again
                    )
            }
        }
        return true
    }

    // Loads the signed-in profile before choosing the next screen.
    private suspend fun loadProfile(identity: AccountIdentity): UserProfile? {
        val remote = runtime.accounts.profile(identity)
        return withContext(Dispatchers.IO) {
            if (runtime.store.pendingSync(identity.uid)) runtime.store.profile(identity.uid) else remote
        }
    }

    // Reject organization accounts that were never provisioned and accounts opened under the wrong role.
    private suspend fun validateAccountRole(profile: UserProfile?, create: Boolean): Boolean {
        val chosenRole = runtime.state.accountRole
        if (profile == null && !create && chosenRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            runtime.accounts.signOut()
            runtime.state.message = runtime.application.getString(
                R.string.ui_this_value_account_has_not_been_provisioned_by_an_administrator,
                chosenRole.localizedTitle(ResourceText(runtime.application.resources)),
            )
            return false
        }
        if (profile != null && profile.role != chosenRole) {
            runtime.accounts.signOut()
            runtime.state.message = runtime.application.getString(
                R.string.ui_this_account_is_registered_as_value_select_that_account_type_to_s,
                profile.role.localizedTitle(ResourceText(runtime.application.resources)),
            )
            return false
        }
        return true
    }

    // Opens setup, the cover, or the role portal after sign-in succeeds.
    private suspend fun openNextScreen(identity: AccountIdentity, profile: UserProfile?) {
        if (profile != null && profile.role == AccountRole.SAFE_CONTACT &&
            AccountPolicy.personalProfileNeedsSetup(profile)
        ) {
            runtime.navigation.openScreen(SessionScreen.ProfileSetup)
            return
        }
        if (profile != null && profile.role in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
            AccountPolicy.organizationProfileNeedsSetup(profile)
        ) {
            runtime.navigation.openScreen(SessionScreen.OrganizationProfileSetup)
            return
        }
        if (profile != null && profile.role != AccountRole.USER) {
            openReturningNonUser(identity, profile)
            return
        }
        val nextScreen = when {
            profile == null || AccountPolicy.personalProfileNeedsSetup(profile) -> SessionScreen.ProfileSetup
            profile.triggerHash.isBlank() -> SessionScreen.TriggerSetup
            else -> SessionScreen.DevicePin
        }
        runtime.navigation.openScreen(nextScreen)
    }

    // Safe contacts enter the safe app; staff and admins enter their separate portal.
    private suspend fun openReturningNonUser(identity: AccountIdentity, profile: UserProfile) {
        val enrolled = withContext(Dispatchers.IO) { runtime.store.enrollWithoutPin(identity, profile) }
        runtime.enrollment = enrolled
        val state = runtime.state
        state.archivedConversationIds = withContext(Dispatchers.IO) {
            runtime.store.archivedConversationIds(enrolled.uid)
        }
        state.privateContactNotes = withContext(Dispatchers.IO) {
            runtime.store.privateContactNotes(enrolled.uid)
        }
        state.profile = profile
        state.coverUsername = profile.username
        state.coverDob = profile.dob
        state.coverFullName = profile.fullName
        state.accountRole = profile.role
        state.language = profile.language
        state.deliveryAddress = profile.address
        runtime.screenHistory.clear()
        runtime.safePageHistory.clear()
        // Safe contacts open their own dashboard instead of a discreet cover.
        if (profile.role == AccountRole.SAFE_CONTACT) {
            state.page = SafePage.Home
            state.screen = SessionScreen.Safe
            runtime.history.refreshHistory(enrolled.uid)
        } else {
            state.screen = SessionScreen.Portal
            if (profile.role == AccountRole.ADMIN) runtime.organization.queueAdminRefresh()
        }
    }
}
