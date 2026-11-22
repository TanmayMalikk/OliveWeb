// Signed-in users: moves between cover, login, and safe-app screens.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.*
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRelationship
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.SupportRepository
import com.olive.app.data.news.NewsArticle
import com.olive.app.security.EncryptedVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration.Companion.seconds


// Owns navigation history and background locking; quick exit clears PIN authorization and invalidates pending operations.
internal class SessionNavigation(private val runtime: SessionRuntime) {
    /**
     * Opens a Safe App screen and remembers where Back should return.
     *
     * @param value Input value to store, parse, or format.
     */
    fun openScreen(value: SessionScreen) {
        with(runtime) {
            with(state) {
                if (value == screen) return
                screenHistory += screen
                screen = value
            }
        }
    }

    /**
     * User exits also advance the cover-focus event so TalkBack leaves the old private screen.
     */
    fun quickExit() {
        with(runtime) {
            with(state) {
                val enrolledRole = enrollment?.role
                accountRole = enrolledRole ?: AccountRole.USER
                if (accountRole == AccountRole.USER && screen != SessionScreen.Cover) {
                    accessibilityExitVersion++
                }
                safety.close()
                adminPreloader.clear()
                epoch++
                pinAuthorizedUid = null
                pinAuthorizedEpoch = -1L
                deviceLockRequest?.complete(null)
                deviceLockRequest = null
                passkeyCredentialRequest?.complete(null)
                passkeyCredentialRequest = null
                operationJob?.cancel()
                operationJob = null
                idleJob?.cancel()
                idleJob = null
                idlePausedForCall = false
                idleCountdownSeconds = null
                busy = false
                coverReset++
                viewedSharedEntry = null
                if (enrolledRole == null) coverType = CoverType.FITNESS
                else profile?.coverType?.let { coverType = it }
                screen = when (accountRole) {
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF, AccountRole.ADMIN -> SessionScreen.Portal
                }
                page = SafePage.Home
                activityCategory = null
                screenHistory.clear()
                safePageHistory.clear()
                if (accountRole == AccountRole.USER) profile = null
                attempts = emptyList()
                message = null
                pendingSync = false
                settingsHistory = emptyList()
                privacyHistory = emptyList()
                triggerHistory =
                    emptyList()
                privateEntries = emptyList()
                messaging.stopMessaging()
                if (!localMode) messaging.syncLocalNotificationHandler()
                activeTriggerId = null
                triggerJob = null
                pendingIdentity = null
                pendingProfile = null
                credentialFieldError = null
                EncryptedVault.purgeAll()
            }
        }
    }

    /**
     * Moves the app into its background state for session navigation.
     */
    fun background() {
        with(runtime) {
            with(state) {
                // Also discard partially entered onboarding fields when leaving the app.
                if (screen == SessionScreen.Cover || screen == SessionScreen.Loading || screen == SessionScreen.StorageError) {
                    EncryptedVault.purgeAll()
                    return
                }
                // Without a saved enrollment, private entry must return to account setup.
                if (enrollment == null) {
                    epoch++
                    operationJob?.cancel()
                    operationJob = null
                    busy =
                        false
                    pendingIdentity = null
                    pendingProfile = null
                    accountRole = AccountRole.USER
                    coverType = CoverType.FITNESS
                    screenHistory.clear()
                    screen = SessionScreen.Cover
                    message = null
                    EncryptedVault.purgeAll()
                } else if (accountRole == AccountRole.USER) quickExit()
            }
        }
    }

