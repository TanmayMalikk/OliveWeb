// Immutable state for each session feature. Controllers own updates and Compose observes snapshots.
package com.olive.app.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FailedAttempt
import com.olive.app.data.account.PasskeyRecoveryRepository
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.PrivateContactNote
import com.olive.app.data.news.NewsArticle
import com.olive.app.ui.users.shared.navigation.ActivityCategory
import com.olive.app.ui.users.shared.navigation.CredentialFieldError
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SessionNavigationState(
    val screen: SessionScreen = SessionScreen.Loading,
    val pinChallengeVersion: Int = 0,
    val unlockAnimationVersion: Int = 0,
    val accessibilityExitVersion: Int = 0,
    val page: SafePage = SafePage.Home,
    val activityCategory: ActivityCategory? = null,
    val deviceLockRequest: DeviceLockRequest? = null,
    val idleCountdownSeconds: Int? = null,
    val privacyBlackout: Boolean = false,
    val coverReset: Int = 0,
)

internal data class SessionAccountState(
    val busy: Boolean = false,
    val message: String? = null,
    val profile: UserProfile? = null,
    val pendingSync: Boolean = false,
    val deliveryAddress: DeliveryAddress = DeliveryAddress(),
    val language: String = "English",
    val coverDob: String = "",
    val coverFullName: String = "",
    val coverUsername: String = "",
    val coverType: CoverType = CoverType.FITNESS,
    val accountRole: AccountRole = AccountRole.USER,
    val credentialFieldError: CredentialFieldError? = null,
    val creatingAccount: Boolean = false,
    val passkeyCredentialRequest: PasskeyCredentialRequest? = null,
    val passkeyRecoveryCredentials: List<PasskeyRecoveryRepository.Credential>? = null,
    val passwordRecoveryEnrolled: Boolean? = null,
)

internal data class SessionActivityState(
    val attempts: List<FailedAttempt> = emptyList<FailedAttempt>(),
    val settingsHistory: List<SettingsEvent> = emptyList<SettingsEvent>(),
    val privacyHistory: List<SettingsEvent> = emptyList<SettingsEvent>(),
    val triggerHistory: List<TriggerEvent> = emptyList<TriggerEvent>(),
    val privateEntries: List<PrivateEntry> = emptyList<PrivateEntry>(),
    val viewedSharedEntry: PrivateEntry? = null,
)

internal data class SessionMessagingState(
    val userSearchResults: List<DirectoryUser> = emptyList<DirectoryUser>(),
    val friends: List<Friend> = emptyList<Friend>(),
    val incomingFriendRequests: List<FriendRequest> = emptyList<FriendRequest>(),
    val outgoingFriendRequests: List<FriendRequest> = emptyList<FriendRequest>(),
    val conversations: List<ChatConversation> = emptyList<ChatConversation>(),
    val archivedConversationIds: Set<String> = emptySet<String>(),
    val restoredClosedConversationIds: Set<String> = emptySet<String>(),
    val privateContactNotes: Map<String, PrivateContactNote> = emptyMap<String, PrivateContactNote>(),
    val readMessageVersion: Int = 0,
    val activeConversation: ChatConversation? = null,
    val chatQuickExitHintVisible: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList<ChatMessage>(),
    val messagingBusy: Boolean = false,
    val messagingNeedsReconnect: Boolean = false,
    val conversationLoadFailed: Boolean = false,
)

internal data class SessionDashboardState(
    val adminCounts: AccountCounts = AccountCounts(),
    val adminSummaryOwner: String? = null,
    val adminAccounts: List<AdminAccountSummary> = emptyList<AdminAccountSummary>(),
    val savedNews: List<NewsArticle> = emptyList(),
    val coverDataVersion: Int = 0,
)

// Preserve synchronous controller reads while exposing read-only StateFlows to new UI consumers.
internal class SessionState(private val canEnterSafe: () -> Boolean) {
    private val navigationFlow = MutableStateFlow(SessionNavigationState())
    val navigationState = navigationFlow.asStateFlow()
    private var navigationSnapshot by mutableStateOf(navigationFlow.value)

    private fun updateNavigation(transform: (SessionNavigationState) -> SessionNavigationState) {
        val next = transform(navigationSnapshot)
        navigationSnapshot = next
        navigationFlow.value = next
    }

