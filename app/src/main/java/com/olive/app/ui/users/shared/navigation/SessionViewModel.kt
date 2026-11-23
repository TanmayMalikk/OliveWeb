// All app roles: connects screens to the session workflows that own each feature.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Duration.Companion.milliseconds
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountDraft
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.PasskeyRecoveryRepository
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.messaging.*
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.users.shared.organization.AdminLocationMapController
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import com.olive.app.ui.users.user.location.LocationMapController


// Android lifecycle adapter. Feature controllers own workflows; screens observe read-only state.
internal class SessionViewModel @JvmOverloads constructor(
    application: Application,
    dataSourcesOverride: com.olive.app.ui.session.SessionDataSources? = null,
    localMode: Boolean = !com.olive.app.BuildConfig.FIREBASE_CONFIGURED,
) : AndroidViewModel(application),
    SessionCoverNotesActivityActions,
    SessionAccountManagementActions {
    override val runtime = SessionRuntime(application, viewModelScope, dataSourcesOverride, localMode)
    val emergencyResponses = com.olive.app.ui.session.EmergencyResponseController(application)
    val account = SessionAccountEntry(runtime)
    val messages = SessionMessages(runtime)
    val calls = SessionCalls(application, viewModelScope)
    val navigation = SessionScreenNavigation(runtime)

    // Document and map screens keep their own feature controllers.
    internal val media = runtime.mediaRepository?.let {
        MediaTransferController(
            application,viewModelScope,it,offlineCache=runtime.offlineMediaCache,
            accountKeysetProvider=runtime.settings::ensureAccountKeysetForMedia,
            accountKeysetUnlocker=runtime.settings::unlockAccountKeysetForMedia,
        )
    }
    internal var coverEmergencyConfirmation by mutableStateOf<Pair<String, Long>?>(null)
    internal var coverEmergencyInFlight = false
    internal val documents =
        SafetyDocumentsController(application, viewModelScope, runtime.safetyDocumentRepository, media)
    internal val locationMap =
        LocationMapController(application, viewModelScope, runtime.locationSharingRepository)
    internal val adminLocationMap = AdminLocationMapController(runtime.dataSources.adminLocations)

    val preparingVoiceConversationId get() = runtime.secureSharing.preparingVoiceConversationId
    /**
     * Sends voice message.
     *
     * @param file Local file to read, hash, process, or write.
     * @param metadata Metadata supplied to [sendVoiceMessage].
     * @return `true` if send voice message, or `false` otherwise.
     */
    fun sendVoiceMessage(file: java.io.File, metadata: String): Boolean =
        media?.let { runtime.secureSharing.sendVoice(file, metadata, it) } ?: false

    // Root navigation, account entry, and device lock state.
    val navigationState get() = runtime.state.navigationState
    val accountState get() = runtime.state.accountState
    val activityState get() = runtime.state.activityState
    val dashboardState get() = runtime.state.dashboardState
    val screen get() = runtime.state.screen
    // These versions tell Compose to redraw after an entry or cover event.
    val pinChallengeVersion get() = runtime.state.pinChallengeVersion
    val unlockAnimationVersion get() = runtime.state.unlockAnimationVersion
    val page get() = runtime.state.page
    val activityCategory get() = runtime.state.activityCategory
    val busy get() = runtime.state.busy
    val deviceLockRequest get() = runtime.state.deviceLockRequest
    val passkeyCredentialRequest get() = runtime.state.passkeyCredentialRequest
    val passkeyRecoveryCredentials: List<PasskeyRecoveryRepository.Credential>?
        get() = runtime.state.passkeyRecoveryCredentials
    // Signed-in account, privacy, and activity state.
    val message get() = runtime.state.message
    val inputErrorVersion get() = runtime.state.inputErrorVersion
    val profile get() = runtime.state.profile
    val attempts get() = runtime.state.attempts
    val settingsHistory get() = runtime.state.settingsHistory
    val privacyHistory get() = runtime.state.privacyHistory
    val triggerHistory get() = runtime.state.triggerHistory
    val privateEntries get() = runtime.state.privateEntries
    val sharedPrivateEntries get() = runtime.state.sharedPrivateEntries
    val sharedEntryRecipients get() = runtime.state.sharedEntryRecipients
    val loadingHistory get() = coverAccountId?.let { it == runtime.history.loadingUid } == true
    val historyLoadFailed get() = coverAccountId?.let { it == runtime.history.failedUid } == true
    val viewedSharedEntry get() = runtime.state.viewedSharedEntry
    /** Ensures real-time observation of incoming and outgoing shared private entries is active. */
    fun ensureSharedEntriesObservation() = runtime.secureSharing.observeSharedEntries()
    /** Shares a private entry with selected [friends] and invokes [onComplete] afterward. */
    fun sharePrivateEntryWithFriends(
        entry: PrivateEntry,
        friends: List<Friend>,
        onComplete: () -> Unit = {},
    ) = runtime.secureSharing.shareWithFriends(entry, friends, onComplete)
    internal val safetyPlans get() = runtime.safety
    internal val staffSupport get() = runtime.staffSupport

    // Cover, organization account, and sync state.
    val pendingSync get() = runtime.state.pendingSync
    val deliveryAddress get() = runtime.state.deliveryAddress
    val language get() = runtime.state.language
    val coverDob get() = runtime.state.coverDob
    val coverFullName get() = runtime.state.coverFullName
    val coverUsername get() = runtime.state.coverUsername
    val coverType get() = runtime.state.coverType
    val accountRole get() = runtime.state.accountRole
    val adminAccounts get() = runtime.adminPreloader.accounts
    val adminStaffAccounts get() = runtime.adminPreloader.staff
    val adminAccountsLoading get() = runtime.adminPreloader.loadingAccounts
    val adminStaffLoading get() = runtime.adminPreloader.loadingStaff
    val adminAccountsReady get() = runtime.adminPreloader.accountsReady
    val adminStaffReady get() = runtime.adminPreloader.staffReady
    val showAdminAccountsSkeleton get() = runtime.adminPreloader.showAccountsSkeleton
    val showAdminStaffSkeleton get() = runtime.adminPreloader.showStaffSkeleton
    val adminAccountsError get() = runtime.adminPreloader.accountsError
    val adminStaffError get() = runtime.adminPreloader.staffError
    /**
     * Ensures admin accounts.
     *
     * @return The resulting ensure admin accounts.
     */
    fun ensureAdminAccounts() = runtime.adminPreloader.accounts()
    /**
     * Ensures admin staff.
     */
    fun ensureAdminStaff() = runtime.adminPreloader.staff()
    val savedNews get() = runtime.state.savedNews
    val privacyBlackout get() = runtime.state.privacyBlackout
    val coverReset get() = runtime.state.coverReset
    val localMode get() = runtime.localMode
    val coverAccountId get() = runtime.coverAccountId
    val sensitive get() = runtime.sensitive
    val usesCover get() = runtime.usesCover

    /**
     * Pauses idle timer for call.
     */
    fun pauseIdleTimerForCall() = runtime.privacy.pauseIdleTimerForCall()

    /**
     * Resumes idle timer after call.
     */
    fun resumeIdleTimerAfterCall() = runtime.privacy.resumeIdleTimerAfterCall()

    @Volatile
    var externalFilePickerOpen = false
        private set

    var callPermissionRequestOpen by mutableStateOf(false)
        private set
    var safeSystemActivityOpen = false
        private set
    private var safeSystemLaunchId = 0

    /**
     * Starts safe system activity.
     *
     * @return The computed begin safe system activity number, or `null` if unavailable.
     */
    internal fun beginSafeSystemActivity(): Int? {
        if (safeSystemActivityOpen) return null
        safeSystemActivityOpen = true
        return ++safeSystemLaunchId
    }

    /**
     * Finishes safe system activity.
     *
     * @param launchId Launch ID supplied to [finishSafeSystemActivity].
     */
    internal fun finishSafeSystemActivity(launchId: Int = safeSystemLaunchId) {
        if (launchId == safeSystemLaunchId) safeSystemActivityOpen = false
    }

    /**
     * Computes safe system activity result.
     *
     * @param view View supplied to [safeSystemActivityResult].
     */
    internal fun safeSystemActivityResult(view: android.view.View) {
        val launchId = safeSystemLaunchId
        // Some settings activities return a result before their window has even taken focus.
        // Release the exemption only once Olive has its window back.
        view.postDelayed(object : Runnable {
            /**
             * Executes the session view model.
             */
            override fun run() {
                if (launchId != safeSystemLaunchId || !safeSystemActivityOpen || !view.isAttachedToWindow) return
                if (view.hasWindowFocus()) finishSafeSystemActivity(launchId)
                else view.postDelayed(this, 250L)
            }
        }, 250L)
    }

    /** Carries checklist-specific tags across navigation into the full-screen camera. */
    internal var pendingCameraUploadTags: Set<String> = emptySet()

    init {
        runtime.accountSession.load()
    }

    /**
     * Uses the same durable reconciliation path after Android returns the app to the foreground.
     */
    fun reconcilePriorityData() {
        runtime.coverAccountId?.let(runtime.prioritySync::request)
    }

    /**
     * Opens Android's file picker for a document attachment.
     */
    fun beginExternalFilePicker() {
        externalFilePickerOpen = true
    }

    /**
     * Clears the pending file-picker request after Android returns a result.
     */
    fun endExternalFilePicker() {
        externalFilePickerOpen = false
    }

    /**
     * Starts call permission request.
     */
    fun beginCallPermissionRequest() {
        callPermissionRequestOpen = true
    }

    /**
     * Ends call permission request.
     */
    fun endCallPermissionRequest() {
        callPermissionRequestOpen = false
    }

    /**
     * Shares the user's current live-location session with a chat.
     *
     * @param durationMillis Duration or timestamp for duration, in milliseconds.
     * @param requestPermission Function used to transform or provide request permission.
     */
    fun shareLiveLocationInChat(durationMillis: Long, requestPermission: suspend () -> Boolean) =
        runtime.secureSharing.shareLiveLocation(durationMillis, locationMap, requestPermission)

    /**
     * Saves updated safety-plan questionnaire answers to the encrypted profile.
     *
     * @param intake Completed questionnaire selections supplied by the user.
     */
    fun saveSafetyIntake(intake: SafetyPlanIntake) {
        val current = profile ?: return
        runtime.settings.saveSafeProfile(current.copy(safetyIntake = intake.copy(completed = true)))
    }

    /**
     * Computes quick exit.
     *
     * @param expectedCallId Optional expected call ID supplied to [quickExit].
     * @return The resulting [Job] for quick exit.
     */
    fun quickExit(expectedCallId: String? = null): Job {
        val hangup = calls.finish(coverAccountId.orEmpty(), expectedCallId)
        pendingCameraUploadTags = emptySet()
        navigation.quickExit()
        return hangup
    }

    /**
     * Closing this session also clears document and map data held by their screen controllers.
     */
    fun signOut() {
        pendingCameraUploadTags = emptySet()
        coverEmergencyConfirmation = null
        coverEmergencyInFlight = false
        documents.clear()
        locationMap.clear()
        adminLocationMap.close()
        runtime.adminPreloader.clear()
        runtime.accountSession.signOut()
    }

    /**
     * Stops account listeners and background work when the session view model is removed.
     */
    override fun onCleared() {
        runtime.adminPreloader.clear()
        coverEmergencyConfirmation = null
        coverEmergencyInFlight = false
        documents.clear()
        locationMap.clear()
        adminLocationMap.close()
        runtime.safety.close()
        runtime.messaging.stopMessaging()
        calls.close()
    }
}