    /**
     * Returns to the previous screen for session navigation.
     */
    fun back() {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe && page == SafePage.Messages && activeConversation != null) conversationActions.closeConversation()
                else if (screen == SessionScreen.Safe && page == SafePage.Activity && activityCategory != null) {
                    activityCategory = null
                    message = null
                } else if (screen == SessionScreen.Safe && safePageHistory.isNotEmpty()) {
                    page = safePageHistory.removeAt(safePageHistory.lastIndex)
                    message = null
                } else if (screen == SessionScreen.Safe && page != SafePage.Home) {
                    page = SafePage.Home
                    message = null
                } else if ((screen == SessionScreen.Safe && accountRole == AccountRole.USER) || screen == SessionScreen.Pin) quickExit()
                else if (screenHistory.isNotEmpty()) {
                    val previousScreen = screenHistory.removeAt(screenHistory.lastIndex)
                    if (enrollment == null && previousScreen == SessionScreen.Cover) {
                        accountRole = AccountRole.USER
                        coverType = CoverType.FITNESS
                    }
                    screen = previousScreen
                    message = null
                }
                // Root cover/welcome/loading screens deliberately consume Back so Android does not open the phone home screen.
            }
        }
    }

    /**
     * Session navigation: Opens the requested screen.
     *
     * @param value Input value to store, parse, or format.
     */
    fun navigate(value: SafePage) {
        with(runtime) {
            with(state) {
                // Safe App pages use their own back stack before leaving the private area.
                if (screen == SessionScreen.Safe) {
                    if (value != page) safePageHistory += page
                    page = value
                    message = null
                    if (value == SafePage.Activity) activityCategory = null
                    val uid = enrollment?.uid
                    if (uid != null) {
                        if (value == SafePage.Settings || value == SafePage.Profile || value == SafePage.Contacts)
                            history.recordSettingsInteraction(
                                "Olive Branch",
                                "Opened ${value.name.lowercase()} page"
                            )
                        if (value == SafePage.Activity || value == SafePage.Notes) history.refreshHistory(
                            uid
                        )
                        if (value == SafePage.Messages) messaging.ensureMessagingObservation(
                            showFeedback = true
                        )
                    }
                }
            }
        }
    }
}

// Signed-in users: stores the selected discreet cover.

// Owns account-scoped cover persistence.
internal class SessionCoverState(private val runtime: SessionRuntime) {
    /**
     * Loads the signed-in account's saved news state.
     *
     * @param uid Firebase UID of the target user account.
     */
    suspend fun restoreCoverData(uid: String) {
        with(runtime) {
            with(state) {
                val saved = withContext(Dispatchers.IO) { coverStore.restore(uid) }
                savedNews = saved
                coverDataVersion++
            }
        }
    }

    /**
     * Sets the bookmark state for one news article.
     *
     * @param article The article to bookmark or remove.
     * @param bookmarked The requested bookmark state.
     */
    fun setNewsBookmark(article: NewsArticle, bookmarked: Boolean) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                val previous = savedNews
                if (previous.any { it.link == article.link } == bookmarked) return
                savedNews = if (bookmarked) (listOf(article) + previous).take(100)
                    else previous.filterNot { it.link == article.link }
                val latest = savedNews
                val revision = (newsRevisions[uid] ?: 0L) + 1L
                newsRevisions[uid] = revision
                scope.launch {
                    coverSaveMutex.withLock {
                        if (newsRevisions[uid] != revision) return@withLock
                        try {
                            withContext(Dispatchers.IO) { coverStore.saveNews(uid, latest) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (enrollment?.uid == uid && newsRevisions[uid] == revision) savedNews = previous
                        }
                    }
                }
            }
        }
    }
}

// Users and staff: manages support tickets separately from safety-plan consultations.

internal data class StaffSupportUiState(
    val requested: Boolean = false,
    val staffUid: String = "",
    val conversationId: String = "",
    val saving: Int = 0,
    val error: Int? = null,
    val addParticipantError: String? = null,
    val requestStaffError: String? = null,
)

