package com.olive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olive.app.data.*
import com.olive.app.ui.session.SessionRuntime

// Android lifecycle adapter. Feature controllers own workflows; screens observe read-only state.
class SessionViewModel(application:Application):AndroidViewModel(application) {
    private val runtime=SessionRuntime(application,viewModelScope)
    val screen get()=runtime.state.screen
    val pinChallengeVersion get()=runtime.state.pinChallengeVersion
    val accessibilityExitVersion get()=runtime.state.accessibilityExitVersion
    val page get()=runtime.state.page
    val activityCategory get()=runtime.state.activityCategory
    val busy get()=runtime.state.busy
    val message get()=runtime.state.message
    val profile get()=runtime.state.profile
    val attempts get()=runtime.state.attempts
    val settingsHistory get()=runtime.state.settingsHistory
    val privacyHistory get()=runtime.state.privacyHistory
    val triggerHistory get()=runtime.state.triggerHistory
    val checkoutHistory get()=runtime.state.checkoutHistory
    val privateEntries get()=runtime.state.privateEntries
    val userSearchResults get()=runtime.state.userSearchResults
    val friends get()=runtime.state.friends
    val incomingFriendRequests get()=runtime.state.incomingFriendRequests
    val outgoingFriendRequests get()=runtime.state.outgoingFriendRequests
    val conversations get()=runtime.state.conversations
    val activeConversation get()=runtime.state.activeConversation
    val chatQuickExitHintVisible get()=runtime.state.chatQuickExitHintVisible
    val chatMessages get()=runtime.state.chatMessages
    val messagingBusy get()=runtime.state.messagingBusy
    val messagingNeedsReconnect get()=runtime.state.messagingNeedsReconnect
    val pendingSync get()=runtime.state.pendingSync
    val deliveryAddress get()=runtime.state.deliveryAddress
    val addressPrompt get()=runtime.state.addressPrompt
    val addressSaveVersion get()=runtime.state.addressSaveVersion
    val language get()=runtime.state.language
    val coverFullName get()=runtime.state.coverFullName
    val coverUsername get()=runtime.state.coverUsername
    val coverType get()=runtime.state.coverType
    val accountRole get()=runtime.state.accountRole
    val adminCounts get()=runtime.state.adminCounts
    val adminAccounts get()=runtime.state.adminAccounts
    val foodCart get()=runtime.state.foodCart
    val foodFavorites get()=runtime.state.foodFavorites
    val foodCustomizations get()=runtime.state.foodCustomizations
    val savedNews get()=runtime.state.savedNews
    val coverDataVersion get()=runtime.state.coverDataVersion
    var checkoutActive: Boolean
        get()=runtime.state.checkoutActive
        set(value) { runtime.state.checkoutActive=value }
    val idleCountdownSeconds get()=runtime.state.idleCountdownSeconds
    val privacyBlackout get()=runtime.state.privacyBlackout
    val credentialFieldError get()=runtime.state.credentialFieldError
    val creatingAccount get()=runtime.state.creatingAccount
    val coverReset get()=runtime.state.coverReset
    val localMode get()=runtime.localMode
    val configured get()=runtime.configured
    val coverAccountId get()=runtime.coverAccountId
    val sensitive get()=runtime.sensitive
    val setupUsername get()=runtime.setupUsername
    val setupProfile get()=runtime.setupProfile
    val usesCover get()=runtime.usesCover
    val unreadMessageCount get()=runtime.unreadMessageCount
    val pendingMessageActionCount get()=runtime.pendingMessageActionCount

    init { runtime.auth.load() }

    fun load() = runtime.auth.load()
    fun selectRole(role: AccountRole) = runtime.auth.selectRole(role)
    fun chooseNew() = runtime.auth.chooseNew()
    fun chooseReturning() = runtime.auth.chooseReturning()
    fun setup() = runtime.auth.setup()
    fun error(text: String) = runtime.auth.error(text)
    fun clearCredentialError() = runtime.auth.clearCredentialError()
    fun authenticate(username: String, password: String, create: Boolean) = runtime.auth.authenticate(username,password,create)
    fun saveSetupProfile(value: UserProfile) = runtime.auth.saveSetupProfile(value)
    fun createDevicePin(pin: String, confirmation: String) = runtime.auth.createDevicePin(pin,confirmation)
    fun interceptSearch(value: String) : Boolean = runtime.access.interceptSearch(value)
    fun submitPin(pin: String,challenge:Int=pinChallengeVersion) = runtime.access.submitPin(pin,challenge)
    fun submitCalculatorPin(pin: String) = runtime.access.submitCalculatorPin(pin)
    fun quickExit() = runtime.navigation.quickExit()
    fun background() = runtime.navigation.background()
    fun back() = runtime.navigation.back()
    fun saveProvisionedTrigger(trigger:String) = runtime.auth.saveProvisionedTrigger(trigger)
    fun saveOrganizationProfile(fullName:String,dob:String,email:String,phone:String) = runtime.organization.saveOrganizationProfile(fullName,dob,email,phone)
    fun updateOrganizationProfile(fullName:String,dob:String,email:String,phone:String) = runtime.organization.updateOrganizationProfile(fullName,dob,email,phone)
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    fun createManagedAccount(value:AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    fun deleteManagedAccount(value:AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)
    fun navigate(value: SafePage) = runtime.navigation.navigate(value)
    fun ensureMessagingObservation(showFeedback: Boolean=false) = runtime.messaging.ensureMessagingObservation(showFeedback)
    fun reconnectMessaging(password: String, onConnected: ()->Unit = {}) = runtime.messaging.reconnectMessaging(password,onConnected)
    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    fun relationshipFor(uid: String) : FriendRelationship = runtime.friendActions.relationshipFor(uid)
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)
    fun openConversation(conversation: ChatConversation) = runtime.conversationActions.openConversation(conversation)
    fun closeConversation() = runtime.conversationActions.closeConversation()
    fun sendMessage(text: String, onSent: ()->Unit = {}) = runtime.conversationActions.sendMessage(text,onSent)
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    fun noteInteraction() = runtime.privacy.noteInteraction()
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    fun recordingStopped() = runtime.privacy.recordingStopped()
    fun recordSettingsInteraction(area: String, action: String) = runtime.history.recordSettingsInteraction(area,action)
    fun saveFoodCover(cart: Map<String,Int>, favorites: Set<String>, customizations: Map<String,Set<String>>) = runtime.covers.saveFoodCover(cart,favorites,customizations)
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    fun activityBack() = runtime.history.activityBack()
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) = runtime.notes.savePrivateEntry(id,kind,title,body)
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) = runtime.covers.recordCheckout(foods,totalCents,error)
    fun dismissAddressPrompt() = runtime.covers.dismissAddressPrompt()
    fun saveAddress(address: DeliveryAddress) = runtime.covers.saveAddress(address)
    fun saveSettings(fullName: String, affected: String, abuserName: String, dob: String, selectedLanguage: String, situation: String, address: DeliveryAddress) = runtime.settings.saveSettings(fullName,affected,abuserName,dob,selectedLanguage,situation,address)
    fun saveCoverSettings(fullName: String, selectedLanguage: String) = runtime.settings.saveCoverSettings(fullName,selectedLanguage)
    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger,pin)
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    fun sync() = runtime.settings.sync()
    fun signOut() = runtime.auth.signOut()
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) = runtime.settings.changePassword(currentPassword,newPassword,confirmation)
    fun changePin(currentPin: String, newPin: String, confirmation: String) = runtime.settings.changePin(currentPin,newPin,confirmation)

    override fun onCleared() { runtime.messaging.stopMessaging() }
}
