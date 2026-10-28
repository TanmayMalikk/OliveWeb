// Signed-in users: holds the active account and screen state.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.R
import com.olive.app.security.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

// Shares dependencies, account identity, jobs and cancellation epoch across feature controllers.
internal class SessionRuntime(
    val application: Application,
    val scope: CoroutineScope,
    dataSourcesOverride: SessionDataSources? = null,
) {
    val state: SessionState = SessionState { canEnterSafe() }
    val localMode = !BuildConfig.FIREBASE_CONFIGURED
    private val localStorage = SessionLocalStorage(application, localMode)
    val vault get() = localStorage.vault
    val activityVault get() = localStorage.activityVault

    fun phoneSecure() =
        application.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure

    var deviceSecure by mutableStateOf(phoneSecure())
    val store get() = localStorage.deviceStore
    val coverStore get() = localStorage.coverStore
    val coverSaveMutex get() = localStorage.coverSaveMutex
    val foodRevisions get() = localStorage.foodRevisions
    val newsRevisions get() = localStorage.newsRevisions
    val messageReadState get() = localStorage.messageReadState
    val cloudState get() = localStorage.cloudState
    val dataSources = dataSourcesOverride ?: SessionDataSources.create(application, localMode, vault)
    val accounts get() = dataSources.accounts
    val adminRepository get() = dataSources.admin
    val messagingRepository get() = dataSources.messaging
    val secureChatRepository get() = dataSources.secureChat
    val secureDocumentRepository get() = dataSources.secureDocument
    val mediaRepository get() = dataSources.media
    val safetyDocumentRepository get() = dataSources.safetyDocuments
    val locationSharingRepository get() = dataSources.locations
    var enrollment by mutableStateOf<DeviceEnrollment?>(null)
    var pendingIdentity: AccountIdentity? = null
    var pendingProfile: UserProfile? = null
    var operationJob: Job? = null
    var triggerJob: Job? = null
    var historySyncJob: Job? = null
    var idleJob: Job? = null
    var socialSubscription: ChatSubscription? = null
    var conversationSubscription: ChatSubscription? = null
    var messageSubscription: ChatSubscription? = null
    var observedFriendshipIds = emptySet<String>()
    var chatQuickExitHintShown = false
    var verifiedMessagingIdentityMarker: String? = null
    var lastTouchAt = 0L
    val historySyncMutex = Mutex()
    var activeTriggerId: String? = null
    var epoch = 0L
    var pinAuthorizedUid: String? = null
    var pinAuthorizedEpoch = -1L
    val screenHistory = mutableListOf<SessionScreen>()
    val safePageHistory = mutableListOf<SafePage>()

    val accountSignIn = SessionAccountSignIn(this)
    val accountSession = AccountSessionController(this)
    val access = SessionAccess(this)
    val entrySettings = SessionEntrySettings(this)
    val deviceAccess = SessionDeviceAccess(this)
    val navigation = SessionNavigation(this)
    val organization = OrganizationAccounts(this)
    val messaging = SessionMessaging(this)
    val friendActions = FriendActions(this)
    val conversationActions = SessionConversations(this)
    val secureSharing = SessionSecureSharing(this)
    val privacy = SessionPrivacy(this)
    val covers = SessionCoverState(this)
    val history = SessionHistory(this)
    val notes = SessionNotes(this)
    val safety = SessionSafetyPlans(this)
    val staffSupport = StaffSupportController(this)
    val settings = SessionSettings(this)
    val operations = SessionOperations(this)

    val configured get() = enrollment != null
    val coverAccountId get() = enrollment?.uid
    val sensitive get() = state.screen != SessionScreen.Cover || state.checkoutActive
    val setupUsername get() = pendingIdentity?.username.orEmpty()
    val setupProfile get() = pendingProfile
    val usesCover get() = state.accountRole == AccountRole.USER
    val unreadMessageCount: Int
        get() {
            state.readMessageVersion
            val uid = enrollment?.uid ?: return 0
            return state.conversations.count { conversation ->
                conversation.lastMessageId.isNotBlank() && conversation.lastSenderUid.isNotBlank() &&
                        conversation.lastSenderUid != uid &&
                        messageReadState.getString(
                            conversationActions.readMessageKey(
                                uid,
                                conversation.id
                            ), null
                        ) != conversation.lastMessageId
            }
        }
    val pendingMessageActionCount get() = state.incomingFriendRequests.size + unreadMessageCount

    // User access requires a verified app PIN or Android authentication for this enrollment and epoch.
    private fun canEnterSafe(): Boolean {
        val device = enrollment
        val requiresPin = device?.role == AccountRole.USER || state.accountRole == AccountRole.USER
        return !requiresPin || (device != null && pinAuthorizedUid == device.uid && pinAuthorizedEpoch == epoch)
    }

}

