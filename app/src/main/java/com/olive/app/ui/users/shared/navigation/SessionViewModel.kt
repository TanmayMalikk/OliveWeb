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
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.PasskeyRecoveryRepository
import com.olive.app.data.account.SafeContact
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMembershipEvent
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRelationship
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.ChatAccessRecoveryResult
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.KeyManager
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.users.shared.organization.AdminLocationMapController
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.ui.users.user.documents.MediaAccessRecoveryResult
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import com.olive.app.ui.users.user.documents.recoverableMediaDocuments
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
    internal val documents =
        SafetyDocumentsController(application, viewModelScope, runtime.safetyDocumentRepository, media)
    internal val encryptedAccessRecovery = EncryptedAccessRecoveryController(
        scope = viewModelScope,
        deviceId = { KeyManager.deviceId() },
        unlock = runtime.settings::unlockAccountKeysetForMedia,
        findChats = { conversations, uid ->
            runtime.secureChatRepository?.accountAccessRecovery?.candidates(conversations, uid).orEmpty()
        },
        restoreMedia = { candidates, uid, keyset ->
            media?.restoreDeviceAccess(candidates, uid, keyset)
                ?: MediaAccessRecoveryResult(emptyList(), candidates.mapTo(mutableSetOf(), SafetyDocument::id), emptySet())
        },
        restoreChats = { conversations, uid, keyset ->
            runtime.secureChatRepository?.accountAccessRecovery?.restore(conversations, uid, keyset)
                ?: ChatAccessRecoveryResult(emptyList(), conversations.mapTo(mutableSetOf(), ChatConversation::id))
        },
        rememberMedia = documents::rememberRecoveredDocuments,
        rememberChat = { uid, access, deviceId, validatedAt ->
            runtime.chatCache.saveAccess(
                ownerUid = uid,
                conversation = access.conversation,
                wrappedRoomKey = access.wrappedRoomKey,
                deviceId = deviceId,
                validatedAt = validatedAt,
            )
        },
    )
    internal val locationMap =
        LocationMapController(application, viewModelScope, runtime.locationSharingRepository)
    internal val adminLocationMap = AdminLocationMapController(runtime.dataSources.adminLocations)

    val preparingVoiceConversationId get() = runtime.secureSharing.preparingVoiceConversationId
    fun sendVoiceMessage(file: java.io.File, metadata: String): Boolean =
        media?.let { runtime.secureSharing.sendVoice(file, metadata, it) } ?: false

    // Root navigation, account entry, and device lock state.
    val navigationState get() = runtime.state.navigationState
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
    val passwordRecoveryEnrolled: Boolean? get() = runtime.state.passwordRecoveryEnrolled
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
    val adminAccounts get() = runtime.state.adminAccounts
    val savedNews get() = runtime.state.savedNews
    val privacyBlackout get() = runtime.state.privacyBlackout
    val encryptedAccessRecoveryState get() = encryptedAccessRecovery.state
    val coverReset get() = runtime.state.coverReset
    val localMode get() = runtime.localMode
    val coverAccountId get() = runtime.coverAccountId
    val sensitive get() = runtime.sensitive
    val usesCover get() = runtime.usesCover

    fun pauseIdleTimerForCall() = runtime.privacy.pauseIdleTimerForCall()

    fun resumeIdleTimerAfterCall() = runtime.privacy.resumeIdleTimerAfterCall()

    /** Evaluates encrypted access only inside an authenticated personal-account session. */
    fun evaluateEncryptedAccess() {
        val uid = coverAccountId ?: return
        if (accountRole != AccountRole.USER || screen != SessionScreen.Safe || privacyBlackout) return
        encryptedAccessRecovery.evaluate(
            ownerUid = uid,
            media = recoverableMediaDocuments(documents.state.files, uid, KeyManager.deviceId()),
            conversations = runtime.state.conversations,
        )
    }

    /** Starts the user-confirmed recovery operation without exposing account key material. */
    fun restoreEncryptedAccess() = encryptedAccessRecovery.restore()

    /** Defers only the currently detected recovery candidates. */
    fun dismissEncryptedAccess() = encryptedAccessRecovery.dismiss()

    /** Clears the localized recovery result after the user acknowledges it. */
    fun clearEncryptedAccessNotice() = encryptedAccessRecovery.clearNotice()

    @Volatile
    var externalFilePickerOpen = false
        private set

    var callPermissionRequestOpen by mutableStateOf(false)
        private set
    var safeSystemActivityOpen = false
        private set
    private var safeSystemLaunchId = 0

    internal fun beginSafeSystemActivity(): Int? {
        if (safeSystemActivityOpen) return null
        safeSystemActivityOpen = true
        return ++safeSystemLaunchId
    }

    internal fun finishSafeSystemActivity(launchId: Int = safeSystemLaunchId) {
        if (launchId == safeSystemLaunchId) safeSystemActivityOpen = false
    }

    internal fun safeSystemActivityResult(view: android.view.View) {
        val launchId = safeSystemLaunchId
        // Some settings activities return a result before their window has even taken focus.
        // Release the exemption only once Olive has its window back.
        view.postDelayed(object : Runnable {
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

    // Uses the same durable reconciliation path after Android returns the app to the foreground.
    fun reconcilePriorityData() {
        runtime.coverAccountId?.let(runtime.prioritySync::request)
    }

    // Opens Android's file picker for a document attachment.
    fun beginExternalFilePicker() {
        externalFilePickerOpen = true
    }

    // Clears the pending file-picker request after Android returns a result.
    fun endExternalFilePicker() {
        externalFilePickerOpen = false
    }

    fun beginCallPermissionRequest() {
        callPermissionRequestOpen = true
    }

    fun endCallPermissionRequest() {
        callPermissionRequestOpen = false
    }

    // Shares the user's current live-location session with a chat.
    fun shareLiveLocationInChat(durationMillis: Long, requestPermission: suspend () -> Boolean) =
        runtime.secureSharing.shareLiveLocation(durationMillis, locationMap, requestPermission)

    fun quickExit(expectedCallId: String? = null): Job {
        val hangup = calls.finish(coverAccountId.orEmpty(), expectedCallId)
        pendingCameraUploadTags = emptySet()
        navigation.quickExit()
        return hangup
    }

    // Closing this session also clears document and map data held by their screen controllers.
    fun signOut() {
        pendingCameraUploadTags = emptySet()
        documents.clear()
        locationMap.clear()
        adminLocationMap.close()
        runtime.accountSession.signOut()
    }

    // Stops account listeners and background work when the session view model is removed.
    override fun onCleared() {
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
    fun isUnread(conversation: ChatConversation) = runtime.isConversationUnread(conversation)
    fun unreadCountForSupportTag(tag: String) = runtime.state.conversations.count { conversation ->
        conversation.type == "SUPPORT" && conversation.supportTag == tag &&
            runtime.isActiveUnreadConversation(conversation)
    }

    fun unreadCountForChatTab(tickets: Boolean): Int =
        runtime.state.conversations.count {
            (it.type == "SUPPORT") == tickets && runtime.isActiveUnreadConversation(it)
        } + if (tickets) 0 else runtime.incomingFriendRequestCount

    // Starts chat listeners when the current screen needs messaging.
    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    // Reconnects chat listeners after a network or account change.
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    // Searches the account directory for an exact username.
    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    // Returns the current friend relationship with this account.
    fun relationshipFor(uid: String): FriendRelationship = runtime.friendActions.relationshipFor(uid)
    // Sends a friend request to the selected account.
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    // Accepts an incoming friend request.
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    // Declines an incoming friend request.
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    // Returns true when the outgoing friend request may still be cancelled.
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    // Opens an existing conversation by its ID.
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)

    // Creates or opens a direct conversation with this account.
    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    internal fun observeMembershipEvents(
        id: String, actorUid: String? = null, onUpdate: (List<ChatMembershipEvent>) -> Unit,
    ) = runtime.messagingRepository?.observeMembershipEvents(id, actorUid, onUpdate)

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

    // Closes the current conversation and returns to the chat list.
    fun closeConversation() = runtime.conversationActions.closeConversation()
    // Archives or restores the selected conversation for this account.
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

    // Saves a private note about this friend or supported user.
    fun appendPrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.appendPrivateNote(target, text, onSaved)

    // Encrypts and sends the typed message to the current conversation.
    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    // Retries the failed encrypted queue head without bypassing message order.
    fun retryMessage(chat: ChatMessage) = runtime.conversationActions.retryMessage(chat)
    fun discardFailedMessage(chat: ChatMessage) = runtime.conversationActions.discardFailedMessage(chat)
    // Un-sends a message when the signed-in account is allowed to remove it.
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    // Dismisses the quick-exit tip shown inside a chat.
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()

    // Encrypts a private note and shares it with the current chat.
    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    // Gives the current chat encrypted access to a safety document.
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    // Shares the safety plan and starts a separate plan-consult ticket.
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    // Opens a private note that was shared in this conversation.
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    // Closes the shared-note viewer and releases its live listener.
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()

    // Escalates this support ticket by adding an available admin.
    fun requestAdminHelp(conversationId: String) = runtime.staffSupport.requestAdmin(conversationId)
    // Adds an accepted contact to this encrypted conversation without starting a new chat.
    fun addConversationParticipant(conversationId: String, participantUid: String) =
        runtime.staffSupport.addParticipant(conversationId, participantUid)
    // Removes another member when the signed-in account owns this group chat.
    fun removeConversationParticipant(conversationId: String, participantUid: String) =
        runtime.staffSupport.removeParticipant(conversationId, participantUid)
    // Starts a separate staff ticket with every person already in this personal chat.
    fun requestStaffForConversation(conversationId: String, onCreated: (String) -> Unit) =
        runtime.staffSupport.requestStaffForConversation(conversationId, onCreated)
    // Closes the support ticket and archives it for every participant.
    fun closeSupportChat(conversationId: String, planCompleted: Boolean = false, onClosed: () -> Unit = {}) =
        runtime.staffSupport.close(conversationId, planCompleted, onClosed)
}

// Signed-in accounts and admins: connects profile, cover settings, and account management screens.

internal interface SessionAccountManagementActions {
    val runtime: SessionRuntime

    // Saves the staff or admin details entered during account setup.
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    // Updates the signed-in staff or admin profile after setup.
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    // Reloads the account list shown in the admin portal.
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    // Reloads staff workload rows without downloading the full account directory.
    fun refreshAdminStaff() = runtime.organization.refreshAdminStaff()
    // Creates a staff or admin account from the admin form.
    fun createManagedAccount(value: AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    // Deletes a staff or admin account selected in the admin portal.
    fun deleteManagedAccount(value: AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)

    // Saves the user profile and situation details from Safe App settings.
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress,
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

    // Saves the public profile details used by the selected cover app.
    fun saveCoverSettings(
        fullName: String,
        dob: String = runtime.state.coverDob,
        address: DeliveryAddress = runtime.state.deliveryAddress,
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    // Changes which cover app opens before the private Safe App.
    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    // Changes the hidden phrase that opens private entry from a cover app.
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    // Saves the trusted contacts entered during setup or in settings.
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    // Sends local setting changes to the account and downloads newer changes.
    fun sync() = runtime.settings.sync()
    // Restarts account notification listeners after the signed-in account changes.
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    // Checks the current password before replacing it with the new password.
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)
    fun refreshPasskeyRecovery() = runtime.settings.refreshPasskeyRecovery()
    fun enablePasskeyRecovery() = runtime.settings.enablePasskeyRecovery()
    fun removePasskeyRecovery(credentialId: String, password: String) =
        runtime.settings.removePasskeyRecovery(credentialId, password)
    fun refreshPasswordRecovery() = runtime.passwordRecovery.refreshPasswordRecovery()
    fun enablePasswordRecovery(password: String) = runtime.passwordRecovery.enablePasswordRecovery(password)
    fun rotatePasswordRecovery(oldPassword: String, newPassword: String) =
        runtime.passwordRecovery.rotatePasswordRecovery(oldPassword, newPassword)
    fun removePasswordRecovery(password: String) = runtime.passwordRecovery.removePasswordRecovery(password)
    fun unlockWithPasswordRecovery(password: String) = runtime.passwordRecovery.unlockWithPasswordRecovery(password)
}

// Connects cover, private note, activity, and privacy screens to their workflows.
internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    // Passes the private-note button action to the session privacy controller.
    fun noteInteraction() = runtime.privacy.noteInteraction()
    fun idleTimeoutSeconds(): Int = runtime.privacy.idleTimeoutSeconds()
    fun setIdleTimeoutSeconds(seconds: Int) = runtime.privacy.setIdleTimeoutSeconds(seconds)
    // Records that Android detected a screenshot or screen recording.
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    // Records when Android reports that screen recording has stopped.
    fun recordingStopped() = runtime.privacy.recordingStopped()
    // Adds a settings change to the user's private activity history.
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    // Adds or removes an article from the news cover's bookmarks.
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)

    // Opens one category in the private activity-history screen.
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    // Returns from an activity category to the full history list.
    fun activityBack() = runtime.history.activityBack()

    // Clears the note editor's success or error message before the next action.
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    // Creates a private note or updates the note with the matching ID.
    fun savePrivateEntry(
        id: String?, kind: PrivateEntryKind, title: String, body: String,
        incidentDate: String = "", incidentTime: String = "", incidentLocation: String = "",
        incidentLatitude: Double? = null, incidentLongitude: Double? = null,
        attachmentIds: List<String> = emptyList(),
    ) = runtime.notes.savePrivateEntry(
        id, kind, title, body, incidentDate, incidentTime, incidentLocation,
        incidentLatitude, incidentLongitude, attachmentIds,
    )

    // Pins or unpins a private note so important notes stay easy to find.
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    // Moves a private note into the note trash.
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    // Restores a private note from the note trash.
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}

// Opens and closes the app's root screens and safe-app pages.
internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    // Gives navigation screens the same quick-exit action from the current session.
    fun quickExit() = runtime.navigation.quickExit()
    // Sends the app to the background when the user presses Quick Exit.
    fun background() = runtime.navigation.background()
    // Returns to the previous Safe App screen.
    fun back() = runtime.navigation.back()
    // Opens the requested Safe App page from shared navigation.
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}