// Users, safe contacts, staff, and admins: keeps message screen state and chat actions together.

internal class SessionMessages(private val runtime: SessionRuntime) {
    val state get() = runtime.state.messagingState
    val userSearchResults get() = runtime.state.userSearchResults
    val friends get() = runtime.state.friends
    val incomingFriendRequests get() = runtime.state.incomingFriendRequests
        .filter { it.recipientUid == runtime.enrollment?.uid }
    val outgoingFriendRequests get() = runtime.state.outgoingFriendRequests
        .filter { it.senderUid == runtime.enrollment?.uid }
    val conversations get() = runtime.state.conversations
    val archivedConversationIds get() = runtime.state.archivedConversationIds
    val restoredClosedConversationIds get() = runtime.state.restoredClosedConversationIds
    val privateContactNotes get() = runtime.state.privateContactNotes
    val activeConversation get() = runtime.state.activeConversation
    val chatQuickExitHintVisible get() = runtime.state.chatQuickExitHintVisible
    val chatMessages get() = runtime.state.chatMessages
    val loadingIndex get() = runtime.messaging.loadingIndex
    val loadingConversation get() = runtime.conversationActions.loadingConversation
    val busy get() = runtime.state.messagingBusy
    val needsReconnect get() = runtime.state.messagingNeedsReconnect
    val pendingActionCount get() = runtime.pendingMessageActionCount
    /**
     * Checks whether unread.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @return `true` if is unread, or `false` otherwise.
     */
    fun isUnread(conversation: ChatConversation) = runtime.isConversationUnread(conversation)
    /**
     * Computes unread count for support tag.
     *
     * @param tag Tag supplied to [unreadCountForSupportTag].
     * @return The resulting unread count for support tag.
     */
    fun unreadCountForSupportTag(tag: String) = runtime.state.conversations.count { conversation ->
        conversation.type == "SUPPORT" && conversation.supportTag == tag &&
            runtime.isActiveUnreadConversation(conversation)
    }

