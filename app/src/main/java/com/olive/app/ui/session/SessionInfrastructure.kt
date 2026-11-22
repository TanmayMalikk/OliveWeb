// All app roles: builds the local stores, account-scoped data sources, and PIN/device-lock access controllers used by a session.
package com.olive.app.ui.session

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRepository
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminRepository
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.FirebaseAccountRepository
import com.olive.app.data.account.LocalAccountRepository
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.account.UserProfile
import com.olive.app.data.cover.CoverStateStore
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.LocationSharingRepository
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.MessageOutboxStore
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.messaging.SecureChatRepository
import com.olive.app.data.safety.DocumentIndexDataSource
import com.olive.app.data.safety.DocumentIndexStore
import com.olive.app.data.safety.IndexedSafetyDocumentRepository
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.SafetyDocumentRepository
import com.olive.app.data.safety.SecureDocumentRepository
import com.olive.app.data.sync.PrioritySyncCoordinator
import com.olive.app.security.*
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import javax.crypto.Cipher
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Owns the device-only stores. Keeping them together makes their scopes visible at construction.
internal class SessionLocalStorage(application: Application, localMode: Boolean) {
    val vault = EncryptedVault(application, if (localMode) "demo" else "firebase")
    val activityVault = EncryptedVault(
        application,
        if (localMode) "demo-activity" else "firebase-activity",
    )
    val deviceStore = DeviceStore(vault, activityVault)
    val coverStore = CoverStateStore(EncryptedVault(application, "cover"))
    val coverSaveMutex = Mutex()
    val newsRevisions = mutableMapOf<String, Long>()
    val messageReadState: SharedPreferences = application.getSharedPreferences(
        "olive_message_read_state",
        Context.MODE_PRIVATE,
    )
    val cloudState: SharedPreferences = application.getSharedPreferences(
        "olive_cloud_state",
        Context.MODE_PRIVATE,
    )
    val messageOutbox = MessageOutboxStore(application)
    val chatCache = ChatCacheStore(application)
    val documentIndex = DocumentIndexStore(application)
    val offlineMediaCache = OfflineMediaCache(application)
}

// Builds cloud repositories only for a Firebase session. Tests can supply a replacement bundle.
internal class SessionDataSources(
    val accounts: AccountRepository,
    val admin: AdminRepository?,
    val messaging: MessagingRepository?,
    val secureChat: SecureChatRepository?,
    val secureDocument: SecureDocumentRepository?,
    val media: MediaTicketDataSource?,
    val safetyDocuments: SafetyDocumentDataSource?,
    val locations: LocationSharingDataSource?,
    val adminLocations: AdminLocationRepository?,
) {
    companion object {
        /**
         * Creates the cloud repository bundle used by a signed-in Firebase account.
         *
         * @param application Application supplied to [create].
         * @param localMode Whether to apply local mode.
         * @param vault Vault supplied to [create].
         * @param documentIndex Document index supplied to [create].
         * @return The resulting [SessionDataSources] instance.
         */
        fun create(
            application: Application,
            localMode: Boolean,
            vault: EncryptedVault,
            documentIndex: DocumentIndexDataSource,
        ): SessionDataSources {
            val media: MediaTicketDataSource? = if (localMode) null else MediaRepository()
            return SessionDataSources(
                accounts = if (localMode) LocalAccountRepository(vault) else FirebaseAccountRepository(),
                admin = if (localMode) null else AdminRepository(),
                messaging = if (localMode) null else MessagingRepository(),
                secureChat = if (localMode) null else SecureChatRepository(),
                secureDocument = if (localMode) null else SecureDocumentRepository(),
                media = media,
                safetyDocuments = media?.let {
                    IndexedSafetyDocumentRepository(
                        SafetyDocumentRepository(media = it),
                        documentIndex
                    )
                },
                locations = if (localMode) null else LocationSharingRepository(application),
                adminLocations = if (localMode) null else AdminLocationRepository(),
            )
        }
    }
}

