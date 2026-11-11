// Signed-in users: loads and sends chat messages.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import androidx.core.content.edit
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.functions.FirebaseFunctionsException
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


// Connects account messaging and manages live subscriptions.
internal class SessionMessaging(private val runtime: SessionRuntime) {
    private var preparedSupportUid: String? = null

    // Session messaging: Reports whether messaging screen active.
    private fun messagingScreenActive() = with(runtime.state) {
        screen == SessionScreen.Safe ||
                (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN))
    }
    // Starts the account's friend and conversation listeners once the current screen may show messages.
    fun ensureMessagingObservation(showFeedback: Boolean = false) {
        with(runtime) {
            with(state) {
                if (socialSubscription != null || messagingBusy) return
                startMessaging(showFeedback)
            }
        }
    }

    // Starts friend and chat listeners for the signed-in account.
    fun startMessaging(showFeedback: Boolean = true) {
        with(runtime) {
            with(state) {
                stopMessaging(clearRows = false)
                val repository = messagingRepository
                val device = enrollment
                if (repository == null || device == null) {
                    if (showFeedback) message =
                        runtime.application.getString(R.string.messaging_needs_a_firebase_connected_build)
                    return
                }
                val cacheToken = epoch
                scope.launch {
                    val cached = withContext(Dispatchers.IO) { chatCache.conversations(device.uid) }
                    if (epoch == cacheToken && enrollment?.uid == device.uid && conversations.isEmpty()) {
                        conversations = cached
                    }
                }
                // A saved local account can outlive Firebase's login; reconnect it before touching private friend data.
                val expectedUsername = InputPolicy.username(coverUsername)
                if (accounts.authenticatedUid() != device.uid || accounts.authenticatedUsername() != expectedUsername) {
                    messagingNeedsReconnect = true
                    messagingBusy = false
                    if (showFeedback) message =
                        runtime.application.getString(R.string.ui_enter_your_account_password_to_reconnect_friends_and_messages)
                    return
                }
                messagingNeedsReconnect = false
                val identity = AccountIdentity(device.uid, coverUsername)
                // Organization accounts stay in the portal, so keep their notification listener
                // active while the message list listener drives unread badges on the dashboard.
                LocalNotificationEventHandler.start(application)
                val token = epoch
                if (showFeedback) message = null
                observeConversations(token)
                socialSubscription = repository.observeSocial(device.uid, { social ->
                    if (epoch == token && messagingScreenActive()) {
                        friends = social.friends
                        incomingFriendRequests = social.incoming
                        outgoingFriendRequests = social.outgoing
                    }
                }, { error ->
                    if (epoch == token && messagingScreenActive() && (showFeedback || page == SafePage.Messages)) {
                        message = application.getString(R.string.ui_unable_to_load_friend_information_value, error)
                    }
                })
                scope.launch {
                    try {
                        registerMessagingIdentity(identity)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("OliveMessaging", "Messaging key registration failed", error)
                    }
                }
                if (preparedSupportUid != device.uid) scope.launch {
                    try {
                        repository.prepareSupportTickets()
                        if (epoch == token) preparedSupportUid = device.uid
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("OliveMessaging", "Ticket maintenance failed", error)
                    }
                }
            }
        }
    }

    // Rebuilds messaging listeners after the account or network connection changes.
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                if (localMode || messagingBusy || password.isBlank() || !messagingScreenActive()) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    var connected = false
                    // Turn an unexpected reconnect messaging failure into a readable message.
                    try {
                        val identity = accounts.authenticate(coverUsername, password, accountRole)
                        check(identity.uid == device.uid) { "This device is linked to an older account. Sign out, then sign in again." }
                        connected = true
                        messagingNeedsReconnect = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Keep the social screen open and offer account reconnection when messaging setup fails.
                        if (epoch == token) {
                            messagingNeedsReconnect = true
                            message =
                                runtime.application.getString(R.string.ui_could_not_reconnect_check_your_password_and_try_again)
                        }
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                    if (connected && epoch == token && messagingScreenActive()) {
                        onConnected()
                        startMessaging(showFeedback = true)
                    }
                }
            }
        }
    }

    // Watches all conversations, including tickets for accounts without friends.
    private fun observeConversations(token: Long) {
        with(runtime) {
            with(state) {
                val ownerUid = enrollment?.uid ?: return
                conversationSubscription?.close()
                conversationSubscription =
                    messagingRepository?.observeConversations(ownerUid, { rows ->
                        // Update the list only while the same messaging screen is still open.
                        if (epoch == token && messagingScreenActive()) {
                            if (conversationLoadFailed) message = null
                            conversationLoadFailed = false
                            conversations = rows
                            activeConversation?.let { active ->
                                rows.firstOrNull { it.id == active.id }?.let { updated ->
                                    if (active.closedAt == 0L && updated.closedAt > 0L) {
                                        conversationActions.closeConversation()
                                    } else if (active.participantIds != updated.participantIds) {
                                        messageSubscription?.close()
                                        messageSubscription = null
                                        activeConversation = updated
                                        conversationActions.openConversation(updated, preserveMessages = true)
                                    } else {
                                        activeConversation = updated
                                        conversationActions.markConversationRead(updated)
                                        if (messageSubscription == null && !messagingBusy &&
                                            active.updatedAt != updated.updatedAt) {
                                            conversationActions.openConversation(updated)
                                        }
                                    }
                                }
                            }
                        }
                    }, { error ->
                        if (epoch == token && page == SafePage.Messages) {
                            conversationLoadFailed = true
                            message = runtime.application.getString(
                                R.string.ui_unable_to_load_messages_value,
                                error
                            )
                        }
                    }, { serverRows ->
                        if (epoch == token) {
                            activeConversation?.takeIf { active ->
                                serverRows.none { it.id == active.id }
                            }?.let { conversationActions.closeConversation() }
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                chatCache.replaceIndex(ownerUid, serverRows, System.currentTimeMillis())
                            }
                        }
                    })
            }
        }
    }

    // Runs one messaging change and shows a readable Firebase error if it fails.
    fun runMessagingAction(
        failure: String,
        block: suspend (MessagingRepository, DeviceEnrollment, Long) -> Unit
    ) {
        with(runtime) {
            with(state) {
                val repository = messagingRepository ?: run {
                    message =
                        runtime.application.getString(R.string.messaging_needs_a_firebase_connected_build)
                    return
                }
                val device = enrollment ?: return
                if (messagingBusy || messagingNeedsReconnect || !messagingScreenActive()) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Use the fallback when run messaging action is unavailable.
                    try {
                        block(repository, device, token)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Show the result only if the same account is still signed in.
                        if (epoch == token) {
                            message =
                                if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                    "$failure Firebase denied this action. Refresh the page and try again."
                                else error.localizedMessage ?: failure
                        }
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Removes friend and conversation listeners when messaging is no longer needed.
    fun stopMessaging(clearRows: Boolean = true) {
        runtime.conversationActions.stopKeyObservation()
        with(runtime) {
            with(state) {
                socialSubscription?.close()
                socialSubscription = null
                conversationSubscription?.close()
                conversationSubscription = null
                conversationLoadFailed = false
                messageSubscription?.close()
                messageSubscription = null
                activeConversation = null
                activeRoomKey = null
                chatMessages = emptyList()
                messagingBusy = false
                messagingNeedsReconnect = false
                if (clearRows) {
                    userSearchResults = emptyList()
                    friends = emptyList()
                    incomingFriendRequests =
                        emptyList()
                    outgoingFriendRequests = emptyList()
                    conversations = emptyList()
                }
            }
        }
    }

    // Returns this device's messaging ID for encrypted-chat access.
    private fun deviceUid() = runtime.enrollment?.uid ?: error("No active account")

    // Registers messaging identity for session messaging.
    suspend fun registerMessagingIdentity(identity: AccountIdentity) {
        with(runtime) {
            val publicKey = withContext(Dispatchers.Default) { KeyManager.getOrCreatePublicKey() }
            val marker = messagingIdentityMarker(identity, publicKey.encoded)
            val savedMarker = cloudState.getString("registered_identity", null)
            if (verifiedMessagingIdentityMarker == marker &&
                !shouldRegisterMessagingIdentity(savedMarker, identity, publicKey.encoded)
            ) return
            messagingRepository?.registerUser(identity, publicKey)
            cloudState.edit { putString("registered_identity", marker) }
            verifiedMessagingIdentityMarker = marker
        }
    }

    // Reconnects device notifications to the conversations visible to the current account.
    fun syncLocalNotificationHandler() {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                if (localMode) return
                if (accounts.authenticatedUid() == device.uid)
                    LocalNotificationEventHandler.start(application)
            }
        }
    }
}

// All account roles: checks credentials, loads the account, and opens the next screen.

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
        state.restoredClosedConversationIds = withContext(Dispatchers.IO) {
            runtime.store.restoredClosedConversationIds(enrolled.uid)
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
        if (!runtime.localMode) {
            MessageSendWorker.enqueuePending(runtime.application, enrolled.uid)
            runtime.accountSession.watchAccountChanges(identity)
        }
    }
}