    var screen: SessionScreen
        get() = navigationSnapshot.screen
        set(value) = updateNavigation { it.copy(screen = if (value == SessionScreen.Safe && !canEnterSafe()) SessionScreen.Cover else value) }
    var pinChallengeVersion: Int
        get() = navigationSnapshot.pinChallengeVersion
        set(value) = updateNavigation { it.copy(pinChallengeVersion = value) }
    var unlockAnimationVersion: Int
        get() = navigationSnapshot.unlockAnimationVersion
        set(value) = updateNavigation { it.copy(unlockAnimationVersion = value) }
    var accessibilityExitVersion: Int
        get() = navigationSnapshot.accessibilityExitVersion
        set(value) = updateNavigation { it.copy(accessibilityExitVersion = value) }
    var page: SafePage
        get() = navigationSnapshot.page
        set(value) = updateNavigation { it.copy(page = value) }
    var activityCategory: ActivityCategory?
        get() = navigationSnapshot.activityCategory
        set(value) = updateNavigation { it.copy(activityCategory = value) }
    var deviceLockRequest: DeviceLockRequest?
        get() = navigationSnapshot.deviceLockRequest
        set(value) = updateNavigation { it.copy(deviceLockRequest = value) }
    var idleCountdownSeconds: Int?
        get() = navigationSnapshot.idleCountdownSeconds
        set(value) = updateNavigation { it.copy(idleCountdownSeconds = value) }
    var privacyBlackout: Boolean
        get() = navigationSnapshot.privacyBlackout
        set(value) = updateNavigation { it.copy(privacyBlackout = value) }
    var coverReset: Int
        get() = navigationSnapshot.coverReset
        set(value) = updateNavigation { it.copy(coverReset = value) }

    private val accountFlow = MutableStateFlow(SessionAccountState())
    private var accountSnapshot by mutableStateOf(accountFlow.value)

    private fun updateAccount(transform: (SessionAccountState) -> SessionAccountState) {
        val next = transform(accountSnapshot)
        accountSnapshot = next
        accountFlow.value = next
    }

    var busy: Boolean
        get() = accountSnapshot.busy
        set(value) = updateAccount { it.copy(busy = value) }
    var message: String?
        get() = accountSnapshot.message
        set(value) = updateAccount { it.copy(message = value) }
    var profile: UserProfile?
        get() = accountSnapshot.profile
        set(value) = updateAccount { it.copy(profile = value) }
    var pendingSync: Boolean
        get() = accountSnapshot.pendingSync
        set(value) = updateAccount { it.copy(pendingSync = value) }
    var deliveryAddress: DeliveryAddress
        get() = accountSnapshot.deliveryAddress
        set(value) = updateAccount { it.copy(deliveryAddress = value) }
    var language: String
        get() = accountSnapshot.language
        set(value) = updateAccount { it.copy(language = value) }
    var coverDob: String
        get() = accountSnapshot.coverDob
        set(value) = updateAccount { it.copy(coverDob = value) }
    var coverFullName: String
        get() = accountSnapshot.coverFullName
        set(value) = updateAccount { it.copy(coverFullName = value) }
    var coverUsername: String
        get() = accountSnapshot.coverUsername
        set(value) = updateAccount { it.copy(coverUsername = value) }
    var coverType: CoverType
        get() = accountSnapshot.coverType
        set(value) = updateAccount { it.copy(coverType = value) }
    var accountRole: AccountRole
        get() = accountSnapshot.accountRole
        set(value) = updateAccount { it.copy(accountRole = value) }
    var credentialFieldError: CredentialFieldError?
        get() = accountSnapshot.credentialFieldError
        set(value) = updateAccount { it.copy(credentialFieldError = value) }
    var creatingAccount: Boolean
        get() = accountSnapshot.creatingAccount
        set(value) = updateAccount { it.copy(creatingAccount = value) }
    var passkeyCredentialRequest: PasskeyCredentialRequest?
        get() = accountSnapshot.passkeyCredentialRequest
        set(value) = updateAccount { it.copy(passkeyCredentialRequest = value) }
    var passkeyRecoveryCredentials: List<PasskeyRecoveryRepository.Credential>?
        get() = accountSnapshot.passkeyRecoveryCredentials
        set(value) = updateAccount { it.copy(passkeyRecoveryCredentials = value) }
    var passwordRecoveryEnrolled: Boolean?
        get() = accountSnapshot.passwordRecoveryEnrolled
        set(value) = updateAccount { it.copy(passwordRecoveryEnrolled = value) }

    private val activityFlow = MutableStateFlow(SessionActivityState())
    private var activitySnapshot by mutableStateOf(activityFlow.value)

    private fun updateActivity(transform: (SessionActivityState) -> SessionActivityState) {
        val next = transform(activitySnapshot)
        activitySnapshot = next
        activityFlow.value = next
    }

    var attempts: List<FailedAttempt>
        get() = activitySnapshot.attempts
        set(value) = updateActivity { it.copy(attempts = value) }
    var settingsHistory: List<SettingsEvent>
        get() = activitySnapshot.settingsHistory
        set(value) = updateActivity { it.copy(settingsHistory = value) }
    var privacyHistory: List<SettingsEvent>
        get() = activitySnapshot.privacyHistory
        set(value) = updateActivity { it.copy(privacyHistory = value) }
    var triggerHistory: List<TriggerEvent>
        get() = activitySnapshot.triggerHistory
        set(value) = updateActivity { it.copy(triggerHistory = value) }
    var privateEntries: List<PrivateEntry>
        get() = activitySnapshot.privateEntries
        set(value) = updateActivity { it.copy(privateEntries = value) }
    var viewedSharedEntry: PrivateEntry?
        get() = activitySnapshot.viewedSharedEntry
        set(value) = updateActivity { it.copy(viewedSharedEntry = value) }