    /**
     * Computes unread count for chat tab.
     *
     * @param tickets Whether to apply tickets.
     * @return The computed unread count for chat tab numeric value.
     */
    fun unreadCountForChatTab(tickets: Boolean): Int =
        runtime.state.conversations.count {
            (it.type == "SUPPORT") == tickets && runtime.isActiveUnreadConversation(it)
        } + if (tickets) 0 else runtime.incomingFriendRequestCount

    /**
     * Starts the friend and conversation listeners if messaging is not already running or starting.
     *
     * @param showFeedback Whether to show immediate setup or sign-in messages.
     */
    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    /**
     * Reauthenticates the linked account and restarts messaging observation.
     *
     * @param password The current account password.
     * @param onConnected Runs after sign-in succeeds if the same messaging session is still active.
     */
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    /**
     * Gets a shareable invite for the signed-in account.
     *
     * @param uid The expected UID of the signed-in account.
     * @param refresh Whether to replace an existing invite.
     * @return The invite code, share link, and expiration time.
     */
    suspend fun createFriendInvite(uid: String, refresh: Boolean) =
        FriendInviteRepository(uid).create(refresh)

    /**
     * Claims another account's friend invite with the current Firebase account.
     *
     * @param uid The expected account UID, if known.
     * @param code The invitation code entered by the user.
     * @return The username of the person who created the invite.
     */
    suspend fun claimFriendInvite(uid: String?, code: String) =
        FriendInviteRepository(uid).claim(code)

    /**
     * Watches for Firebase to report that the displayed invite was claimed or replaced.
     *
     * @param uid The UID of the invite owner.
     * @param code The invite code currently displayed.
     * @param onUnavailable Runs when the invite has been claimed or its code no longer matches.
     * @return The subscription to close when the invite dialog is dismissed.
     */
    fun observeFriendInviteUnavailable(uid: String, code: String, onUnavailable: () -> Unit) =
        FriendInviteRepository(uid).observeUnavailable(code, onUnavailable)

