// All app roles: connects screens to the session workflows that own each feature.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.olive.app.data.account.AdminAccountDraft
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.SafeContact
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMembershipEvent
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendInviteRepository
import com.olive.app.data.messaging.FriendRelationship
import com.olive.app.data.messaging.FriendRequest
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
        MediaTransferController(application, viewModelScope, it, offlineCache = runtime.offlineMediaCache)
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
    // Signed-in account, privacy, and activity state.
    val message get() = runtime.state.message
    val profile get() = runtime.state.profile
    val attempts get() = runtime.state.attempts
    val settingsHistory get() = runtime.state.settingsHistory
    val privacyHistory get() = runtime.state.privacyHistory
    val triggerHistory get() = runtime.state.triggerHistory
    val privateEntries get() = runtime.state.privateEntries
    val loadingHistory get() = coverAccountId?.let { it == runtime.history.loadingUid } == true
    val historyLoadFailed get() = coverAccountId?.let { it == runtime.history.failedUid } == true
    val viewedSharedEntry get() = runtime.state.viewedSharedEntry
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
     */
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

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
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

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
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)

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
    ) = runtime.notes.savePrivateEntry(
        id, kind, title, body, incidentDate, incidentTime, incidentLocation,
        incidentLatitude, incidentLongitude, attachmentIds,
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
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
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
