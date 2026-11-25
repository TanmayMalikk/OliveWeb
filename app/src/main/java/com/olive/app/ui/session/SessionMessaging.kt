// Signed-in users: loads and sends chat messages.
package com.olive.app.ui.session

import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import androidx.core.content.edit
import com.olive.app.security.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.network.hasValidatedInternetConnection
import com.olive.app.data.network.persistentInstallationId
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountPolicy
import com.olive.app.data.account.AccountRole
import com.olive.app.security.InputPolicy
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.MessageSendWorker
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.ui.shared.ResourceText
import com.olive.app.ui.shared.localizedTitle
import com.olive.app.ui.users.shared.navigation.CredentialFieldError
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen


// Connects account messaging and manages live subscriptions.
internal class SessionMessaging(private val runtime: SessionRuntime) {
    private var identityCheck: Job? = null
    private var listenerGeneration = 0L
    private var rowsOwner: String? = null
    private var preparedSupportUid: String? = null
    private var serverConversationIndexLoaded = false
    var loadingIndex by mutableStateOf(false)
        private set
    private var waitingForSocial = false
    private var waitingForConversations = false
    private var indexLoadTimeout: Job? = null

    /**
     * Updates index loading.
     */
    private fun updateIndexLoading() {
        loadingIndex = waitingForSocial || waitingForConversations
    }
    private var cacheIndexUpdate: Job? = null