    private val messagingFlow = MutableStateFlow(SessionMessagingState())
    val messagingState = messagingFlow.asStateFlow()
    private var messagingSnapshot by mutableStateOf(messagingFlow.value)

    private fun updateMessaging(transform: (SessionMessagingState) -> SessionMessagingState) {
        val next = transform(messagingSnapshot)
        messagingSnapshot = next
        messagingFlow.value = next
    }

    var userSearchResults: List<DirectoryUser>
        get() = messagingSnapshot.userSearchResults
        set(value) = updateMessaging { it.copy(userSearchResults = value) }
    var friends: List<Friend>
        get() = messagingSnapshot.friends
        set(value) = updateMessaging { it.copy(friends = value) }
    var incomingFriendRequests: List<FriendRequest>
        get() = messagingSnapshot.incomingFriendRequests
        set(value) = updateMessaging { it.copy(incomingFriendRequests = value) }
    var outgoingFriendRequests: List<FriendRequest>
        get() = messagingSnapshot.outgoingFriendRequests
        set(value) = updateMessaging { it.copy(outgoingFriendRequests = value) }
    var conversations: List<ChatConversation>
        get() = messagingSnapshot.conversations
        set(value) = updateMessaging { it.copy(conversations = value) }
    var archivedConversationIds: Set<String>
        get() = messagingSnapshot.archivedConversationIds
        set(value) = updateMessaging { it.copy(archivedConversationIds = value) }
    var restoredClosedConversationIds: Set<String>
        get() = messagingSnapshot.restoredClosedConversationIds
        set(value) = updateMessaging { it.copy(restoredClosedConversationIds = value) }
    var privateContactNotes: Map<String, PrivateContactNote>
        get() = messagingSnapshot.privateContactNotes
        set(value) = updateMessaging { it.copy(privateContactNotes = value) }
    var readMessageVersion: Int
        get() = messagingSnapshot.readMessageVersion
        set(value) = updateMessaging { it.copy(readMessageVersion = value) }
    var activeConversation: ChatConversation?
        get() = messagingSnapshot.activeConversation
        set(value) = updateMessaging { it.copy(activeConversation = value) }
    var chatQuickExitHintVisible: Boolean
        get() = messagingSnapshot.chatQuickExitHintVisible
        set(value) = updateMessaging { it.copy(chatQuickExitHintVisible = value) }
    var chatMessages: List<ChatMessage>
        get() = messagingSnapshot.chatMessages
        set(value) = updateMessaging { it.copy(chatMessages = value) }
    var messagingBusy: Boolean
        get() = messagingSnapshot.messagingBusy
        set(value) = updateMessaging { it.copy(messagingBusy = value) }
    var messagingNeedsReconnect: Boolean
        get() = messagingSnapshot.messagingNeedsReconnect
        set(value) = updateMessaging { it.copy(messagingNeedsReconnect = value) }
    var conversationLoadFailed: Boolean
        get() = messagingSnapshot.conversationLoadFailed
        set(value) = updateMessaging { it.copy(conversationLoadFailed = value) }

    private val dashboardFlow = MutableStateFlow(SessionDashboardState())
    val dashboardState = dashboardFlow.asStateFlow()
    private var dashboardSnapshot by mutableStateOf(dashboardFlow.value)

    private fun updateDashboard(transform: (SessionDashboardState) -> SessionDashboardState) {
        val next = transform(dashboardSnapshot)
        dashboardSnapshot = next
        dashboardFlow.value = next
    }

    var adminCounts: AccountCounts
        get() = dashboardSnapshot.adminCounts
        set(value) = updateDashboard { it.copy(adminCounts = value) }

    fun setAdminSummary(ownerUid: String, counts: AccountCounts) =
        updateDashboard { it.copy(adminSummaryOwner = ownerUid, adminCounts = counts) }
    var adminAccounts: List<AdminAccountSummary>
        get() = dashboardSnapshot.adminAccounts
        set(value) = updateDashboard { it.copy(adminAccounts = value) }
    var savedNews: List<NewsArticle>
        get() = dashboardSnapshot.savedNews
        set(value) = updateDashboard { it.copy(savedNews = value) }
    var coverDataVersion: Int
        get() = dashboardSnapshot.coverDataVersion
        set(value) = updateDashboard { it.copy(coverDataVersion = value) }

    fun clearMessaging(clearRows: Boolean) = updateMessaging { state ->
        val closed = state.copy(
            activeConversation = null,
            chatMessages = emptyList(),
            conversationLoadFailed = false,
            messagingBusy = false,
            messagingNeedsReconnect = false,
        )
        if (clearRows) closed.copy(
            userSearchResults = emptyList(),
            friends = emptyList(),
            incomingFriendRequests = emptyList(),
            outgoingFriendRequests = emptyList(),
            conversations = emptyList(),
            privateContactNotes = emptyMap(),
            archivedConversationIds = emptySet(),
            restoredClosedConversationIds = emptySet(),
        ) else closed
    }
}