    /**
     * Searches the account directory for an exact username.
     *
     * @param query Search query string entered by the user.
     * @return The resulting search users.
     */
    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    /**
     * Returns the current friend relationship with this account.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [FriendRelationship] for relationship for.
     */
    fun relationshipFor(uid: String): FriendRelationship = runtime.friendActions.relationshipFor(uid)
    /**
     * Sends a friend request to the selected account.
     *
     * @param user User supplied to [sendFriendRequest].
     */
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    /**
     * Accepts an incoming friend request.
     *
     * @param request Request supplied to [acceptFriendRequest].
     */
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    /**
     * Declines an incoming friend request.
     *
     * @param request Request supplied to [declineFriendRequest].
     */
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    /**
     * Returns true when the outgoing friend request may still be cancelled.
     *
     * @param request Request supplied to [cancelFriendRequest].
     * @return `true` if cancel friend request, or `false` otherwise.
     */
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    /**
     * Opens an existing conversation by its ID.
     *
     * @param friend Friend relationship record.
     */
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)

    /**
     * Creates or opens a direct conversation with this account.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     */
    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    /**
     * Starts observing membership events.
     *
     * @param id ID supplied to [observeMembershipEvents].
     * @param actorUid Firebase UID of the account performing the action, or null when not provided.
     * @param onUpdate Callback invoked when updated data is available.
     * @return The resulting observe membership events.
     */
    internal fun observeMembershipEvents(
        id: String, actorUid: String? = null, onUpdate: (List<ChatMembershipEvent>) -> Unit,
    ) = runtime.messagingRepository?.observeMembershipEvents(id, actorUid, onUpdate)

    /**
     * Finds requested conversation.
     *
     * @param id ID supplied to [findRequestedConversation].
     * @return The resulting [ChatConversation], or `null` if unavailable.
     */
    suspend fun findRequestedConversation(id: String): ChatConversation? {
        if (id.isBlank()) return null
        val uid = runtime.coverAccountId ?: return null
        return try {
            runtime.messagingRepository?.getConversation(id)?.takeIf {
                runtime.coverAccountId == uid && uid in it.participantIds && it.closedAt == 0L
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The live conversation list can still deliver the ticket after a transient read failure.
            null
        }
    }

    /**
     * Closes the current conversation and returns to the chat list.
     */
    fun closeConversation() = runtime.conversationActions.closeConversation()
    /**
     * Archives or restores the selected conversation for this account.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param archived Whether to apply archived.
     * @param onSaved Runs when the device has saved the change.
     */
    fun setConversationArchived(
        conversation: ChatConversation,
        archived: Boolean,
        onSaved: () -> Unit = {},
    ) = runtime.conversationActions.setArchived(conversation, archived, onSaved)

    /**
     * Saves a private note about this friend or supported user.
     *
     * @param target Target destination or output object to write to.
     * @param text Text string to display, parse, or send.
     * @param onSaved Callback invoked when saved changes or occurs.
     */
    fun savePrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.savePrivateNote(target, text, onSaved)

    /**
     * Encrypts and sends the typed message to the current conversation.
     *
     * @param text Text string to display, parse, or send.
     * @param onSent Callback invoked when sent changes or occurs.
     */
    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    /**
     * Retries the failed encrypted queue head without bypassing message order.
     *
     * @param chat Chat supplied to [retryMessage].
     */
    fun retryMessage(chat: ChatMessage) = runtime.conversationActions.retryMessage(chat)
    /**
     * Discards failed message.
     *
     * @param chat Chat supplied to [discardFailedMessage].
     */
    fun discardFailedMessage(chat: ChatMessage) = runtime.conversationActions.discardFailedMessage(chat)
    /**
     * Un-sends a message when the signed-in account is allowed to remove it.
     *
     * @param chat Chat supplied to [unsendMessage].
     */
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    /**
     * Dismisses the quick-exit tip shown inside a chat.
     */
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()

    /**
     * Encrypts a private note and shares it with the current chat.
     *
     * @param entry Entry supplied to [sharePrivateEntryInChat].
     */
    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    /**
     * Gives the current chat encrypted access to a safety document.
     *
     * @param document Safety document metadata record.
     */
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    /**
     * Shares the safety plan and starts a separate plan-consult ticket.
     */
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    /**
     * Opens a private note that was shared in this conversation.
     *
     * @param entryId Unique identifier of the private entry or note.
     * @return The resulting open shared private entry.
     */
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    /**
     * Closes the shared-note viewer and releases its live listener.
     */
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()

    /**
     * Escalates this support ticket by adding an available admin.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    fun requestAdminHelp(conversationId: String) = runtime.staffSupport.requestAdmin(conversationId)
    /**
     * Adds an accepted contact to this encrypted conversation without starting a new chat.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [addConversationParticipant].
     */
    fun addConversationParticipant(conversationId: String, participantUid: String) =
        runtime.staffSupport.addParticipant(conversationId, participantUid)
    /**
     * Removes another member when the signed-in account owns this group chat.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param participantUid Participant user ID supplied to [removeConversationParticipant].
     */
    fun removeConversationParticipant(conversationId: String, participantUid: String) =
        runtime.staffSupport.removeParticipant(conversationId, participantUid)
    /**
     * Starts a separate staff ticket with every person already in this personal chat.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param onCreated Callback invoked when created changes or occurs.
     * @return The resulting request staff for conversation.
     */
    fun requestStaffForConversation(conversationId: String, onCreated: (String) -> Unit) =
        runtime.staffSupport.requestStaffForConversation(conversationId, onCreated)
    /**
     * Closes the support ticket and archives it for every participant.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param planCompleted Whether to apply plan completed.
     * @param onClosed Callback invoked when closed changes or occurs.
     */
    fun closeSupportChat(conversationId: String, planCompleted: Boolean = false, onClosed: () -> Unit = {}) =
        runtime.staffSupport.close(conversationId, planCompleted, onClosed)
}

// Signed-in accounts and admins: connects profile, cover settings, and account management screens.

internal interface SessionAccountManagementActions {
    val runtime: SessionRuntime

    /**
     * Saves the staff or admin details entered during account setup.
     *
     * @param fullName Full name supplied to [saveOrganizationProfile].
     * @param dob Date of birth supplied to [saveOrganizationProfile].
     * @param email Email supplied to [saveOrganizationProfile].
     * @param phone Phone supplied to [saveOrganizationProfile].
     */
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    /**
     * Updates the signed-in staff or admin profile after setup.
     *
     * @param fullName Full name supplied to [updateOrganizationProfile].
     * @param dob Date of birth supplied to [updateOrganizationProfile].
     * @param email Email supplied to [updateOrganizationProfile].
     * @param phone Phone supplied to [updateOrganizationProfile].
     */
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    /**
     * Reloads the account list shown in the admin portal.
     *
     * @return The resulting refresh admin accounts.
     */
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    /**
     * Reloads staff workload rows without downloading the full account directory.
     */
    fun refreshAdminStaff() = runtime.organization.refreshAdminStaff()
    /**
     * Creates a staff or admin account from the admin form.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting create managed account.
     */
    fun createManagedAccount(value: AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    /**
     * Deletes a staff or admin account selected in the admin portal.
     *
     * @param value Input value to store, parse, or format.
     */
    fun deleteManagedAccount(value: AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)

    /**
     * Saves the user profile and situation details from Safe App settings.
     *
     * @param fullName Full name supplied to [saveSettings].
     * @param affected Affected supplied to [saveSettings].
     * @param abuserName Abuser name supplied to [saveSettings].
     * @param dob Date of birth supplied to [saveSettings].
     * @param situation Situation supplied to [saveSettings].
     * @param address Address supplied to [saveSettings].
     */
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress,
        email: String? = null,
        emailCommunicationConsent: Boolean? = null,
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address, email, emailCommunicationConsent)

