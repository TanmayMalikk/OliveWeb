// Signed-in users: controls who can receive shared safety items.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.ChatConversation
import com.olive.app.data.ChatMessage
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.users.user.location.LocationMapConfiguration
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.security.KeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PublicKey
import com.olive.app.ui.users.shared.navigation.*
import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import com.olive.app.data.sync.PrioritySyncCoordinator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout


/** Coordinates encrypted conversations and private-entry sharing without exposing keys to UI code. */
internal class SessionSecureSharing(private val runtime: SessionRuntime) {
    // Keeps the saved room key name in one place for the signed-in session.
    suspend fun roomKey(conversation: ChatConversation): javax.crypto.SecretKey {
        with(runtime) {
            val device = enrollment ?: error("No active account")
            val repository = messagingRepository ?: error("Messaging unavailable")
            val secure = secureChatRepository ?: error("Secure messaging unavailable")
            // Use the fallback when room key is unavailable.
            try {
                val peers = conversation.participantIds.filter { it != device.uid }.map { uid ->
                    val username = conversation.participantUsernames[uid]
                        ?: error("Conversation participant unavailable")
                    repository.searchUsers(username, device.uid).firstOrNull()
                        ?: error("Every participant needs to open the updated app before secure messages can be sent.")
                }
                require(peers.all { it.publicKey.isNotBlank() }) {
                    "Every participant needs to open the updated app before secure messages can be sent."
                }
                val keys: Map<String, PublicKey> = withContext(Dispatchers.Default) {
                    buildMap {
                        put(device.uid, KeyManager.getOrCreatePublicKey())
                        peers.forEach { put(it.uid, KeyManager.decodePublicKey(it.publicKey)) }
                    }
                }
                return secure.ensureRoomKey(conversation, device.uid, keys)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (keySharingFailure: Exception) {
                // Existing members can still read history while a newly added member finishes key setup.
                return secure.existingRoomKey(conversation.id, device.uid) ?: throw keySharingFailure
            }
        }
    }

    // Shares the selected item for session secure sharing.
    fun share(entry: PrivateEntry) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val documents = secureDocumentRepository ?: return
                val chat = secureChatRepository ?: return
                val repository = messagingRepository ?: return
                if (messagingBusy) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Turn an unexpected share failure into a readable message.
                    try {
                        val peers = conversation.participantIds.filter { it != device.uid }.map { uid ->
                            val name = conversation.participantUsernames[uid]
                                ?: error("Participant unavailable")
                            repository.searchUsers(name, device.uid).firstOrNull()
                                ?: error("Recipient unavailable")
                        }
                        require(peers.all { it.publicKey.isNotBlank() })
                        val recipients = withContext(Dispatchers.Default) {
                            buildMap {
                                put(device.uid, KeyManager.getOrCreatePublicKey())
                                peers.forEach { put(it.uid, KeyManager.decodePublicKey(it.publicKey)) }
                            }
                        }
                        documents.saveMultiRecipientEntry(
                            entry,
                            device.uid,
                            recipients
                        )
                        val prefix =
                            if (entry.kind == PrivateEntryKind.Incident) "SHARED_INCIDENT" else "SHARED_NOTE"
                        chat.send(
                            conversation.id, AccountIdentity(device.uid, coverUsername),
                            "$prefix:${entry.id}:${
                                entry.title.ifBlank {
                                    runtime.application.getString(
                                        R.string.untitled_note
                                    )
                                }
                            }",
                            roomKey(conversation)
                        )
                        if (epoch == token) message =
                            runtime.application.getString(R.string.private_entry_shared)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Hide encryption details and report only that the private entry could not be shared.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_share_private_entry)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Shares the selected item for session secure sharing.
    fun share(document: SafetyDocument) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val documents = safetyDocumentRepository ?: return
                val chat = secureChatRepository ?: return
                if (messagingBusy || document.ownerUid != device.uid) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    val newlyGrantedRecipients = mutableListOf<String>()
                    // Turn an unexpected share failure into a readable message.
                    try {
                        conversation.participantIds.filter { it != device.uid }.forEach { recipient ->
                            if (recipient !in document.allowedUsers) newlyGrantedRecipients += recipient
                            val username = conversation.participantUsernames[recipient]
                                ?: error("Recipient username is unavailable")
                            documents.grantAccess(document, device.uid, recipient, username)
                        }
                        chat.send(
                            conversation.id, AccountIdentity(device.uid, coverUsername),
                            "SHARED_MEDIA:${document.id}:${document.name}", roomKey(conversation)
                        )
                        if (epoch == token) message =
                            runtime.application.getString(R.string.documents_shared_in_chat)
                    } catch (error: CancellationException) {
                        newlyGrantedRecipients.forEach { recipient ->
                            runCatching {
                                documents.revokeAccess(document.id, device.uid, recipient)
                            }
                        }
                        throw error
                    } catch (_: Exception) { // Revoke any access granted before a later recipient or chat update failed.
                        newlyGrantedRecipients.forEach { recipient ->
                            runCatching {
                                documents.revokeAccess(document.id, device.uid, recipient)
                            }
                        }
                        if (epoch == token) message =
                            runtime.application.getString(R.string.documents_share_failed)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Sends safety plan for session secure sharing.
    fun shareSafetyPlan() {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val chat = secureChatRepository ?: return
                if (accountRole != com.olive.app.data.AccountRole.USER || messagingBusy ||
                    conversation.type != "SUPPORT") return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Use the fallback when share safety plan is unavailable.
                    try {
                        chat.send(
                            conversation.id,
                            AccountIdentity(device.uid, coverUsername),
                            "SHARED_SAFETY_PLAN:${device.uid}:Safety plan",
                            roomKey(conversation),
                        )
                        if (epoch == token) message = runtime.application.getString(R.string.safety_plan_shared_in_chat)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Leave the safety plan unchanged and show the shared sync error.
                        if (epoch == token) message = runtime.application.getString(R.string.safety_sync_error)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Session secure sharing: Sends live location.
    fun shareLiveLocation(
        durationMillis: Long,
        controller: LocationMapController,
        requestPermission: suspend () -> Boolean
    ) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val profile = profile ?: return
                if (profile.role != com.olive.app.data.AccountRole.USER || messagingBusy) return
                val peerUid = conversation.participantIds.firstOrNull { it != device.uid } ?: return
                val friend = friends.firstOrNull { it.uid == peerUid } ?: return
                val configuration = LocationMapConfiguration(
                    AccountIdentity(device.uid, coverUsername),
                    profile,
                    listOf(friend),
                    localMode,
                    application.getString(R.string.you),
                    application.getString(R.string.your_saved_location)
                )
                controller.startSharingWith(
                    configuration,
                    listOf(friend),
                    durationMillis,
                    requestPermission,
                    application.getString(R.string.location_permission_required),
                    application.getString(R.string.location_start_failed),
                    onError = { message = it },
                    onStarted = { expiresAt ->
                        // Posts a normal encrypted timeline item after location sharing has actually started.
                        conversationActions.sendMessage("SHARED_LOCATION:$expiresAt")
                    },
                )
            }
        }
    }

