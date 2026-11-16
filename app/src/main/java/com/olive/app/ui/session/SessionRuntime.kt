// Composes account-scoped dependencies and feature controllers.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.olive.app.ui.users.shared.navigation.*
import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.security.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import com.olive.app.data.sync.PrioritySyncCoordinator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope


// Signed-in users: holds the active account and screen state.

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

    // Checks whether Android has a PIN, pattern, password, or biometric lock.
    fun phoneSecure() =
        application.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure

    var deviceSecure by mutableStateOf(phoneSecure())
    val store get() = localStorage.deviceStore
    val coverStore get() = localStorage.coverStore
    val coverSaveMutex get() = localStorage.coverSaveMutex
    val newsRevisions get() = localStorage.newsRevisions
    val messageReadState get() = localStorage.messageReadState
    val cloudState get() = localStorage.cloudState
    val messageOutbox get() = localStorage.messageOutbox
    val chatCache get() = localStorage.chatCache
    val documentIndex get() = localStorage.documentIndex
    val offlineMediaCache get() = localStorage.offlineMediaCache
    val prioritySync = PrioritySyncCoordinator(application)
    val dataSources = dataSourcesOverride ?: SessionDataSources.create(
        application,
        localMode,
        vault,
        documentIndex,
    )
    val accounts get() = dataSources.accounts
    val adminRepository get() = dataSources.admin
    val messagingRepository get() = dataSources.messaging
    val secureChatRepository get() = dataSources.secureChat
    val secureDocumentRepository get() = dataSources.secureDocument
    val mediaRepository get() = dataSources.media
    val safetyDocumentRepository get() = dataSources.safetyDocuments
    val locationSharingRepository get() = dataSources.locations
    private var activeEnrollment by mutableStateOf<DeviceEnrollment?>(null)
    var enrollment: DeviceEnrollment?
        get() = activeEnrollment
        set(value) {
            if (activeEnrollment?.uid != value?.uid) messaging.stopMessaging()
            activeEnrollment = value
        }
    var pendingIdentity: AccountIdentity? = null
    var pendingProfile: UserProfile? = null
    var operationJob: Job? = null
    var triggerJob: Job? = null
    var historySyncJob: Job? = null
    var idleJob: Job? = null
    var idlePausedForCall = false
    var socialSubscription: ChatSubscription? = null
    var accountSyncSubscription: ChatSubscription? = null
    var conversationSubscription: ChatSubscription? = null
    var messageSubscription: ChatSubscription? = null
    var messageOutboxObservation: Job? = null
    var remoteChatMessages = emptyList<ChatMessage>()
    var activeRoomKey: javax.crypto.SecretKey? = null
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
    val sensitive get() = state.screen != SessionScreen.Cover
    val setupUsername get() = pendingIdentity?.username.orEmpty()
    val setupProfile get() = pendingProfile
    val usesCover get() = state.accountRole == AccountRole.USER
    fun isConversationUnread(conversation: ChatConversation): Boolean {
        state.readMessageVersion
        val uid = enrollment?.uid ?: return false
        return uid in conversation.participantIds && conversation.lastMessageId.isNotBlank() &&
                conversation.lastSenderUid.isNotBlank() &&
                conversation.lastSenderUid != uid &&
                messageReadState.getString(
                    conversationActions.readMessageKey(uid, conversation.id),
                    null,
                ) != conversation.lastMessageId
    }
    fun isActiveUnreadConversation(conversation: ChatConversation) =
        conversation.closedAt == 0L && conversation.id !in state.archivedConversationIds &&
            isConversationUnread(conversation)

    val incomingFriendRequestCount: Int
        get() {
            val account = enrollment ?: return 0
            if (account.role == AccountRole.STAFF || account.role == AccountRole.ADMIN) return 0
            return state.incomingFriendRequests.count { it.recipientUid == account.uid }
        }

    val pendingMessageActionCount
        get() = incomingFriendRequestCount + state.conversations.count(::isActiveUnreadConversation)

    // User access requires a verified app PIN or Android authentication for this enrollment and epoch.
    private fun canEnterSafe(): Boolean {
        val device = enrollment
        val requiresPin = device?.role == AccountRole.USER || state.accountRole == AccountRole.USER
        return !requiresPin || (device != null && pinAuthorizedUid == device.uid && pinAuthorizedEpoch == epoch)
    }

}

// Uses a 25-second timeout by default; Android authentication gets longer. Epoch/job checks reject canceled work.
internal class SessionOperations(private val runtime: SessionRuntime) {
    // Runs one session task with a timeout and a readable error message.
    fun runOperation(failure: String, timeoutMillis: Long = 25_000, block: suspend (Long) -> Unit) {
        with(runtime) {
            with(state) {
                val token = epoch
                busy = true
                message = null
                operationJob = scope.launch {
                    val runningJob = coroutineContext.job
                    var finalFailure: String? = null
                    // Turn an unexpected task failure into the message prepared by its feature.
                    try {
                        withTimeout(timeoutMillis.milliseconds) { block(token) }
                    } catch (_: TimeoutCancellationException) {
                        if (epoch == token) finalFailure =
                            application.getString(R.string.the_operation_timed_out_check_your_connection_and_retry_your_account_may_already_exist)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { // Convert unexpected operation failures into the feature's prepared user-facing error.
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