    /**
     * Saves the public profile details used by the selected cover app.
     *
     * @param fullName Full name supplied to [saveCoverSettings].
     * @param dob Date of birth supplied to [saveCoverSettings].
     * @param address Address supplied to [saveCoverSettings].
     */
    fun saveCoverSettings(
        fullName: String,
        dob: String = runtime.state.coverDob,
        address: DeliveryAddress = runtime.state.deliveryAddress,
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    /**
     * Changes which cover app opens before the private Safe App.
     *
     * @param type Type supplied to [changeCover].
     */
    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    /**
     * Changes the hidden phrase that opens private entry from a cover app.
     *
     * @param trigger Trigger supplied to [changeTrigger].
     * @param pin User-entered PIN string.
     */
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    /**
     * Saves the trusted contacts entered during setup or in settings.
     *
     * @param value Input value to store, parse, or format.
     */
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    /**
     * Sends local setting changes to the account and downloads newer changes.
     *
     * @return The resulting sync.
     */
    fun sync() = runtime.settings.sync()
    /**
     * Restarts account notification listeners after the signed-in account changes.
     */
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    /**
     * Checks the current password before replacing it with the new password.
     *
     * @param currentPassword Current password supplied to [changePassword].
     * @param newPassword New password supplied to [changePassword].
     * @param confirmation Confirmation supplied to [changePassword].
     */
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)
    /** Refreshes the list of registered passkey recovery factors. */
    fun refreshPasskeyRecovery() = runtime.settings.refreshPasskeyRecovery()
    /** Starts enrolling a new passkey recovery factor for the signed-in account. */
    fun enablePasskeyRecovery() = runtime.settings.enablePasskeyRecovery()
    /** Removes an enrolled passkey recovery credential identified by [credentialId]. */
    fun removePasskeyRecovery(credentialId: String) =
        runtime.settings.removePasskeyRecovery(credentialId)
}

// Connects cover, private note, activity, and privacy screens to their workflows.
internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    /**
     * Passes the private-note button action to the session privacy controller.
     */
    fun noteInteraction() = runtime.privacy.noteInteraction()
    /**
     * Returns the idle timeout seconds.
     *
     * @return The computed idle timeout seconds numeric value.
     */
    fun idleTimeoutSeconds(): Int = runtime.privacy.idleTimeoutSeconds()
    /**
     * Sets idle timeout seconds.
     *
     * @param seconds Seconds supplied to [setIdleTimeoutSeconds].
     */
    fun setIdleTimeoutSeconds(seconds: Int) = runtime.privacy.setIdleTimeoutSeconds(seconds)
    /**
     * Records that Android detected a screenshot or screen recording.
     *
     * @param kind Kind supplied to [captureDetected].
     */
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    /**
     * Records when Android reports that screen recording has stopped.
     */
    fun recordingStopped() = runtime.privacy.recordingStopped()
    /**
     * Adds a settings change to the user's private activity history.
     *
     * @param area Area supplied to [recordSettingsInteraction].
     * @param action Action supplied to [recordSettingsInteraction].
     */
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    /**
     * Adds or removes an article from the news cover's bookmarks.
     *
     * @param article Article supplied to [toggleNewsBookmark].
     * @return The resulting toggle news bookmark.
     */
    fun toggleNewsBookmark(article: NewsArticle) =
        setNewsBookmark(article, runtime.state.savedNews.none { it.link == article.link })

    /** Sets a bookmark to the requested state, so a repeated Undo does not toggle it off. */
    fun setNewsBookmark(article: NewsArticle, bookmarked: Boolean) =
        runtime.covers.setNewsBookmark(article, bookmarked)

    /**
     * Opens one category in the private activity-history screen.
     *
     * @param value Input value to store, parse, or format.
     */
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    /**
     * Returns from an activity category to the full history list.
     */
    fun activityBack() = runtime.history.activityBack()

    /**
     * Clears the note editor's success or error message before the next action.
     */
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    /**
     * Creates a private note or updates the note with the matching ID.
     *
     * @param id Optional ID supplied to [savePrivateEntry].
     * @param kind Kind supplied to [savePrivateEntry].
     * @param title Primary display title text.
     * @param body Body text content.
     * @param incidentDate Incident date supplied to [savePrivateEntry].
     * @param incidentTime Incident time supplied to [savePrivateEntry].
     * @param incidentLocation Incident location supplied to [savePrivateEntry].
     * @param incidentLatitude Optional incident latitude supplied to [savePrivateEntry].
     * @param incidentLongitude Optional incident longitude supplied to [savePrivateEntry].
     * @param attachmentIds Collection of attachment IDs values to process.
     */
    fun savePrivateEntry(
        id: String?, kind: PrivateEntryKind, title: String, body: String,
        incidentDate: String = "", incidentTime: String = "", incidentLocation: String = "",
        incidentLatitude: Double? = null, incidentLongitude: Double? = null,
        attachmentIds: List<String> = emptyList(),
        newEntryId: String = java.util.UUID.randomUUID().toString(),
    ) = runtime.notes.savePrivateEntry(
        id, kind, title, body, incidentDate, incidentTime, incidentLocation,
        incidentLatitude, incidentLongitude, attachmentIds, newEntryId,
    )

    /**
     * Pins or unpins a private note so important notes stay easy to find.
     *
     * @param id ID supplied to [togglePrivateEntryPin].
     * @return The resulting toggle private entry PIN.
     */
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    /**
     * Moves a private note into the note trash.
     *
     * @param id ID supplied to [trashPrivateEntry].
     */
    fun trashPrivateEntry(id: String, onTrashed: (() -> Unit)? = null) = runtime.notes.trashPrivateEntry(id, onTrashed)
    /**
     * Restores a private note from the note trash.
     *
     * @param id ID supplied to [restorePrivateEntry].
     */
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}