// Uses a 25-second timeout by default; Android authentication gets longer. Epoch/job checks reject canceled work.
internal class SessionOperations(private val runtime: SessionRuntime) {
    fun runOperation(failure: String, timeoutMillis: Long = 25_000, block: suspend (Long) -> Unit) {
        with(runtime) {
            with(state) {
                val token = epoch
                busy = true
                message = null
                operationJob = scope.launch {
                    val runningJob = coroutineContext.job
                    var finalFailure: String? = null
                    try {
                        withTimeout(timeoutMillis.milliseconds) { block(token) }
                    } catch (_: TimeoutCancellationException) {
                        if (epoch == token) finalFailure =
                            application.getString(R.string.the_operation_timed_out_check_your_connection_and_retry_your_account_may_already_exist)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (epoch == token) finalFailure = failure
                    } finally {
                        if (operationJob === runningJob) {
                            operationJob = null
                            busy = false
                            finalFailure?.let { message = it }
                        }
                    }
                }
            }
        }
    }
}

// Observable screen state exposed through SessionViewModel; the screen setter enforces the verified User entry gate.
internal class SessionState(private val canEnterSafe: () -> Boolean) {
    private var screenState by mutableStateOf(SessionScreen.Loading)
    var screen: SessionScreen
        get() = screenState
        set(value) {
            screenState =
                if (value == SessionScreen.Safe && !canEnterSafe()) SessionScreen.Cover else value
        }
    // Counts changes to PIN challenge.
    var pinChallengeVersion by mutableIntStateOf(0)
    // Counts successful unlocks so the UI can run the safe-area reveal once.
    var unlockAnimationVersion by mutableIntStateOf(0)
    // Counts changes to accessibility exit.
    var accessibilityExitVersion by mutableIntStateOf(0)
    var page by mutableStateOf(SafePage.Home)
    var activityCategory by mutableStateOf<ActivityCategory?>(null)
    var busy by mutableStateOf(false)
    var deviceLockRequest by mutableStateOf<DeviceLockRequest?>(null)
    var message by mutableStateOf<String?>(null)
    var profile by mutableStateOf<UserProfile?>(null)
    var attempts by mutableStateOf(emptyList<FailedAttempt>())
    var settingsHistory by mutableStateOf(emptyList<SettingsEvent>())
    var privacyHistory by mutableStateOf(emptyList<SettingsEvent>())
    var triggerHistory by mutableStateOf(emptyList<TriggerEvent>())
    var checkoutHistory by mutableStateOf(emptyList<CheckoutEvent>())
    var privateEntries by mutableStateOf(emptyList<PrivateEntry>())
    var viewedSharedEntry by mutableStateOf<PrivateEntry?>(null)
    var userSearchResults by mutableStateOf(emptyList<DirectoryUser>())
    var friends by mutableStateOf(emptyList<Friend>())
    var incomingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var outgoingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var conversations by mutableStateOf(emptyList<ChatConversation>())
    var archivedConversationIds by mutableStateOf(emptySet<String>())
    var privateContactNotes by mutableStateOf(emptyMap<String, PrivateContactNote>())
    // Counts changes to read message.
    var readMessageVersion by mutableIntStateOf(0)
    var activeConversation by mutableStateOf<ChatConversation?>(null)
    var chatQuickExitHintVisible by mutableStateOf(false)
    var chatMessages by mutableStateOf(emptyList<ChatMessage>())
    var messagingBusy by mutableStateOf(false)
    var messagingNeedsReconnect by mutableStateOf(false)
    var conversationLoadFailed by mutableStateOf(false)
    var pendingSync by mutableStateOf(false)
    var deliveryAddress by mutableStateOf(DeliveryAddress())
    var addressPrompt by mutableStateOf(false)
    // Counts changes to address save.
    var addressSaveVersion by mutableIntStateOf(0)
    var language by mutableStateOf("English")
    var coverDob by mutableStateOf("")
    var coverFullName by mutableStateOf("")
    var coverUsername by mutableStateOf("")
    var coverType by mutableStateOf(CoverType.FOOD)
    var accountRole by mutableStateOf(AccountRole.USER)
    var adminCounts by mutableStateOf(AccountCounts())
    var adminAccounts by mutableStateOf(emptyList<AdminAccountSummary>())
    var foodCart by mutableStateOf<Map<String, Int>>(emptyMap())
    var foodFavorites by mutableStateOf<Set<String>>(emptySet())
    var foodCustomizations by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var savedNews by mutableStateOf<List<NewsArticle>>(emptyList())
    // Counts changes to cover data.
    var coverDataVersion by mutableIntStateOf(0)
    var checkoutActive by mutableStateOf(false)
    var idleCountdownSeconds by mutableStateOf<Int?>(null)
    var privacyBlackout by mutableStateOf(false)
    var credentialFieldError by mutableStateOf<CredentialFieldError?>(null)
    var creatingAccount by mutableStateOf(false)
    var coverReset by mutableIntStateOf(0)
}
