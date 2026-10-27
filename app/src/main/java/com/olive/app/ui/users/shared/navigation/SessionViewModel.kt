// Contains the session view model code for this part of Olive.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.shared.*


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olive.app.data.*
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.security.SafeEntryMethod
import com.olive.app.security.allowsAppPin
import okhttp3.OkHttpClient

// Android lifecycle adapter. Feature controllers own workflows; screens observe read-only state.
internal class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = SessionRuntime(application, viewModelScope)
    internal val media = if (runtime.localMode) null else MediaTransferController(
        application,
        viewModelScope,
        MediaTransferStore(application),
        MediaRepository(),
        OkHttpClient(),
        PassThroughMediaPreprocessor,
    )
    internal val documents =
        SafetyDocumentsController(application, viewModelScope, runtime.safetyDocumentRepository, media)
    internal val locationMap =
        LocationMapController(application, viewModelScope, runtime.locationSharingRepository)
    val screen get() = runtime.state.screen
    // Counts changes to PIN challenge.
    val pinChallengeVersion get() = runtime.state.pinChallengeVersion
    val unlockAnimationVersion get() = runtime.state.unlockAnimationVersion
    // Counts changes to accessibility exit.
    val accessibilityExitVersion get() = runtime.state.accessibilityExitVersion
    val page get() = runtime.state.page
    val activityCategory get() = runtime.state.activityCategory
    val busy get() = runtime.state.busy
    val deviceLockRequest get() = runtime.state.deviceLockRequest
    val preferredEntryMethod get() = runtime.enrollment?.entryMethod ?: SafeEntryMethod.APP_PIN
    val hasAppPin get() = runtime.enrollment?.hasAppPin == true
    val phoneHasLock get() = runtime.deviceSecure
    val usesDeviceLock get() = preferredEntryMethod == SafeEntryMethod.DEVICE_LOCK && phoneHasLock
    val canUseAppPin get() = runtime.enrollment?.allowsAppPin() == true
    // Refreshes device security.
    fun refreshDeviceSecurity() {
        runtime.deviceSecure = runtime.phoneSecure()
    }

    // Sets safe entry method.
    fun setSafeEntryMethod(method: SafeEntryMethod, currentPin: String) =
        runtime.entrySettings.select(method, currentPin)

    // Saves safe entry PIN.
    fun saveSafeEntryPin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) =
        runtime.entrySettings.savePin(currentPin, pin, confirmation, usePin)

    fun authenticateDeviceLock(enroll: Boolean = false) = runtime.deviceAccess.authenticate(enroll)
    fun changeDeviceLockTrigger(trigger: String) = runtime.deviceAccess.authenticate(false, trigger)
    val message get() = runtime.state.message
    val profile get() = runtime.state.profile
    val attempts get() = runtime.state.attempts
    val settingsHistory get() = runtime.state.settingsHistory
    val privacyHistory get() = runtime.state.privacyHistory
    val triggerHistory get() = runtime.state.triggerHistory
    val checkoutHistory get() = runtime.state.checkoutHistory
    val privateEntries get() = runtime.state.privateEntries
    val viewedSharedEntry get() = runtime.state.viewedSharedEntry
    internal val safetyPlans get() = runtime.safety
    internal val support get() = runtime.support
    val userSearchResults get() = runtime.state.userSearchResults
    val friends get() = runtime.state.friends
    val incomingFriendRequests get() = runtime.state.incomingFriendRequests
    val outgoingFriendRequests get() = runtime.state.outgoingFriendRequests
    val conversations get() = runtime.state.conversations
    val archivedConversationIds get() = runtime.state.archivedConversationIds
    val privateContactNotes get() = runtime.state.privateContactNotes
    val activeConversation get() = runtime.state.activeConversation
    val chatQuickExitHintVisible get() = runtime.state.chatQuickExitHintVisible
    val chatMessages get() = runtime.state.chatMessages
    val messagingBusy get() = runtime.state.messagingBusy
    val messagingNeedsReconnect get() = runtime.state.messagingNeedsReconnect
    val pendingSync get() = runtime.state.pendingSync
    val deliveryAddress get() = runtime.state.deliveryAddress
    val addressPrompt get() = runtime.state.addressPrompt
    // Counts changes to address save.
    val addressSaveVersion get() = runtime.state.addressSaveVersion
    val language get() = runtime.state.language
    val coverDob get() = runtime.state.coverDob
    val coverFullName get() = runtime.state.coverFullName
    val coverUsername get() = runtime.state.coverUsername
    val coverType get() = runtime.state.coverType
    val accountRole get() = runtime.state.accountRole
    val adminCounts get() = runtime.state.adminCounts
    val adminAccounts get() = runtime.state.adminAccounts
    val foodCart get() = runtime.state.foodCart
    val foodFavorites get() = runtime.state.foodFavorites
    val foodCustomizations get() = runtime.state.foodCustomizations
    val savedNews get() = runtime.state.savedNews
    // Counts changes to cover data.
    val coverDataVersion get() = runtime.state.coverDataVersion
    var checkoutActive: Boolean
        get() = runtime.state.checkoutActive
        set(value) {
            runtime.state.checkoutActive = value
        }
    val idleCountdownSeconds get() = runtime.state.idleCountdownSeconds
    val privacyBlackout get() = runtime.state.privacyBlackout
    val credentialFieldError get() = runtime.state.credentialFieldError
    val creatingAccount get() = runtime.state.creatingAccount
    val coverReset get() = runtime.state.coverReset
    val localMode get() = runtime.localMode
    val configured get() = runtime.configured
    val coverAccountId get() = runtime.coverAccountId
    val sensitive get() = runtime.sensitive
    val setupUsername get() = runtime.setupUsername
    val setupProfile get() = runtime.setupProfile
    val usesCover get() = runtime.usesCover
    val pendingMessageActionCount get() = runtime.pendingMessageActionCount

    @Volatile
    var externalFilePickerOpen = false
        private set

    init {
        runtime.auth.load()
    }

    fun load() = runtime.auth.load()
    // Selects role.
    fun selectRole(role: AccountRole) = runtime.auth.selectRole(role)
    fun chooseNew() = runtime.auth.chooseNew()
    fun chooseReturning() = runtime.auth.chooseReturning()
    // Sets up.
    fun setup() = runtime.auth.setup()
    fun error(text: String) = runtime.auth.error(text)
    // Clears credential error.
    fun clearCredentialError() = runtime.auth.clearCredentialError()
    fun authenticate(username: String, password: String, create: Boolean) =
        runtime.auth.authenticate(username, password, create)

    // Saves setup profile.
    fun saveSetupProfile(value: UserProfile) = runtime.auth.saveSetupProfile(value)
    // Creates device PIN.
    fun createDevicePin(pin: String, confirmation: String) =
        runtime.auth.createDevicePin(pin, confirmation)

    fun interceptSearch(value: String): Boolean = runtime.access.interceptSearch(value)
    // Opens the PIN challenge from the discreet olive shown on every cover.
    fun beginDiscreetEntry() = runtime.access.beginDiscreetEntry()
    // Submits PIN.
    fun submitPin(pin: String, challenge: Int = pinChallengeVersion) =
        runtime.access.submitPin(pin, challenge)

    // Submits calculator PIN.
    fun submitCalculatorPin(pin: String) = runtime.access.submitCalculatorPin(pin)
    fun quickExit() = runtime.navigation.quickExit()
    fun background() = runtime.navigation.background()
    fun beginExternalFilePicker() {
        externalFilePickerOpen = true
    }

    fun endExternalFilePicker() {
        externalFilePickerOpen = false
    }

    fun back() = runtime.navigation.back()
    // Saves provisioned trigger.
    fun saveProvisionedTrigger(trigger: String) = runtime.auth.saveProvisionedTrigger(trigger)
    // Saves organization profile.
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    // Updates organization profile.
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    // Refreshes admin accounts.
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    // Creates managed account.
    fun createManagedAccount(value: AdminAccountDraft) =
        runtime.organization.createManagedAccount(value)

    // Deletes managed account.
    fun deleteManagedAccount(value: AdminAccountSummary) =
        runtime.organization.deleteManagedAccount(value)

    fun navigate(value: SafePage) = runtime.navigation.navigate(value)
    // Makes sure messaging observation is ready.
    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    fun relationshipFor(uid: String): FriendRelationship =
        runtime.friendActions.relationshipFor(uid)

    // Sends friend request.
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    fun acceptFriendRequest(request: FriendRequest) =
        runtime.friendActions.acceptFriendRequest(request)

    fun declineFriendRequest(request: FriendRequest) =
        runtime.friendActions.declineFriendRequest(request)

    // Cancels friend request.
    fun cancelFriendRequest(request: FriendRequest) =
        runtime.friendActions.cancelFriendRequest(request)

    // Starts conversation.
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)
    // Opens conversation.
    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    // Closes conversation.
    fun closeConversation() = runtime.conversationActions.closeConversation()
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

    fun appendPrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.appendPrivateNote(target, text, onSaved)
    // Sends message.
    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    fun requestAdminHelp(conversationId: String) = runtime.support.requestAdmin(conversationId)
    fun shareLiveLocationInChat(durationMillis: Long, requestPermission: suspend () -> Boolean) =
        runtime.secureSharing.shareLiveLocation(durationMillis, locationMap, requestPermission)

    // Opens shared private entry.
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    // Closes shared private entry.
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()
    fun noteInteraction() = runtime.privacy.noteInteraction()
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    // Records ing stopped.
    fun recordingStopped() = runtime.privacy.recordingStopped()
    // Records settings interaction.
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    // Saves food cover.
    fun saveFoodCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>
    ) = runtime.covers.saveFoodCover(cart, favorites, customizations)

    // Switches news bookmark on or off.
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)
    // Shows activity category.
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    fun activityBack() = runtime.history.activityBack()
    // Clears private entry status.
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    // Saves private entry.
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) =
        runtime.notes.savePrivateEntry(id, kind, title, body)

    // Switches private entry PIN on or off.
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()
    // Restores private entry.
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
    // Records checkout.
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) =
        runtime.covers.recordCheckout(foods, totalCents, error)

    fun dismissAddressPrompt() = runtime.covers.dismissAddressPrompt()
    // Saves address.
    fun saveAddress(address: DeliveryAddress) = runtime.covers.saveAddress(address)
    // Saves settings.
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

    // Saves cover settings.
    fun saveCoverSettings(
        fullName: String,
        dob: String = coverDob,
        address: DeliveryAddress = deliveryAddress
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    // Syncs local notification handler.
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    // Saves contacts.
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    fun sync() = runtime.settings.sync()
    fun signOut() {
        documents.clear()
        locationMap.clear()
        runtime.auth.signOut()
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)

    // Runs when cleared happens.
    override fun onCleared() {
        documents.clear()
        locationMap.clear()
        runtime.safety.close()
        runtime.messaging.stopMessaging()
    }
}