// Opens and closes the app's root screens and safe-app pages.
internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    /**
     * Gives navigation screens the same quick-exit action from the current session.
     *
     * @return The resulting quick exit.
     */
    fun quickExit() = runtime.navigation.quickExit()
    /**
     * Sends the app to the background when the user presses Quick Exit.
     */
    fun background() = runtime.navigation.background()
    /**
     * Returns to the previous Safe App screen.
     */
    fun back() = runtime.navigation.back()
    /**
     * Opens the requested Safe App page from shared navigation.
     *
     * @param page Page supplied to [openSafePage].
     */
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}

/**
 * Calls are private-account features. Direct and personal group chats can call; support chats cannot.
 *
 * @param role Account role determining workspace permissions.
 * @param conversation Conversation metadata including participants and ticket status.
 * @param currentUid Current user ID supplied to [canCallFromConversation].
 * @param featureAvailable Whether to apply feature available.
 * @return `true` if can call from conversation, or `false` otherwise.
 */
internal fun canCallFromConversation(
    role: AccountRole,
    conversation: ChatConversation,
    currentUid: String,
    featureAvailable: Boolean = isVoipApiConfigured(),
): Boolean = featureAvailable && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) &&
    conversation.type in setOf("DIRECT", "GROUP") &&
    conversation.closedAt == 0L &&
    currentUid.isNotBlank() &&
    currentUid in conversation.participantIds &&
    conversation.participantIds.any { it != currentUid }

/**
 * Checks whether the caller can use VoIP.
 *
 * @param role Account role determining workspace permissions.
 * @return `true` if can use VoIP, or `false` otherwise.
 */
internal fun canUseVoip(role: AccountRole): Boolean =
    role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

/**
 * Determines whether the app should show call overlay.
 *
 * @param screen Screen supplied to [shouldShowCallOverlay].
 * @param role Account role determining workspace permissions.
 * @return `true` if should show call overlay, or `false` otherwise.
 */
internal fun shouldShowCallOverlay(screen: SessionScreen, role: AccountRole): Boolean =
    screen == SessionScreen.Safe && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

/**
 * A failed call with no room attached is shown inside its eligible chat instead of covering every safe-app page.
 *
 * @param state Current state instance to read or render.
 * @return `true` if should show active call, or `false` otherwise.
 */
internal fun shouldShowActiveCall(state: VoipSessionState): Boolean = when (state) {
    is VoipSessionState.Connecting,
    is VoipSessionState.Active,
    is VoipSessionState.Leaving -> true
    is VoipSessionState.Error -> state.call != null
    VoipSessionState.Idle -> false
}

/**
 * Resolves incoming caller.
 *
 * @param invitation Invitation supplied to [resolveIncomingCaller].
 * @param conversations Collection of conversations values to process.
 * @param currentUid Current user ID supplied to [resolveIncomingCaller].
 * @return The resulting [VoipInvitation], or `null` if unavailable.
 */
internal fun resolveIncomingCaller(
    invitation: VoipInvitation,
    conversations: List<ChatConversation>,
    currentUid: String,
): VoipInvitation? {
    val callerUsername = conversations.firstOrNull { conversation ->
        invitation.callerId in conversation.participantIds && currentUid in conversation.participantIds
    }?.participantUsernames?.get(invitation.callerId).orEmpty()
    return invitation.copy(name = callerUsername).takeIf { callerUsername.isNotBlank() }
}

/**
 * Determines whether the app should leave group call.
 *
 * @param call Call supplied to [shouldLeaveGroupCall].
 * @param currentUid Current user ID supplied to [shouldLeaveGroupCall].
 * @param remoteParticipantCount Remote participant count supplied to [shouldLeaveGroupCall].
 * @return `true` if should leave group call, or `false` otherwise.
 */
internal fun shouldLeaveGroupCall(
    call: VoipCall,
    currentUid: String,
    remoteParticipantCount: Int,
): Boolean = call.participants.size > 2 && currentUid.isNotBlank() &&
    (call.callerId != currentUid || remoteParticipantCount > 0)

internal data class SessionCallsUiState(
    val invitations: List<VoipInvitation> = emptyList(),
    val callHistory: List<VoipCall> = emptyList(),
    val message: String? = null,
)