    /**
     * Checks whether the current screen can keep messaging listeners active.
     *
     * @return True when the Safe App is open or a staff or admin account is in its portal.
     */
    private fun messagingScreenActive() = with(runtime.state) {
        screen == SessionScreen.Safe ||
                (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN))
    }
    /**
     * Starts the account's friend and conversation listeners once the current screen may show messages.
     *
     * @param showFeedback Whether to show feedback.
     */
    fun ensureMessagingObservation(showFeedback: Boolean = false) {
        with(runtime) {
            with(state) {
                if (socialSubscription != null || identityCheck?.isActive == true || messagingBusy) return
                startMessaging(showFeedback)
            }
        }
    }

    /**
     * Starts friend and chat listeners for the signed-in account.
     *
     * @param showFeedback Whether to show feedback.
     */
    fun startMessaging(showFeedback: Boolean = true) {
        with(runtime) {
            with(state) {
                val repository = messagingRepository
                val device = enrollment
                stopMessaging(clearRows = device == null || device.uid != rowsOwner)
                if (repository == null || device == null) {
                    if (showFeedback) message =
                        runtime.application.getString(R.string.messaging_needs_a_firebase_connected_build)
                    return
                }
                rowsOwner = device.uid
                val indexGeneration = listenerGeneration
                // A saved local account can outlive Firebase's login; reconnect it before touching private friend data.
                val expectedUsername = InputPolicy.username(coverUsername)
                if (accounts.authenticatedUid() != device.uid || accounts.authenticatedUsername() != expectedUsername) {
                    messagingNeedsReconnect = true
                    messagingBusy = false
                    if (showFeedback) message =
                        runtime.application.getString(R.string.ui_enter_your_account_password_to_reconnect_friends_and_messages)
                    return
                }
                val online = hasValidatedInternetConnection(application)
                waitingForSocial = online
                waitingForConversations = online
                updateIndexLoading()
                val token = epoch
                scope.launch {
                    val cached = withContext(Dispatchers.IO) { chatCache.conversations(device.uid) }
                    if (indexGeneration == listenerGeneration && enrollment?.uid == device.uid &&
                        !serverConversationIndexLoaded && conversations.isEmpty()) {
                        conversations = cached
                    }
                }
                /**
                 * Checks whether current listener.
                 *
                 * @return `true` if is current listener, or `false` otherwise.
                 */
                fun isCurrentListener() = indexGeneration == listenerGeneration && epoch == token &&
                    enrollment?.uid == device.uid && accounts.authenticatedUid() == device.uid
                identityCheck = scope.launch {
                    val role = try {
                        accounts.authenticatedRole()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null // An offline device can still use its own cached chats.
                    }
                    if (!isCurrentListener()) return@launch
                    if (device.role != accountRole || (role != null && role != device.role)) {
                        stopMessaging()
                        messagingNeedsReconnect = true
                        message = application.getString(
                            R.string.ui_this_account_is_registered_as_value_select_that_account_type_to_s,
                            (role ?: device.role).localizedTitle(ResourceText(application.resources)),
                        )
                        return@launch
                    }
                    val identity = AccountIdentity(device.uid, coverUsername)
                    // Organization accounts stay in the portal, so keep their notification listener
                    // active while the message list listener drives unread badges on the dashboard.
                    LocalNotificationEventHandler.start(application)
                    indexLoadTimeout = scope.launch {
                        delay(10_000.milliseconds)
                        if (isCurrentListener() && loadingIndex) {
                            waitingForConversations = false
                            updateIndexLoading()
                        }
                    }
                    if (showFeedback) message = null
                    observeConversations(device.uid, ::isCurrentListener)
                    socialSubscription = repository.observeSocial(device.uid, { social ->
                        if (isCurrentListener() && messagingScreenActive()) {
                            friends = social.friends
                            incomingFriendRequests = social.incoming.filter { it.recipientUid == device.uid }
                            outgoingFriendRequests = social.outgoing.filter { it.senderUid == device.uid }
                            waitingForSocial = false
                            updateIndexLoading()
                        }
                    }, { error ->
                        if (isCurrentListener()) {
                            waitingForSocial = false
                            updateIndexLoading()
                        }
                        if (isCurrentListener() && messagingScreenActive() && (showFeedback || page == SafePage.Messages)) {
                            message = application.getString(R.string.ui_unable_to_load_friend_information_value, error)
                        }
                    }, {
                        if (isCurrentListener()) {
                            waitingForSocial = false
                            updateIndexLoading()
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
                            if (isCurrentListener()) preparedSupportUid = device.uid
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            android.util.Log.w("OliveMessaging", "Ticket maintenance failed", error)
                        }
                    }
                }
            }
        }
    }

    /**
     * Rebuilds messaging listeners after the account or network connection changes.
     *
     * @param password Account password string.
     * @param onConnected Callback invoked when connected changes or occurs.
     */
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

    /**
     * Watches all conversations, including tickets for accounts without friends.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param isCurrent Function used to transform or provide is current.
     */
    private fun observeConversations(ownerUid: String, isCurrent: () -> Boolean) {
        with(runtime) {
            with(state) {
                conversationSubscription?.close()
                conversationSubscription =
                    messagingRepository?.observeConversations(ownerUid, { rows ->
                        // Update the list only while the same messaging screen is still open.
                        if (isCurrent() && messagingScreenActive()) {
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
                        if (isCurrent()) {
                            waitingForConversations = false
                            updateIndexLoading()
                            conversationLoadFailed = true
                            message = runtime.application.getString(
                                R.string.ui_unable_to_load_messages_value,
                                error
                            )
                        }
                    }, { serverRows ->
                        if (!isCurrent()) return@observeConversations
                        serverConversationIndexLoaded = true
                        waitingForConversations = false
                        updateIndexLoading()
                        val visibleIds = serverRows.mapTo(mutableSetOf(), ChatConversation::id)
                        activeConversation?.takeIf { it.id !in visibleIds }?.let {
                            conversationActions.closeConversation()
                        }
                        archivedConversationIds = archivedConversationIds.intersect(visibleIds)
                        restoredClosedConversationIds = restoredClosedConversationIds.intersect(visibleIds)
                        val previousUpdate = cacheIndexUpdate
                        cacheIndexUpdate = scope.launch {
                            previousUpdate?.join()
                            withContext(Dispatchers.IO) {
                                chatCache.replaceIndex(ownerUid, serverRows, System.currentTimeMillis())
                                store.retainConversationPreferences(ownerUid, visibleIds)
                            }
                        }
                    })
            }
        }
    }

    /**
     * Runs one messaging change and shows a readable Firebase error if it fails.
     *
     * @param failure Exception or failure reason encountered during execution.
     * @param block Callback or suspending lambda executed within this scope.
     */
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
                if (messagingBusy || messagingNeedsReconnect || identityCheck?.isActive == true ||
                    !messagingScreenActive()) return
                if (accounts.authenticatedUid() != device.uid) {
                    stopMessaging()
                    messagingNeedsReconnect = true
                    message = application.getString(R.string.ui_enter_your_account_password_to_reconnect_friends_and_messages)
                    return
                }
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
                        if (epoch == token && enrollment?.uid == device.uid) {
                            message =
                                if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                    "$failure Firebase denied this action. Refresh the page and try again."
                                else error.localizedMessage ?: failure
                        }
                    } finally {
                        if (epoch == token && enrollment?.uid == device.uid) messagingBusy = false
                    }
                }
            }
        }
    }

    /**
     * Removes friend and conversation listeners when messaging is no longer needed.
     *
     * @param clearRows Whether to apply clear rows.
     */
    fun stopMessaging(clearRows: Boolean = true) {
        listenerGeneration++
        if (clearRows) rowsOwner = null
        identityCheck?.cancel()
        identityCheck = null
        indexLoadTimeout?.cancel()
        indexLoadTimeout = null
        serverConversationIndexLoaded = false
        waitingForSocial = false
        waitingForConversations = false
        updateIndexLoading()
        runtime.conversationActions.stopKeyObservation()
        with(runtime) {
            with(state) {
                socialSubscription?.close()
                socialSubscription = null
                conversationSubscription?.close()
                conversationSubscription = null
                messageSubscription?.close()
                messageSubscription = null
                activeRoomKey = null
                clearMessaging(clearRows)
            }
        }
    }

    /**
     * Registers messaging identity for session messaging.
     *
     * @param identity Authenticated account identity containing UID and username.
     */
    suspend fun registerMessagingIdentity(identity: AccountIdentity) {
        with(runtime) {
            check(accounts.authenticatedUid() == identity.uid)
            val publicKey = withContext(Dispatchers.Default) { KeyManager.getOrCreatePublicKey() }
            check(accounts.authenticatedUid() == identity.uid)
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

    /**
     * Reconnects device notifications to the conversations visible to the current account.
     */
    fun syncLocalNotificationHandler() {
        with(runtime) {
            val device = enrollment ?: return
            if (localMode) return
            if (accounts.authenticatedUid() == device.uid)
                LocalNotificationEventHandler.start(application)
        }
    }
}

// All account roles: checks credentials, loads the account, and opens the next screen.

internal class SessionAccountSignIn(private val runtime: SessionRuntime) {
    /**
     * Session account sign in: Authenticates the account.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param create Whether to apply create.
     */
    fun authenticate(username: String, password: String, create: Boolean) {
        if (!canStart()) return
        if (!credentialsAreValid(username, password, create)) return

        val failureMessage = runtime.application.getString(
            R.string.unable_to_sign_in_check_your_credentials_and_connection_then_try_again
        )
        val needsDeviceConfirmation = runtime.opaqueRecoveryRepository != null && runtime.state.accountRole == AccountRole.USER
        val timeoutMessage = runtime.application.getString(
            if (create) R.string.the_operation_timed_out_check_your_connection_and_retry_your_account_may_already_exist
            else R.string.the_operation_timed_out_check_your_connection_and_retry,
        )
        runtime.operations.runOperation(
            failureMessage,
            timeoutMillis = if (needsDeviceConfirmation) 120_000 else 25_000,
            timeoutMessage = timeoutMessage,
        ) { sessionVersion ->
            val creatingIdentity = create && runtime.pendingIdentity?.username != InputPolicy.username(username)
            val identity = try {
                if (creatingIdentity) {
                    runtime.accounts.create(username, password, runtime.state.accountRole)
                } else {
                    runtime.accounts.authenticate(username, password, runtime.state.accountRole)
                }
            } catch (failure: Exception) {
                if (!showCredentialFailure(failure, creatingIdentity, sessionVersion)) throw failure
                return@runOperation
            }
            if (runtime.epoch != sessionVersion) return@runOperation
            if (creatingIdentity) runtime.pendingIdentity = identity

            if (!runtime.localMode) {
                val signInDeviceId = persistentInstallationId(runtime.application, "olive_device_signin", "signin_device_id")
                runtime.scope.launch {
                    runCatching { runtime.accounts.recordDeviceSignIn(signInDeviceId, registerOnly = create) }
                }
            }

            val profile = loadProfile(identity)
            if (runtime.epoch != sessionVersion) return@runOperation
            if (!validateAccountRole(profile, create)) return@runOperation

            runtime.pendingIdentity = identity
            runtime.pendingProfile = profile
            if (needsDeviceConfirmation) {
                val setupRequired = create || profile == null || AccountPolicy.personalProfileNeedsSetup(profile)
                try {
                    runtime.passwordRecovery.enrollAfterAuthentication(identity.uid, password, sessionVersion, setupRequired)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (setupRequired) {
                        if (runtime.epoch == sessionVersion) {
                            runtime.state.failInput(runtime.passwordRecovery.passwordRecoveryFailure(failure))
                        }
                        return@runOperation
                    }
                    // Existing accounts can sign in even when a legacy key cannot be migrated on this device.
                }
                if (runtime.epoch != sessionVersion) return@runOperation
            }
            openNextScreen(identity, profile)
        }
    }

    /**
     * Stops a second sign-in request while the first request is still running.
     *
     * @return `true` if can start, or `false` otherwise.
     */
    private fun canStart(): Boolean {
        val state = runtime.state
        return !state.busy && runtime.enrollment == null &&
            state.screen in setOf(SessionScreen.NewCredentials, SessionScreen.ReturningCredentials)
    }

    /**
     * Check the form before sending credentials to the account repository.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param create Whether to apply create.
     * @return `true` if credentials are valid, or `false` otherwise.
     */
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
            state.failInput(runtime.application.getString(R.string.ui_invalid_username_10e538))
            return false
        }
        if (create && !InputPolicy.passwordExcludesUsername(password, username)) {
            state.failInput(runtime.application.getString(R.string.password_excludes_username))
            return false
        }
        if (create && !InputPolicy.validAccountPassword(password, username)) {
            state.credentialFieldError = CredentialFieldError.InvalidPassword
            state.failInput(runtime.application.getString(R.string.ui_invalid_password_a2df22))
            return false
        }
        if (password.isEmpty()) {
            state.credentialFieldError = CredentialFieldError.IncorrectPassword
            state.failInput(runtime.application.getString(R.string.ui_password_required_ec4e2c))
            return false
        }
        return true
    }

    /**
     * Keep the old sign-in error messages while letting unexpected failures reach the operation handler.
     *
     * @param failure Exception or failure reason encountered during execution.
     * @param create Whether to apply create.
     * @param sessionVersion Session version supplied to [showCredentialFailure].
     * @return `true` if show credential failure, or `false` otherwise.
     */
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
            runtime.state.failInput(
                when {
                    usernameUnavailable -> runtime.application.getString(R.string.ui_username_taken_c04650)
                    incorrectLogin -> runtime.application.getString(R.string.ui_incorrect_password_b68db3)
                    else -> failure.message?.takeIf { it.isNotBlank() }
                        ?: runtime.application.getString(
                            R.string.unable_to_sign_in_check_your_credentials_and_connection_then_try_again
                        )
                }
            )
        }
        return true
    }

    /**
     * Loads the signed-in profile before choosing the next screen.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return The resulting [UserProfile], or `null` if unavailable.
     */
    private suspend fun loadProfile(identity: AccountIdentity): UserProfile? {
        val remote = runtime.accounts.profile(identity)
        return withContext(Dispatchers.IO) {
            if (runtime.store.pendingSync(identity.uid)) runtime.store.profile(identity.uid) else remote
        }
    }

    /**
     * Reject organization accounts that were never provisioned and accounts opened under the wrong role.
     *
     * @param profile Account profile details, or null when not provided.
     * @param create Whether to apply create.
     * @return `true` if validate account role, or `false` otherwise.
     */
    private fun validateAccountRole(profile: UserProfile?, create: Boolean): Boolean {
        val chosenRole = runtime.state.accountRole
        if (profile == null && !create && chosenRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            runtime.accounts.signOut()
            runtime.state.failInput(
                runtime.application.getString(
                    R.string.ui_this_value_account_has_not_been_provisioned_by_an_administrator,
                    chosenRole.localizedTitle(ResourceText(runtime.application.resources)),
                )
            )
            return false
        }
        if (profile != null && profile.role != chosenRole) {
            runtime.accounts.signOut()
            runtime.state.failInput(
                runtime.application.getString(
                    R.string.ui_this_account_is_registered_as_value_select_that_account_type_to_s,
                    profile.role.localizedTitle(ResourceText(runtime.application.resources)),
                )
            )
            return false
        }
        return true
    }

    /**
     * Opens setup, the cover, or the role portal after sign-in succeeds.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details, or null when not provided.
     */
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

    /**
     * Safe contacts enter the safe app; staff and admins enter their separate portal.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     */
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