// Checks cover triggers and PINs; only a verified result for the current enrollment may authorize the safe area.
internal class SessionAccess(private val runtime: SessionRuntime) {
    /**
     * Detects a valid private-entry search for session access.
     *
     * @param value Input value to store, parse, or format.
     * @return `true` if intercept search, or `false` otherwise.
     */
    fun interceptSearch(value: String): Boolean {
        val device = runtime.enrollment ?: return false
        return openTrigger(device, InputPolicy.matchesTrigger(value, device.triggerHash))
    }

    /**
     * Computes intercept search async.
     *
     * @param value Input value to store, parse, or format.
     * @return `true` if intercept search async, or `false` otherwise.
     */
    suspend fun interceptSearchAsync(value: String): Boolean {
        val device = runtime.enrollment ?: return false
        val match = withContext(Dispatchers.Default) { InputPolicy.matchesTrigger(value, device.triggerHash) }
        return openTrigger(device, match)
    }

    /**
     * Opens trigger.
     *
     * @param device Device supplied to [openTrigger].
     * @param match Whether to apply match.
     * @return `true` if open trigger, or `false` otherwise.
     */
    private fun openTrigger(device: DeviceEnrollment, match: Boolean): Boolean {
        with(runtime) {
            with(state) {
                if (!match || accountRole != AccountRole.USER || enrollment != device) return false
                if (screen == SessionScreen.Cover && !busy) {
                    val event =
                        TriggerEvent(source = if (coverType == CoverType.NEWS) "Daily Digest search" else "Olive search")
                    activeTriggerId = event.id
                    triggerJob = scope.launch(Dispatchers.IO) {
                        store.recordTrigger(device.uid, event)
                        history.queueHistorySync()
                    }
                    pinChallengeVersion++
                    message = null
                    screen = SessionScreen.Pin
                }
                return true
            }
        }
    }

    /**
     * Starts the same locked PIN flow when the user double-taps the discreet olive on a cover.
     */
    fun beginDiscreetEntry() {
        with(runtime) {
            with(state) {
                if (accountRole != AccountRole.USER || screen != SessionScreen.Cover || busy || privacyBlackout) return
                val device = enrollment
                if (device == null) {
                    accountSession.openAccountSetup()
                    return
                }
                if (device.role != AccountRole.USER) return
                val event = TriggerEvent(source = "Cover olive")
                activeTriggerId = event.id
                triggerJob = scope.launch(Dispatchers.IO) {
                    store.recordTrigger(device.uid, event)
                    history.queueHistorySync()
                }
                pinChallengeVersion++
                message = null
                screen = SessionScreen.Pin
            }
        }
    }