internal class StaffSupportController(
    private val runtime: SessionRuntime,
    repositoryFactory: () -> SupportRepository = { SupportRepository() },
) {
    private val repository by lazy(repositoryFactory)
    private var pendingRequest: Pair<String, String>? = null
    private val uiStateFlow = MutableStateFlow(StaffSupportUiState())
    val uiState: StateFlow<StaffSupportUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (StaffSupportUiState) -> StaffSupportUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    var requested: Boolean
        get() = uiStateSnapshot.requested
        private set(value) = updateUiState { it.copy(requested = value) }
    var staffUid: String
        get() = uiStateSnapshot.staffUid
        private set(value) = updateUiState { it.copy(staffUid = value) }
    var conversationId: String
        get() = uiStateSnapshot.conversationId
        private set(value) = updateUiState { it.copy(conversationId = value) }
    var saving: Int
        get() = uiStateSnapshot.saving
        private set(value) = updateUiState { it.copy(saving = value) }
    var error: Int?
        get() = uiStateSnapshot.error
        private set(value) = updateUiState { it.copy(error = value) }
    var addParticipantError: String?
        get() = uiStateSnapshot.addParticipantError
        private set(value) = updateUiState { it.copy(addParticipantError = value) }
    var requestStaffError: String?
        get() = uiStateSnapshot.requestStaffError
        private set(value) = updateUiState { it.copy(requestStaffError = value) }

    /**
     * Requests help for staff support controller.
     */
    fun requestHelp() = updateRequest(cancel = false)

    /**
     * Returns true while a staff-help request is waiting and may be cancelled.
     */
    fun cancelRequest() = updateRequest(cancel = true)

    /**
     * Keep the current account's ticket on screen; responses from a previous account are ignored.
     *
     * @param cancel Whether to apply cancel.
     */
    private fun updateRequest(cancel: Boolean) {
        if (saving > 0) return
        if (runtime.localMode) {
            error = R.string.safety_account_required
            return
        }
        if (runtime.state.accountRole != AccountRole.USER) return
        val actor = runtime.coverAccountId ?: return
        val requestEpoch = runtime.epoch
        val ticketToCancel = conversationId
        val requestKey = if (cancel) "" else {
            pendingRequest?.takeIf { it.first == actor }?.second ?: UUID.randomUUID().toString()
        }
        if (!cancel) {
            pendingRequest = actor to requestKey
            // Clear the last ticket before searching so this screen cannot reopen an older chat.
            requested = false
            staffUid = ""
            conversationId = ""
        }
        saving++
        error = null
        runtime.scope.launch {
            // Use the fallback when update request is unavailable.
            try {
                val ticket = withTimeout(35.seconds) {
                    withContext(Dispatchers.IO) {
                        if (cancel) repository.cancelRequest(ticketToCancel) else repository.requestHelp(requestKey)
                    }
                }
                if (!cancel && pendingRequest == (actor to requestKey)) pendingRequest = null
                if (runtime.coverAccountId == actor && runtime.epoch == requestEpoch) {
                    requested = ticket.requested
                    staffUid = ticket.staffUid
                    conversationId = ticket.conversationId
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (runtime.coverAccountId == actor) error = R.string.support_request_error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the existing ticket state and show that the support request failed.
                if (runtime.coverAccountId == actor) error = R.string.support_request_error
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Staff support controller: Requests admin.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    fun requestAdmin(conversationId: String) {
        if (runtime.localMode || runtime.state.accountRole != AccountRole.STAFF || conversationId.isBlank()) return
        saving++
        error = null
        runtime.scope.launch {
            // Use the fallback when request admin is unavailable.
            try {
                withContext(Dispatchers.IO) { repository.requestAdmin(conversationId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Do not change the ticket category unless the admin escalation succeeds.
                error = R.string.support_admin_request_error
            } finally {
                saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Adds an accepted personal contact to the open chat after the server checks both accounts.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [addParticipant].
     */
    fun addParticipant(conversationId: String, participantUid: String) {
        if (runtime.localMode || conversationId.isBlank() || participantUid.isBlank() || saving > 0) return
        val actor = runtime.coverAccountId ?: return
        saving++
        addParticipantError = null
        runtime.scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.addParticipant(conversationId, participantUid) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) { // Keep the members unchanged and explain the server's actual reason.
                if (runtime.coverAccountId == actor) {
                    addParticipantError = exception.message?.takeIf(String::isNotBlank)
                        ?: "This person could not be added."
                }
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Removes a member from a group after the server verifies group ownership and the two-person minimum.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [removeParticipant].
     */
    fun removeParticipant(conversationId: String, participantUid: String) {
        if (runtime.localMode || conversationId.isBlank() || participantUid.isBlank() || saving > 0) return
        val actor = runtime.coverAccountId ?: return
        saving++
        addParticipantError = null
        runtime.scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.removeParticipant(conversationId, participantUid) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) { // Keep the member visible when the server rejects the removal.
                if (runtime.coverAccountId == actor) {
                    addParticipantError = exception.message?.takeIf(String::isNotBlank)
                        ?: "This person could not be removed."
                }
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Creates a new support chat from an existing user and safe-contact conversation.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param onCreated Callback invoked when created changes or occurs.
     */
    fun requestStaffForConversation(conversationId: String, onCreated: (String) -> Unit) {
        if (runtime.localMode || conversationId.isBlank() || saving > 0 ||
            runtime.state.accountRole !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)
        ) return
        val actor = runtime.coverAccountId ?: return
        saving++
        requestStaffError = null
        runtime.scope.launch {
            try {
                val createdId = withContext(Dispatchers.IO) {
                    repository.requestStaffForConversation(conversationId)
                }
                if (runtime.coverAccountId == actor) onCreated(createdId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) { // Keep the personal chat open when staff assignment fails.
                if (runtime.coverAccountId == actor) {
                    requestStaffError = exception.message?.takeIf(String::isNotBlank)
                        ?: runtime.application.getString(R.string.support_request_error)
                }
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Removes old Chat Info errors before another chat is opened.
     */
    fun clearChatInfoErrors() {
        addParticipantError = null
        requestStaffError = null
    }

    /**
     * Staff support controller: Closes the active feature.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param planCompleted Whether to apply plan completed.
     * @param onClosed Callback invoked when closed changes or occurs.
     */
    fun close(conversationId: String, planCompleted: Boolean = false, onClosed: () -> Unit = {}) {
        if (runtime.localMode ||
            runtime.state.accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) ||
            conversationId.isBlank() || saving > 0
        ) return
        val actor = runtime.coverAccountId ?: return
        saving++
        error = null
        runtime.scope.launch {
            try {
                if (planCompleted) runtime.safety.flushConsultation(conversationId)
                withContext(Dispatchers.IO) { repository.close(conversationId, planCompleted) }
                if (runtime.coverAccountId == actor) {
                    val state = runtime.state
                    state.conversations = state.conversations.map { chat ->
                        if (chat.id == conversationId && chat.closedAt == 0L) chat.copy(closedAt = System.currentTimeMillis())
                        else chat
                    }
                    state.restoredClosedConversationIds -= conversationId
                    if (state.activeConversation?.id == conversationId) runtime.conversationActions.closeConversation()
                    onClosed()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("OliveMessaging", "Ticket close failed", failure)
                if (runtime.coverAccountId == actor) error = R.string.support_close_error
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }
}

// Signed-in users: sends and accepts friend requests.

// Searches the directory and manages friend requests.
internal class FriendActions(private val runtime: SessionRuntime) {
    /**
     * Finds users for the signed-in session.
     *
     * @param query Search query string entered by the user.
     */
    fun searchUsers(query: String) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to search for users.") { repository, device, token ->
                    val rows = repository.searchUsers(query, device.uid)
                    if (epoch == token) {
                        userSearchResults = rows
                        message =
                            if (rows.isEmpty()) runtime.application.getString(R.string.no_user_found_for_that_exact_username) else null
                    }
                }
            }
        }
    }

    /**
     * Loads the current friend relationship before changing it.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [FriendRelationship] for relationship for.
     */
    fun relationshipFor(uid: String): FriendRelationship {
        with(runtime) {
            with(state) {
                return when {
                    friends.any { it.uid == uid } -> FriendRelationship.Friends
                    incomingFriendRequests.any { it.senderUid == uid } -> FriendRelationship.IncomingRequest
                    outgoingFriendRequests.any { it.recipientUid == uid } -> FriendRelationship.RequestSent
                    else -> FriendRelationship.AddFriend
                }
            }
        }
    }

    /**
     * Sends a request to the selected directory account and refreshes the social list on success.
     *
     * @param user User supplied to [sendFriendRequest].
     */
    fun sendFriendRequest(user: DirectoryUser) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to send this friend request.") { repository, device, _ ->
                    val identity = AccountIdentity(device.uid, coverUsername)
                    messaging.registerMessagingIdentity(identity)
                    if (enrollment?.uid == identity.uid) repository.sendFriendRequest(identity, user)
                }
            }
        }
    }

    /**
     * Session friend actions: Accepts friend request. It runs the repository request through the shared messaging error handler.
     *
     * @param request Request supplied to [acceptFriendRequest].
     */
    fun acceptFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to accept this friend request.") { repository, device, _ ->
                    val identity = AccountIdentity(device.uid, coverUsername)
                    messaging.registerMessagingIdentity(identity)
                    if (enrollment?.uid == identity.uid) repository.acceptRequest(identity, request)
                }
            }
        }
    }

    /**
     * Session friend actions: Declines friend request. It runs the repository request through the shared messaging error handler.
     *
     * @param request Request supplied to [declineFriendRequest].
     */
    fun declineFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to decline this friend request.") { repository, device, _ ->
                repository.declineRequest(device.uid, request)
            }
        }
    }

    /**
     * Cancels friend request.
     *
     * @param request Request supplied to [cancelFriendRequest].
     */
    fun cancelFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to cancel this friend request.") { repository, device, _ ->
                repository.cancelRequest(device.uid, request)
            }
        }
    }

    /**
     * Creates or opens a direct message after confirming the friend relationship.
     *
     * @param friend Friend relationship record.
     */
    fun startConversation(friend: Friend) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to open this conversation.") { repository, device, token ->
                    val conversation = repository.conversationWith(
                        AccountIdentity(device.uid, coverUsername),
                        friend
                    )
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Messages) conversationActions.openConversation(
                        conversation
                    )
                }
            }
        }
    }
}