    // Opens the requested item for session secure sharing.
    fun open(entryId: String) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                val repository = secureDocumentRepository ?: return
                if (busy) return
                val token = epoch
                busy = true
                message = null
                viewedSharedEntry = null
                scope.launch {
                    // Turn an unexpected open failure into a readable message.
                    try {
                        val entry = repository.loadAndDecryptEntry(entryId, uid)
                        if (epoch == token) viewedSharedEntry = entry
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Keep the encrypted entry closed and display a safe opening error.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_open_shared_entry)
                    } finally {
                        if (epoch == token) busy = false
                    }
                }
            }
        }
    }

    // Removes if shared from the signed-in session.
    suspend fun revokeIfShared(
        message: ChatMessage,
        conversation: ChatConversation,
        currentUid: String
    ) {
        val parts = message.body.split(":", limit = 3)
        if (parts.firstOrNull() == "SHARED_MEDIA") {
            val entryId = parts.getOrNull(1) ?: return
            conversation.participantIds.filter { it != currentUid }.forEach { recipient ->
                runtime.safetyDocumentRepository?.revokeAccess(entryId, currentUid, recipient)
            }
            return
        }
        if (parts.firstOrNull() !in setOf("SHARED_NOTE", "SHARED_INCIDENT")) return
        val entryId = parts.getOrNull(1) ?: return
        conversation.participantIds.filter { it != currentUid }.forEach { recipient ->
            runtime.secureDocumentRepository?.revokeAccessToEntry(entryId, recipient)
        }
    }

    // Session secure sharing: Closes the active feature.
    fun close() {
        runtime.state.viewedSharedEntry = null
    }
}

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
    val activityVault get() = localStorage.activityVault

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
    var enrollment by mutableStateOf<DeviceEnrollment?>(null)
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
    val sensitive get() = state.screen != SessionScreen.Cover
    val setupUsername get() = pendingIdentity?.username.orEmpty()
    val setupProfile get() = pendingProfile
    val usesCover get() = state.accountRole == AccountRole.USER
    fun isConversationUnread(conversation: ChatConversation): Boolean {
        state.readMessageVersion
        val uid = enrollment?.uid ?: return false
        return conversation.lastMessageId.isNotBlank() &&
                conversation.lastSenderUid.isNotBlank() &&
                conversation.lastSenderUid != uid &&
                messageReadState.getString(
                    conversationActions.readMessageKey(uid, conversation.id),
                    null,
                ) != conversation.lastMessageId
    }
    val unreadMessageCount: Int
        get() {
            return state.conversations.count(::isConversationUnread)
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
    var privateEntries by mutableStateOf(emptyList<PrivateEntry>())
    var viewedSharedEntry by mutableStateOf<PrivateEntry?>(null)
    var userSearchResults by mutableStateOf(emptyList<DirectoryUser>())
    var friends by mutableStateOf(emptyList<Friend>())
    var incomingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var outgoingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var conversations by mutableStateOf(emptyList<ChatConversation>())
    var archivedConversationIds by mutableStateOf(emptySet<String>())
    var restoredClosedConversationIds by mutableStateOf(emptySet<String>())
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
    var language by mutableStateOf("English")
    var coverDob by mutableStateOf("")
    var coverFullName by mutableStateOf("")
    var coverUsername by mutableStateOf("")
    var coverType by mutableStateOf(CoverType.FITNESS)
    var accountRole by mutableStateOf(AccountRole.USER)
    var adminCounts by mutableStateOf(AccountCounts())
    var adminAccounts by mutableStateOf(emptyList<AdminAccountSummary>())
    var savedNews by mutableStateOf<List<NewsArticle>>(emptyList())
    // Counts changes to cover data.
    var coverDataVersion by mutableIntStateOf(0)
    var idleCountdownSeconds by mutableStateOf<Int?>(null)
    var privacyBlackout by mutableStateOf(false)
    var credentialFieldError by mutableStateOf<CredentialFieldError?>(null)
    var creatingAccount by mutableStateOf(false)
    var coverReset by mutableIntStateOf(0)
}