    /**
     * Rejects queued clicks from an old PIN screen or while verification/blackout is active.
     *
     * @param pin User-entered PIN string.
     * @param challenge Challenge supplied to [submitPin].
     */
    fun submitPin(pin: String, challenge: Int) {
        with(runtime) {
            with(state) {
                if (busy || privacyBlackout || screen != SessionScreen.Pin || accountRole != AccountRole.USER || challenge != pinChallengeVersion) return
                val device = enrollment ?: return
                if (device.role != AccountRole.USER || activeTriggerId == null || !device.allowsAppPin()) return
                if (!InputPolicy.validPin(pin)) {
                    message = runtime.application.getString(R.string.enter_your_4_6_digit_pin)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_check_the_pin_or_record_this_attempt_access_remains_locked)) { token ->
                    val result = withContext(Dispatchers.IO) {
                        triggerJob?.join()
                        store.verify(device.uid, pin).also { outcome ->
                            activeTriggerId?.let { store.recordTriggerAttempt(device.uid, it, outcome is PinResult.Accepted) }
                            history.queueHistorySync()
                        }
                    }
                    if (epoch != token || screen != SessionScreen.Pin || challenge != pinChallengeVersion) return@runOperation
                    when (result) {
                        PinResult.Accepted -> unlockVerifiedDevice(device, token, SessionScreen.Pin, challenge, usingAppPin = true)
                        is PinResult.Rejected -> if (result.failures >= 5) navigation.quickExit() else {
                            message = runtime.application.resources.getQuantityString(
                                R.plurals.ui_incorrect_pin_value_attempts_remaining,
                                5 - result.failures,
                                5 - result.failures
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Rechecks identity, screen, and cancellation epoch after loading private data.
     *
     * @param device Device supplied to [unlockVerifiedDevice].
     * @param token Authentication, push notification, or session token.
     * @param expectedScreen Expected screen supplied to [unlockVerifiedDevice].
     * @param challenge Optional challenge supplied to [unlockVerifiedDevice].
     * @param usingAppPin Whether to apply using app PIN.
     */
    internal suspend fun unlockVerifiedDevice(
        device: DeviceEnrollment,
        token: Long,
        expectedScreen: SessionScreen,
        challenge: Int? = null,
        usingAppPin: Boolean = false
    ) {
        with(runtime) {
            with(state) {
                if (usingAppPin && !device.allowsAppPin()) return
                val saved = withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                if (epoch != token || screen != expectedScreen || enrollment != device || privacyBlackout ||
                    (challenge != null && challenge != pinChallengeVersion) ||
                    (usingAppPin && !device.allowsAppPin())
                ) return
                pinAuthorizedUid = device.uid
                pinAuthorizedEpoch = token
                busy = false
                profile = saved.first
                attempts = saved.second
                pendingSync = saved.third
                history.refreshHistory(device.uid)
                screenHistory.clear()
                safePageHistory.clear()
                enterSafeWithAnimation()
                privacy.startIdleTimer()
                scope.launch(Dispatchers.IO) {
                    runCatching { dataSources.safetyDocuments?.upgradeOwnedRecoveryKeys(device.uid) }
                }
            }
        }
    }
}

// Holds an active biometric/device-credential unlock request.
internal class DeviceLockRequest internal constructor(
    val challenge: DeviceLockChallenge,
    val settingsChange: Boolean = false
) {
    internal val result = CompletableDeferred<Cipher?>()
    /**
     * Completes the device lock request.
     *
     * @param cipher Optional cipher supplied to [complete].
     */
    fun complete(cipher: Cipher?) {
        result.complete(cipher)
    }
}

// Authenticates the signed-in user via Android Keystore device lock.
internal class SessionDeviceAccess(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    /**
     * Authenticates the account for session device access.
     *
     * @param enroll Whether to apply enroll.
     * @param trigger Optional trigger supplied to [authenticate].
     */
    fun authenticate(enroll: Boolean, trigger: String? = null): Unit = with(runtime) {
        with(state) {
            if (busy || privacyBlackout || accountRole != AccountRole.USER) return
            val identity = pendingIdentity
            val value = pendingProfile
            val device = enrollment
            val expectedScreen = if (enroll) SessionScreen.DevicePin else if (trigger != null) SessionScreen.Safe else SessionScreen.Pin
            if (screen != expectedScreen) return
            if (enroll && (identity == null || value == null || device != null || value.role != AccountRole.USER)) return
            if (!enroll && (device?.entryMethod != SafeEntryMethod.DEVICE_LOCK || device.role != AccountRole.USER)) return
            if (!enroll && trigger == null && activeTriggerId == null) return
            if (trigger != null && !InputPolicy.validTrigger(trigger)) {
                message = application.getString(R.string.entry_invalid_trigger)
                return
            }
            val uid = if (enroll) identity!!.uid else device!!.uid
            val version = pinChallengeVersion
            operations.runOperation(application.getString(R.string.entry_auth_failed), timeoutMillis = 120_000) { token ->
                val challenge = withContext(Dispatchers.IO) {
                    keys.prepare(uid, if (enroll) null else store.deviceLockGrant(uid))
                }
                if (!current(token, expectedScreen, uid, enroll)) {
                    challenge.close()
                    return@runOperation
                }
                val request = DeviceLockRequest(challenge, settingsChange = trigger != null)
                deviceLockRequest = request
                try {
                    val cipher = request.result.await()
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (cipher == null) {
                        message = application.getString(R.string.entry_auth_cancelled)
                        return@runOperation
                    }
                    val grant = withContext(Dispatchers.IO) { challenge.finish(cipher) }
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (enroll) {
                        val saved = withContext(Dispatchers.IO) {
                            store.enrollDeviceLock(identity!!, value!!, checkNotNull(grant)).also { challenge.markEnrolled() }
                        }
                        if (current(token, expectedScreen, uid, true)) accountSession.finishEnrollment(saved, value!!, token)
                    } else if (trigger != null) {
                        settings.saveVerifiedTrigger(device!!, trigger, token)
                    } else {
                        withContext(Dispatchers.IO) {
                            triggerJob?.join()
                            activeTriggerId?.let { store.recordTriggerAttempt(uid, it, true) }
                        }
                        if (current(token, expectedScreen, uid, false) && version == pinChallengeVersion) {
                            access.unlockVerifiedDevice(device!!, token, expectedScreen, version)
                        }
                    }
                } finally {
                    if (deviceLockRequest === request) deviceLockRequest = null
                    challenge.close()
                }
            }
        }
    }

    /**
     * Computes current.
     *
     * @param token Authentication, push notification, or session token.
     * @param expected Expected supplied to [current].
     * @param uid Firebase UID of the target user account.
     * @param enroll Whether to apply enroll.
     */
    private fun current(token: Long, expected: SessionScreen, uid: String, enroll: Boolean) = with(runtime) {
        epoch == token && state.screen == expected && !state.privacyBlackout && state.accountRole == AccountRole.USER &&
            (if (enroll) pendingIdentity?.uid == uid else enrollment?.uid == uid)
    }
}

// Signed-in users: holds the active account and screen state.

// Shares dependencies, account identity, jobs and cancellation epoch across feature controllers.
internal class SessionRuntime(
    val application: Application,
    val scope: CoroutineScope,
    dataSourcesOverride: SessionDataSources? = null,
    val localMode: Boolean = !BuildConfig.FIREBASE_CONFIGURED,
) {
    val state: SessionState = SessionState { canEnterSafe() }
    private val localStorage = SessionLocalStorage(application, localMode)
    val vault get() = localStorage.vault

    /**
     * Checks whether Android has a PIN, pattern, password, or biometric lock.
     *
     * @return The resulting phone secure.
     */
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
            if (activeEnrollment?.uid != value?.uid) { messaging.stopMessaging(); adminPreloader.clear() }
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
    val adminPreloader = AdminDataPreloader(this)
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
    /**
     * Checks whether conversation unread.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @return `true` if is conversation unread, or `false` otherwise.
     */
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
    /**
     * Checks whether active unread conversation.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @return `true` if is active unread conversation, or `false` otherwise.
     */
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

    /**
     * User access requires a verified app PIN or Android authentication for this enrollment and epoch.
     *
     * @return `true` if can enter safe, or `false` otherwise.
     */
    private fun canEnterSafe(): Boolean {
        val device = enrollment
        val requiresPin = device?.role == AccountRole.USER || state.accountRole == AccountRole.USER
        return !requiresPin || (device != null && pinAuthorizedUid == device.uid && pinAuthorizedEpoch == epoch)
    }

}

// Uses a 25-second timeout by default; Android authentication gets longer. Epoch/job checks reject canceled work.
internal class SessionOperations(private val runtime: SessionRuntime) {
    /**
     * Runs one session task with a timeout and a readable error message.
     *
     * @param failure Exception or failure reason encountered during execution.
     * @param timeoutMillis Duration or timestamp for timeout, in milliseconds.
     * @param block Callback or suspending lambda executed within this scope.
     */
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
