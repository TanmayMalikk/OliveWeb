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
import com.olive.app.data.account.AccountKeysetRepository
import com.olive.app.data.account.PasskeyRecoveryRepository
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
    val accountKeysets = AccountKeysetLocalStore(vault)
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
    val accountKeysets: AccountKeysetRepository?,
    val passkeyRecovery: PasskeyRecoveryRepository?,
) {
    companion object {
        // Creates the cloud repository bundle used by a signed-in Firebase account.
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
                accountKeysets = if (localMode) null else AccountKeysetRepository(),
                passkeyRecovery = if (localMode) null else PasskeyRecoveryRepository(),
            )
        }
    }
}

// Checks cover triggers and PINs; only a verified result for the current enrollment may authorize the safe area.
internal class SessionAccess(private val runtime: SessionRuntime) {
    // Detects a valid private-entry search for session access.
    fun interceptSearch(value: String): Boolean {
        val device = runtime.enrollment ?: return false
        return openTrigger(device, InputPolicy.matchesTrigger(value, device.triggerHash))
    }

    suspend fun interceptSearchAsync(value: String): Boolean {
        val device = runtime.enrollment ?: return false
        val match = withContext(Dispatchers.Default) { InputPolicy.matchesTrigger(value, device.triggerHash) }
        return openTrigger(device, match)
    }

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

    // Starts the same locked PIN flow when the user double-taps the discreet olive on a cover.
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

    // Rejects queued clicks from an old PIN screen or while verification/blackout is active.
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

    // Rechecks identity, screen, and cancellation epoch after loading private data.
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
                page = SafePage.Home
                screen = SessionScreen.Safe
                unlockAnimationVersion++
                privacy.startIdleTimer()
            }
        }
    }
}

// Holds an active biometric/device-credential unlock request.
internal class DeviceLockRequest internal constructor(
    val challenge: DeviceAuthenticationChallenge,
    val settingsChange: Boolean = false
) {
    internal val result = CompletableDeferred<Cipher?>()
    fun complete(cipher: Cipher?) {
        result.complete(cipher)
    }
}

internal sealed interface PasskeyCredentialResult {
    data class Registration(val serverResponseJson: String) : PasskeyCredentialResult
    data class Assertion(val value: PasskeyPrfProtocol.AssertionResult) : PasskeyCredentialResult
    data class Failure(val reason: PasskeyCredentialFailure) : PasskeyCredentialResult
}

internal class PasskeyCredentialFailureException(
    val reason: PasskeyCredentialFailure,
) : Exception(reason.name)

/** One Activity-owned Credential Manager ceremony; raw PRF JSON never enters session state. */
internal class PasskeyCredentialRequest internal constructor(
    val requestJson: String,
    val assertion: Boolean,
) {
    internal val result = CompletableDeferred<PasskeyCredentialResult?>()
    fun complete(value: PasskeyCredentialResult?) {
        result.complete(value)
    }
}

// Authenticates the signed-in user via Android Keystore device lock.
internal class SessionDeviceAccess(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    // Authenticates the account for session device access.
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
    prioritySyncOverride: PrioritySyncCoordinator? = null,
) {
    val state: SessionState = SessionState { canEnterSafe() }
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
    val accountKeysets get() = localStorage.accountKeysets
    val prioritySync = prioritySyncOverride ?: PrioritySyncCoordinator(application)
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
    val accountKeysetRepository get() = dataSources.accountKeysets
    val passkeyRecoveryRepository get() = dataSources.passkeyRecovery
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
    fun runOperation(
        failure: String,
        timeoutMillis: Long = 25_000,
        failureFor: (Exception) -> String = { failure },
        block: suspend (Long) -> Unit,
    ) {
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
                    } catch (error: Exception) {
                        if (epoch == token) finalFailure = failureFor(error)
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