internal class SessionCalls(
    private val application: Application,
    private val scope: CoroutineScope,
    private val manager: VoipManager = VoipManager(),
    private val controller: VoipSessionController = VoipSessionController.create(application, manager),
    private val available: Boolean = isVoipApiConfigured(),
) {
    private val invitationPreferences = application.getSharedPreferences(
        "dismissed-voip-invitations",
        android.content.Context.MODE_PRIVATE,
    )
    private var invitationJob: Job? = null
    private var callJoinJob: Job? = null
    private var observedUid: String? = null
    private var configuredRole: AccountRole? = null
    private val suppressedInvitationIds = invitationPreferences
        .getStringSet(DISMISSED_INVITATIONS_KEY, emptySet())
        .orEmpty()
        .toMutableSet()
    private val invitationFirstSeenAt = mutableMapOf<String, Long>()

    val state: StateFlow<VoipSessionState> = controller.state
    private val uiStateFlow = MutableStateFlow(SessionCallsUiState())
    val uiState: StateFlow<SessionCallsUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (SessionCallsUiState) -> SessionCallsUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    var invitations: List<VoipInvitation>
        get() = uiStateSnapshot.invitations
        private set(value) = updateUiState { it.copy(invitations = value) }
    var callHistory: List<VoipCall>
        get() = uiStateSnapshot.callHistory
        private set(value) = updateUiState { it.copy(callHistory = value) }
    var message: String?
        get() = uiStateSnapshot.message
        private set(value) = updateUiState { it.copy(message = value) }

    init {
        // Keeps the microphone session running while the phone is on Home or another app is open.
        scope.launch {
            state.map { callState ->
                when (callState) {
                    is VoipSessionState.Connecting -> callState.call.callId
                    is VoipSessionState.Active -> callState.call.callId
                    is VoipSessionState.Leaving -> callState.call.callId
                    else -> null
                }
            }.distinctUntilChanged().collect { callId ->
                if (callId == null) VoipCallForegroundService.stop(application)
                else VoipCallForegroundService.start(application, callId, configuredRole == AccountRole.USER)
            }
        }
    }

    /**
     * Starts one invitation listener while this signed-in person has the messaging screen open.
     *
     * @param uid Firebase UID of the target user account.
     * @param role Account role determining workspace permissions.
     */
    fun observeInvitations(uid: String, role: AccountRole) {
        if (configuredRole != role && uid.isNotBlank()) {
            configuredRole = role
            scope.launch {
                runCatching { VoipPushTokenRegistration.configureForRole(application, role) }
            }
        }
        if (!canUseVoip(role) || !available || uid.isBlank()) {
            stopObserving()
            return
        }
        if (observedUid == uid && invitationJob?.isActive == true) return
        stopObserving()
        observedUid = uid
        invitationJob = scope.launch {
            while (isActive) {
                try {
                    val calls = manager.listCalls()
                    callHistory = calls
                    val now = System.currentTimeMillis()
                    val received = calls.filter { call ->
                        call.status == VoipCallStatus.ACTIVE && call.callerId != uid &&
                            call.participants.any {
                                it.userId == uid && it.status == VoipParticipantStatus.PENDING
                            }
                    }.map { call ->
                        VoipInvitation(
                            callId = call.callId,
                            callerId = call.callerId,
                            name = call.callerName.ifBlank { call.name },
                            status = VoipParticipantStatus.PENDING,
                            expiresAt = call.expiresAt,
                        )
                    }
                    received.forEach { invitationFirstSeenAt.putIfAbsent(it.callId, now) }
                    val locallyExpired = received.filter { invitation ->
                        val deadline = invitation.expiresAt.takeIf { it > 0 }
                            ?: (invitationFirstSeenAt.getValue(invitation.callId) + LOCAL_RING_TIMEOUT_MS)
                        deadline <= now
                    }.mapTo(mutableSetOf(), VoipInvitation::callId)
                    if (locallyExpired.isNotEmpty()) rememberSuppressed(locallyExpired)
                    invitations = received.filter {
                        it.callId !in suppressedInvitationIds
                    }
                    val sessionCall = when (val current = state.value) {
                        is VoipSessionState.Connecting -> current.call
                        is VoipSessionState.Active -> current.call
                        else -> null
                    }
                    if (sessionCall != null && calls.firstOrNull {
                            it.callId == sessionCall.callId
                        }?.status?.let { it != VoipCallStatus.ACTIVE } == true
                    ) controller.dismiss()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    message = error.message ?: "Could not check incoming calls"
                }
                val callIsOpen = state.value is VoipSessionState.Connecting ||
                    state.value is VoipSessionState.Active || invitations.isNotEmpty()
                delay((if (callIsOpen) ACTIVE_CALL_POLL_MS else IDLE_CALL_POLL_MS).milliseconds)
            }
        }
    }

    /**
     * Starts a call with every other person in a direct or personal group chat.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param callerUid Caller user ID supplied to [start].
     * @param role Account role determining workspace permissions.
     */
    fun start(conversation: ChatConversation, callerUid: String, role: AccountRole) {
        if (!canCallFromConversation(role, conversation, callerUid, available)) {
            message = "Voice calls are available only in personal User and Safe Contact chats"
            return
        }
        val recipients = conversation.participantIds.filterNot { it == callerUid }.distinct()
        val recipientName = conversation.participantIds
            .filterNot { it == callerUid }
            .mapNotNull(conversation.participantUsernames::get)
            .filter(String::isNotBlank)
            .joinToString(", ")
            .ifBlank { "Private call" }
        val callerName = conversation.participantUsernames[callerUid]
            .orEmpty()
            .ifBlank { "Private call" }
        launchCallJoin {
            controller.startAndJoin(
                recipientName,
                callerUid,
                recipients,
                callerName,
                conversation.id,
            )
        }
    }

    /**
     * Accepts the selected invitation and joins its LiveKit audio room.
     *
     * @param invitation Invitation supplied to [accept].
     * @param currentUid Current user ID supplied to [accept].
     * @param role Account role determining workspace permissions.
     */
    fun accept(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        launchCallJoin { controller.acceptAndJoin(invitation.callId, currentUid) }
    }

    /**
     * Hides the invitation before Android opens its microphone permission window. This prevents the polling loop from restoring the full-screen prompt while permission is being answered.
     *
     * @param invitation Invitation supplied to [hide].
     */
    fun hide(invitation: VoipInvitation) {
        suppressInvitation(invitation.callId)
    }

    /**
     * Declines the invitation without opening the microphone or joining its room.
     *
     * @param invitation Invitation supplied to [decline].
     * @param currentUid Current user ID supplied to [decline].
     * @param role Account role determining workspace permissions.
     */
    fun decline(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        scope.launch {
            runCallAction {
                manager.declineCall(invitation.callId, currentUid)
            }
        }
    }

    /**
     * Joins an active call from the event shown in chat, using the member's current call status.
     *
     * @param call Call supplied to [joinActive].
     * @param currentUid Current user ID supplied to [joinActive].
     * @param role Account role determining workspace permissions.
     */
    fun joinActive(call: VoipCall, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) ||
            !call.canBeJoinedBy(currentUid)
        ) return
        launchCallJoin {
            when (call.participants.firstOrNull { it.userId == currentUid }?.status) {
                VoipParticipantStatus.PENDING -> controller.acceptAndJoin(call.callId, currentUid)
                VoipParticipantStatus.DECLINED -> controller.rejoinCall(call.callId)
                VoipParticipantStatus.ACCEPTED -> controller.joinCall(call)
                null -> Unit
            }
        }
    }

    /**
     * Updates whether other people in the active call can hear this device's microphone.
     *
     * @param muted Whether to apply muted.
     */
    fun setMuted(muted: Boolean) {
        scope.launch { runCallAction { controller.setMicrophoneMuted(muted) } }
    }

    /**
     * Sets speaker enabled.
     *
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setSpeakerEnabled(enabled: Boolean) {
        scope.launch { runCallAction { controller.setSpeakerEnabled(enabled) } }
    }

    /**
     * Launches call join.
     *
     * @param action Action or callback executed for action.
     */
    private fun launchCallJoin(action: suspend () -> Unit) {
        if (callJoinJob?.isActive == true) return
        callJoinJob = scope.launch { runCallAction(action) }
    }

    /**
     * Checks whether the caller can handle notification.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @return `true` if can handle notification, or `false` otherwise.
     */
    fun canHandleNotification(callId: String): Boolean = when (val current = state.value) {
        is VoipSessionState.Connecting -> current.call.callId == callId
        is VoipSessionState.Active -> current.call.callId == callId
        else -> false
    }

    /**
     * A caller ends the room. A member leaving a group only disconnects their own phone.
     *
     * @param currentUid Current user ID supplied to [finish].
     * @param expectedCallId Optional expected call ID supplied to [finish].
     * @return The resulting [Job] instance.
     */
    fun finish(currentUid: String, expectedCallId: String? = null): Job {
        val joining = callJoinJob
        return scope.launch {
            if (state.value == VoipSessionState.Idle) joining?.join()
            runCallAction {
                val active = state.value
                val call = when (active) {
                    is VoipSessionState.Connecting -> active.call
                    is VoipSessionState.Active -> active.call
                    is VoipSessionState.Error -> active.call
                    else -> null
                } ?: return@runCallAction
                if (expectedCallId != null && call.callId != expectedCallId) return@runCallAction
                val remoteParticipantCount = (active as? VoipSessionState.Active)
                    ?.remoteParticipantCount
                    ?: 0
                when {
                    active is VoipSessionState.Error -> controller.dismiss()
                    shouldLeaveGroupCall(call, currentUid, remoteParticipantCount) ->
                        controller.leaveCall()
                    else -> controller.endCall()
                }
            }
        }
    }

    /**
     * Clears message.
     */
    fun clearMessage() {
        message = null
    }

    /**
     * Reports the session calls.
     *
     * @param message Chat message or remote payload.
     */
    fun report(message: String) {
        this.message = message
    }

    /**
     * Stops network polling when the session is cleared or the account no longer supports calls.
     */
    fun close() {
        stopObserving()
        VoipCallForegroundService.stop(application)
        val current = state.value
        if (current is VoipSessionState.Connecting || current is VoipSessionState.Active ||
            current is VoipSessionState.Error) finish("")
    }

    /**
     * Stops observing.
     */
    private fun stopObserving() {
        invitationJob?.cancel()
        invitationJob = null
        observedUid = null
        invitations = emptyList()
        invitationFirstSeenAt.clear()
    }

    /**
     * Computes history for.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param role Account role determining workspace permissions.
     * @return List of resulting history for items.
     */
    fun historyFor(conversationId: String, role: AccountRole): List<VoipCall> =
        if (canUseVoip(role)) callHistory.filter { it.conversationId == conversationId }
        else emptyList()

    /**
     * Cancels notification.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    private fun cancelNotification(callId: String) {
        androidx.core.app.NotificationManagerCompat.from(application).cancel(callId.hashCode())
    }

    /**
     * Computes suppress invitation.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    private fun suppressInvitation(callId: String) {
        rememberSuppressed(setOf(callId))
        invitations = invitations.filterNot { it.callId == callId }
        cancelNotification(callId)
    }

    /**
     * Computes remember suppressed.
     *
     * @param callIds Collection of call IDs values to process.
     */
    private fun rememberSuppressed(callIds: Set<String>) {
        suppressedInvitationIds += callIds
        invitationPreferences.edit {
                putStringSet(DISMISSED_INVITATIONS_KEY, suppressedInvitationIds.toSet())
            }
    }

    /**
     * Executes call action.
     *
     * @param action Action or callback executed for action.
     */
    private suspend fun runCallAction(action: suspend () -> Unit) {
        message = null
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message = error.message ?: "The voice call could not be completed"
            // A failed API or room request returns to chat so its error cannot cover unrelated support screens.
            if (state.value is VoipSessionState.Error) controller.dismiss()
        }
    }

    companion object {
        private const val DISMISSED_INVITATIONS_KEY = "call_ids"
        private const val LOCAL_RING_TIMEOUT_MS = 20_000L
        private const val ACTIVE_CALL_POLL_MS = 1_000L
        private const val IDLE_CALL_POLL_MS = 5_000L
    }
}
