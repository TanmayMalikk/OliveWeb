// Signed-in users: moves between cover, login, and safe-app screens.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.data.*
import com.olive.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.*
import com.olive.app.data.AccountRole
import com.olive.app.data.SupportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds


// Owns navigation history and background locking; quick exit clears PIN authorization and invalidates pending operations.
internal class SessionNavigation(private val runtime: SessionRuntime) {
    // Opens a Safe App screen and remembers where Back should return.
    fun openScreen(value: SessionScreen) {
        with(runtime) {
            with(state) {
                if (value == screen) return
                screenHistory += screen
                screen = value
            }
        }
    }

    // User exits also advance the cover-focus event so TalkBack leaves the old private screen.
    fun quickExit() {
        with(runtime) {
            with(state) {
                val enrolledRole = enrollment?.role
                accountRole = enrolledRole ?: AccountRole.USER
                if (accountRole == AccountRole.USER && screen != SessionScreen.Cover) {
                    accessibilityExitVersion++
                }
                safety.close()
                epoch++
                pinAuthorizedUid = null
                pinAuthorizedEpoch = -1L
                deviceLockRequest?.complete(null)
                deviceLockRequest = null
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
                if (accountRole == AccountRole.USER && !localMode) messaging.syncLocalNotificationHandler()
                activeTriggerId = null
                triggerJob = null
                pendingIdentity = null
                pendingProfile = null
                credentialFieldError = null
            }
        }
    }

    // Moves the app into its background state for session navigation.
    fun background() {
        with(runtime) {
            with(state) {
                // Also discard partially entered onboarding fields when leaving the app.
                if (screen == SessionScreen.Loading || screen == SessionScreen.StorageError) return
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
                } else if (accountRole == AccountRole.USER) quickExit()
            }
        }
    }

    // Returns to the previous screen for session navigation.
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

    // Session navigation: Opens the requested screen.
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
    // Loads the signed-in account's saved news state.
    suspend fun restoreCoverData(uid: String) {
        with(runtime) {
            with(state) {
                val saved = withContext(Dispatchers.IO) { coverStore.restore(uid) }
                savedNews = saved
                coverDataVersion++
            }
        }
    }

    // Switches news bookmark on or off.
    fun toggleNewsBookmark(article: NewsArticle) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                savedNews =
                    if (savedNews.any { it.link == article.link }) savedNews.filterNot { it.link == article.link }
                    else (listOf(article) + savedNews).take(100)
                val latest = savedNews
                val revision = (newsRevisions[uid] ?: 0L) + 1L
                newsRevisions[uid] = revision
                scope.launch {
                    coverSaveMutex.withLock {
                        if (newsRevisions[uid] != revision) return@withLock
                        withContext(Dispatchers.IO) { coverStore.saveNews(uid, latest) }
                    }
                }
            }
        }
    }
}

// Users and staff: manages support tickets separately from safety-plan consultations.

internal class StaffSupportController(private val runtime: SessionRuntime) {
    private val repository by lazy { SupportRepository() }

    var requested by mutableStateOf(false)
        private set
    var staffUid by mutableStateOf("")
        private set
    var conversationId by mutableStateOf("")
        private set
    var saving by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var addParticipantError by mutableStateOf<String?>(null)
        private set
    var requestStaffError by mutableStateOf<String?>(null)
        private set

    // Requests help for staff support controller.
    fun requestHelp() = updateRequest(cancel = false)

    // Returns true while a staff-help request is waiting and may be cancelled.
    fun cancelRequest() = updateRequest(cancel = true)

    // Keep the current account's ticket on screen; responses from a previous account are ignored.
    private fun updateRequest(cancel: Boolean) {
        if (runtime.localMode) {
            error = R.string.safety_account_required
            return
        }
        if (runtime.state.accountRole != AccountRole.USER) return
        val actor = runtime.coverAccountId ?: return
        val ticketToCancel = conversationId
        if (!cancel) {
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
                        if (cancel) repository.cancelRequest(ticketToCancel) else repository.requestHelp()
                    }
                }
                if (runtime.coverAccountId == actor) {
                    requested = ticket.requested
                    staffUid = ticket.staffUid
                    conversationId = ticket.conversationId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the existing ticket state and show that the support request failed.
                if (runtime.coverAccountId == actor) error = R.string.support_request_error
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    // Staff support controller: Requests admin.
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

    // Adds an accepted personal contact to the open chat after the server checks both accounts.
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

    // Removes a member from a group after the server verifies group ownership and the two-person minimum.
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

    // Removes an old add-person failure when Chat Info is closed or retried.
    fun clearAddParticipantError() {
        addParticipantError = null
    }

    // Creates a new support chat from an existing user and safe-contact conversation.
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

    // Removes old Chat Info errors before another chat is opened.
    fun clearChatInfoErrors() {
        addParticipantError = null
        requestStaffError = null
    }

    // Staff support controller: Closes the active feature.
    fun close(conversationId: String, planCompleted: Boolean = false, onClosed: () -> Unit = {}) {
        if (runtime.localMode ||
            runtime.state.accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) ||
            conversationId.isBlank() || saving > 0
        ) return
        val actor = runtime.coverAccountId ?: return
        saving++
        error = null
        runtime.scope.launch {
            // Closing a ticket is safe to retry because the server treats an already-closed ticket as success.
            try {
                if (planCompleted) runtime.safety.flushConsultation(conversationId)
                withContext(Dispatchers.IO) { repository.close(conversationId, planCompleted) }
                if (runtime.coverAccountId == actor) onClosed()
                if (runtime.coverAccountId == actor && runtime.state.activeConversation?.id == conversationId) {
                    runtime.conversationActions.closeConversation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Retry once in case the server closed the ticket but its first reply was lost.
                try {
                    if (planCompleted) runtime.safety.flushConsultation(conversationId)
                    withContext(Dispatchers.IO) { repository.close(conversationId, planCompleted) }
                    if (runtime.coverAccountId == actor) onClosed()
                    if (runtime.coverAccountId == actor && runtime.state.activeConversation?.id == conversationId) {
                        runtime.conversationActions.closeConversation()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { // Show an error only after both close attempts fail.
                    if (runtime.coverAccountId == actor) error = R.string.support_close_error
                }
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }
}

// Signed-in users: sends and accepts friend requests.

// Searches the directory and manages friend requests.
internal class FriendActions(private val runtime: SessionRuntime) {
    // Finds users for the signed-in session.
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

    // Loads the current friend relationship before changing it.
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

    // Sends a request to the selected directory account and refreshes the social list on success.
    fun sendFriendRequest(user: DirectoryUser) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to send this friend request.") { repository, device, _ ->
                    repository.sendFriendRequest(AccountIdentity(device.uid, coverUsername), user)
                }
            }
        }
    }

    // Session friend actions: Accepts friend request. It runs the repository request through the shared messaging error handler.
    fun acceptFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to accept this friend request.") { repository, device, _ ->
                    repository.acceptRequest(AccountIdentity(device.uid, coverUsername), request)
                }
            }
        }
    }

    // Session friend actions: Declines friend request. It runs the repository request through the shared messaging error handler.
    fun declineFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to decline this friend request.") { repository, device, _ ->
                repository.declineRequest(device.uid, request)
            }
        }
    }

    // Cancels friend request.
    fun cancelFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to cancel this friend request.") { repository, device, _ ->
                repository.cancelRequest(device.uid, request)
            }
        }
    }

    // Creates or opens a direct message after confirming the friend relationship.
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
