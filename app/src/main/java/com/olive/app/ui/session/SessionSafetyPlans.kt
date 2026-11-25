// Signed-in users: controls safety-plan access, editing, sharing, and sync.
package com.olive.app.ui.session

import kotlin.time.Duration.Companion.milliseconds

import com.olive.app.ui.users.shared.navigation.*
import androidx.compose.runtime.*
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.Friend
import com.olive.app.data.network.hasValidatedInternetConnection
import com.olive.app.data.safety.SAFETY_FIELD_KEYS
import com.olive.app.data.safety.SafetyAccessIndexStore
import com.olive.app.data.safety.SafetyConsultation
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyField
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.SafetyPlanRepository
import com.olive.app.data.safety.SafetyPlanStore
import com.olive.app.data.safety.SafetyPlanSyncWorker
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.data.safety.edit
import com.olive.app.data.safety.mergeVisibleSafetyPlan
import com.olive.app.data.safety.readSafetyChecklist
import com.olive.app.data.safety.safetyChecklistWithPublishedDocuments
import com.olive.app.data.safety.safetyPlanSyncDelayMillis
import com.olive.app.data.safety.syncSafetyPlan
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.olive.app.data.sync.SyncCompletionEvents
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import android.os.SystemClock


internal data class SafetyPlansUiState(
    val plan: SafetyPlan? = null,
    val error: Int? = null,
    val sharingError: String? = null,
    val sharingSaved: Boolean = false,
    val saving: Int = 0,
    val syncing: Boolean = false,
    val loadingPlan: Boolean = true,
    val loadingAccess: Boolean = true,
    val consultations: List<SafetyConsultation> = emptyList(),
    val sharedPlans: List<SharedSafetyPlan> = emptyList(),
    val available: Boolean = true,
)

// Private presentation state is cleared on exit; captured local writes finish without reopening a private screen.
internal class SessionSafetyPlans(
    private val runtime: SessionRuntime,
    repositoryFactory: () -> SafetyPlanRepository = { SafetyPlanRepository() },
) {
    private val safetyWriterId = UUID.randomUUID().toString()
    private val store = SafetyPlanStore(runtime.application, runtime.localMode)
    private val accessIndex = SafetyAccessIndexStore(runtime.application)
    private val repository by lazy(repositoryFactory)
    private val writes = Mutex()
    private val sync = Mutex()
    private var activeOwner: String? = null
    private var lease: Any? = null
    private var planListener: ListenerRegistration? = null
    private var accessListener: ListenerRegistration? = null
    private var accessActor: String? = null
    private var generation = 0
    private var foregroundSync: Job? = null
    private val foregroundSyncRequests = Channel<Unit>(Channel.CONFLATED)
    private var immediateSyncRequested = false
    private val consultationRefreshMutex = Mutex()
    private val sharedPlansRefreshMutex = Mutex()
    private var consultationsSession: Pair<String, Long>? = null
    private var consultationsRefreshedAt = 0L
    private var accessRevision = 0
    private var availabilityRevision = 0
    private var availabilityWrite by mutableStateOf<Pair<String, Long>?>(null)
    val savingAvailability get() = availabilityWrite?.second == runtime.epoch

    private val uiStateFlow = MutableStateFlow(SafetyPlansUiState())
    val uiState: StateFlow<SafetyPlansUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (SafetyPlansUiState) -> SafetyPlansUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    var plan: SafetyPlan?
        get() = uiStateSnapshot.plan
        private set(value) = updateUiState { it.copy(plan = value) }
    var error: Int?
        get() = uiStateSnapshot.error
        private set(value) = updateUiState { it.copy(error = value) }
    var saving: Int
        get() = uiStateSnapshot.saving
        private set(value) = updateUiState { it.copy(saving = value) }
    var syncing: Boolean
        get() = uiStateSnapshot.syncing
        private set(value) = updateUiState { it.copy(syncing = value) }
    var loadingPlan: Boolean
        get() = uiStateSnapshot.loadingPlan
        private set(value) = updateUiState { it.copy(loadingPlan = value) }
    var loadingAccess: Boolean
        get() = uiStateSnapshot.loadingAccess
        private set(value) = updateUiState { it.copy(loadingAccess = value) }
    var consultations: List<SafetyConsultation>
        get() = uiStateSnapshot.consultations
        internal set(value) = updateUiState { it.copy(consultations = value) }
    var sharedPlans: List<SharedSafetyPlan>
        get() = uiStateSnapshot.sharedPlans
        private set(value) = updateUiState { it.copy(sharedPlans = value) }
    var available: Boolean
        get() = uiStateSnapshot.available
        private set(value) = updateUiState { it.copy(available = value) }

    init {
        runtime.scope.launch {
            SyncCompletionEvents.events.collect { uid ->
                if (uid == runtime.coverAccountId) loadCachedAccessLists(uid)
            }
        }
    }

    /**
     * Starts observing access.
     */
    fun observeAccess() {
        val actor = runtime.coverAccountId ?: return
        if (runtime.localMode || accessActor == actor) return
        accessListener?.remove()
        accessActor = actor
        accessListener = repository.observeAccess(actor) {
            if (runtime.coverAccountId == actor && authorized()) {
                if (runtime.state.accountRole == AccountRole.SAFE_CONTACT) refreshSharedPlans()
                else runtime.scope.launch { refreshConsultations() }
            }
        }
    }

    /**
     * Loads cached access lists.
     *
     * @param actor Actor supplied to [loadCachedAccessLists].
     */
    private suspend fun loadCachedAccessLists(actor: String) {
        val revision = availabilityRevision.takeUnless { savingAvailability }
        val cached = withContext(Dispatchers.IO) { accessIndex.read(actor) }
        if (!authorized() || actor != runtime.coverAccountId) return
        if (revision == availabilityRevision) available = cached.available
        consultations = cached.consultations
        sharedPlans = cached.sharedPlans
    }

    /**
     * Allows plan work only while the signed-in role is on a screen that may use safety plans.
     *
     * @return The resulting authorized.
     */
    private fun authorized() = runtime.enrollment != null &&
            ((runtime.state.accountRole in setOf(
                AccountRole.USER,
                AccountRole.SAFE_CONTACT
            ) && runtime.state.screen == SessionScreen.Safe && runtime.state.page in setOf(SafePage.SafetyPlan, SafePage.StaffHelp)) ||
                    (runtime.state.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
                            runtime.state.screen == SessionScreen.Portal))

    /**
     * Rejects old background results after the account, plan owner, or screen session changes.
     *
     * @param actor Actor supplied to [current].
     * @param owner Owner supplied to [current].
     * @param token Authentication, push notification, or session token.
     * @return The resulting current.
     */
    private fun current(actor: String, owner: String, token: Int) =
        authorized() && runtime.coverAccountId == actor && activeOwner == owner && generation == token

    /**
     * Clears private plan data when its screen closes; a different screen cannot clear the active one.
     *
     * @param screenLease Optional screen lease supplied to [close].
     * @param preserveAdminIndex Whether to preserve admin index.
     */
    fun close(screenLease: Any? = null, preserveAdminIndex: Boolean = false) {
        if (screenLease != null && lease !== screenLease) return
        planListener?.remove()
        foregroundSync?.cancel()
        while (foregroundSyncRequests.tryReceive().isSuccess) {
            // Drain requests owned by the screen that is closing.
        }
        immediateSyncRequested = false
        syncing = false
        planListener = null
        lease =
            null
        generation++
        activeOwner = null
        plan = null
        loadingPlan = true
        error = null
        updateUiState { it.copy(sharingError = null, sharingSaved = false) }
        saving =
            0
        if (screenLease == null && !(preserveAdminIndex && runtime.state.accountRole == AccountRole.ADMIN &&
                runtime.state.screen == SessionScreen.Portal)) {
            accessRevision++
            consultationsSession = null
            consultationsRefreshedAt = 0L
            loadingAccess = true
            consultations = emptyList()
            sharedPlans = emptyList()
            available = true
            accessListener?.remove()
            accessListener = null
            accessActor = null
        }
    }

    /**
     * Checks access, loads or creates the selected plan, queues cloud sync, and ignores stale results.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param ownerName Owner name supplied to [open].
     * @param defaults Key-value mapping of defaults entries.
     * @param screenLease Screen lease supplied to [open].
     */
    suspend fun open(
        ownerUid: String,
        ownerName: String,
        defaults: Map<String, String>,
        screenLease: Any
    ) {
        if (!authorized() || (runtime.state.accountRole == AccountRole.USER && ownerUid != runtime.coverAccountId)) return
        val actor = runtime.coverAccountId ?: return
        lease = screenLease
        planListener?.remove()
        planListener = null
        generation++
        val token = generation
        activeOwner = ownerUid
        plan = null
        loadingPlan = true
        error = null
        updateUiState { it.copy(sharingError = null, sharingSaved = false) }
        saving = 0
        // Use the fallback when open is unavailable.
        try {
            val value =
                withContext(Dispatchers.IO) { store.ensure(actor, ownerUid, ownerName, defaults) }
            if (!runtime.localMode && value.pending) SafetyPlanSyncWorker.enqueue(
                runtime.application,
                actor,
                ownerUid
            )
            if (current(actor, ownerUid, token)) plan = value
            if (!runtime.localMode && current(actor, ownerUid, token)) {
                runtime.scope.launch {
                    try {
                        if (current(actor, ownerUid, token)) {
                            runtime.messaging.registerMessagingIdentity(AccountIdentity(actor, runtime.state.coverUsername))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Plan fields stay available even if the device cannot register for documents yet.
                    }
                }
                planListener = repository.observe(
                    ownerUid,
                    onUpdate = { remote ->
                        runtime.scope.launch {
                            if (!current(actor, ownerUid, token)) return@launch
                            // Keep unsent local edits while refreshing every section changed on another device.
                            val updated = withContext(Dispatchers.IO) {
                                writes.withLock { store.mergeRemote(actor, remote) }
                            }
                            if (current(actor, ownerUid, token)) {
                                plan = mergeVisibleSafetyPlan(plan, updated, actor)
                                loadingPlan = false
                                if (updated.pending) SafetyPlanSyncWorker.enqueue(
                                    runtime.application,
                                    actor,
                                    ownerUid
                                )
                            }
                        }
                    },
                    onError = { failure ->
                        runtime.scope.launch {
                            if (!current(actor, ownerUid, token)) return@launch
                            if (actor != ownerUid && failure is com.google.firebase.firestore.FirebaseFirestoreException &&
                                failure.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                            ) {
                                plan = null
                                error = R.string.safety_access_revoked
                                withContext(Dispatchers.IO) { store.remove(actor, ownerUid) }
                            } else refresh()
                        }
                    },
                )
            }
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // Keep locally available plan data on screen and report that cloud storage could not be loaded.
            if (current(actor, ownerUid, token)) error = R.string.safety_storage_error
        } finally {
            if (current(actor, ownerUid, token)) loadingPlan = false
        }
    }

    /**
     * Applies an allowed field change locally and saves it for background sync.
     *
     * @param key Lookup key or cryptographic key.
     * @param value Input value to store, parse, or format.
     */
    fun edit(key: String, value: String) {
        val old = plan ?: return
        if (!authorized() || runtime.state.accountRole == AccountRole.SAFE_CONTACT || key !in SAFETY_FIELD_KEYS || value.length > 4000) return
        val field = old.fields[key] ?: return
        if (field.value == value) return
        val edited = field.edit(value, safetyWriterId, runtime.coverAccountId.orEmpty())
        plan = old.copy(fields = old.fields + (key to edited))
        persist { saved -> saved.copy(fields = saved.fields + (key to edited)) }
    }

    /**
     * Resolves a sync conflict by keeping either the local text or the newer server text.
     *
     * @param key Lookup key or cryptographic key.
     * @param keepMine Whether to keep mine.
     */
    fun resolve(key: String, keepMine: Boolean) {
        val old = plan ?: return
        if (runtime.state.accountRole != AccountRole.USER) return
        val field = old.fields[key] ?: return
        val proposal = old.pendingConflicts[key]
        if (field.conflictRevision == null && proposal != null) {
            val actor = runtime.coverAccountId ?: return
            val token = generation
            saving++
            runtime.scope.launch {
                try {
                    val updated = sync.withLock {
                        withContext(Dispatchers.IO) {
                            val remote = repository.sync(old, mapOf(
                                "key" to key, "revision" to field.revision,
                                "mutationId" to proposal.mutationId, "useIncoming" to !keepMine,
                            ))
                            store.mergeRemote(actor, remote)
                        }
                    }
                    if (current(actor, actor, token)) plan = updated
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (current(actor, actor, token)) error = R.string.safety_sync_error
                } finally {
                    if (current(actor, actor, token)) saving = (saving - 1).coerceAtLeast(0)
                }
            }
            return
        }
        val revision = field.conflictRevision ?: return
        val edited = if (keepMine) field.copy(
            revision = revision,
            baseValue = field.conflictValue,
            conflictRevision = null,
            conflictValue = null
        ).edit(field.value)
        else SafetyField(field.conflictValue.orEmpty(), revision)
        plan = old.copy(fields = old.fields + (key to edited))
        persist { saved -> saved.copy(fields = saved.fields + (key to edited)) }
    }

    /**
     * Requests or ends staff help for the account owner's safety plan.
     *
     * @param stop Whether to apply stop.
     */
    fun consult(stop: Boolean = false) {
        if (runtime.localMode) {
            error = R.string.safety_account_required
            return
        }
        val old = plan ?: return
        if (runtime.state.accountRole != AccountRole.USER || (!stop && old.hasConflicts)) return
        val action = if (stop) "stop" else "request"
        val actor = runtime.coverAccountId ?: return
        val token = generation
        val staged = old.copy(shareAction = action)
        plan = staged
        saving++
        error = null
        runtime.scope.launch {
            // Turn an unexpected consult failure into a readable message.
            try {
                withContext(Dispatchers.IO) {
                    writes.withLock {
                        store.update(actor, actor) { it.copy(shareAction = action) }
                    }
                }
                if (!hasValidatedInternetConnection(runtime.application)) {
                    SafetyPlanSyncWorker.enqueue(runtime.application, actor, actor)
                    return@launch
                }
                sync.withLock {
                    withTimeout(35.seconds) {
                        withContext(Dispatchers.IO) {
                            syncSafetyPlan(store, repository, actor, actor)
                        }
                    }
                }
                val updated = withContext(Dispatchers.IO) { store.read(actor, actor) }
                if (current(actor, actor, token) && updated != null) {
                    plan = updated
                    error = null
                    if (!stop && updated.consultationConversationId.isNotBlank()) {
                        if (updated.staffUid.isNotBlank()) shareConsultationDocuments(actor, updated)
                        openConsultation(updated.consultationConversationId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // The edit is already local, so queue an encrypted retry instead of losing the user's change.
                SafetyPlanSyncWorker.enqueue(runtime.application, actor, actor)
                if (current(actor, actor, token)) error = R.string.safety_sync_error
            } finally {
                if (current(actor, actor, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Opens consultation.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    fun openConsultation(conversationId: String) {
        val actor = runtime.coverAccountId ?: return
        if (conversationId.isBlank()) return
        val token = generation
        runtime.scope.launch {
            try {
                val conversation = runtime.messagingRepository?.listConversations(actor)?.firstOrNull { it.id == conversationId }
                    ?: return@launch
                if (!current(actor, actor, token)) return@launch
                runtime.navigation.navigate(SafePage.Messages)
                runtime.conversationActions.openConversation(conversation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current(actor, actor, token)) error = R.string.safety_sync_error
            }
        }
    }

    /**
     * Shares consultation documents.
     *
     * @param actor Actor supplied to [shareConsultationDocuments].
     * @param updated Updated supplied to [shareConsultationDocuments].
     */
    private fun shareConsultationDocuments(actor: String, updated: SafetyPlan) {
        val source = runtime.safetyDocumentRepository ?: return
        val ids = updated.fields.filterKeys { it.endsWith("_documents") }.values.flatMap { field ->
            readSafetyChecklist(field.value).flatMap { item -> item.attachmentIds - item.excludedAttachmentIds.toSet() }
        }.distinct()
        if (ids.isEmpty()) return
        runtime.scope.launch(Dispatchers.IO) {
            try {
                val staff = repository.mediaRecipients()[updated.staffUid] ?: return@launch
                for (id in ids) {
                    if (runtime.coverAccountId != actor) return@launch
                    val document = source.getFor(actor, id)
                    source.grantAccess(document, actor, updated.staffUid, staff, forPlan = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The plan screen retries grants when the staff device registers or the owner returns.
            }
        }
    }

    /**
     * Verifies the selected friends, syncs pending edits, and shares the plan with up to ten contacts.
     *
     * @param friends List of friend records.
     */
    fun shareWithContacts(friends: List<Friend>) {
        if (!authorized() || runtime.state.accountRole != AccountRole.USER || runtime.localMode || friends.size > 10) return
        if (plan == null) return
        val actor = runtime.coverAccountId ?: return
        val token = generation
        saving++
        error = null
        updateUiState { it.copy(sharingError = null, sharingSaved = false) }
        runtime.scope.launch {
            // Use the fallback when share with contacts is unavailable.
            try {
                val updated = sync.withLock {
                    withContext(Dispatchers.IO) {
                        syncSafetyPlan(store, repository, actor, actor)
                        store.mergeRemote(actor, repository.shareContacts(friends))
                    }
                }
                if (current(actor, actor, token)) {
                    plan = updated
                    error = null
                    updateUiState { it.copy(sharingError = null, sharingSaved = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) { // Preserve the local conflict resolution and tell the user that cloud sync must be retried.
                if (current(actor, actor, token)) {
                    error = R.string.safety_sync_error
                    updateUiState { it.copy(sharingError = failure.localizedMessage?.takeIf(String::isNotBlank), sharingSaved = false) }
                }
            } finally {
                if (current(actor, actor, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Adds one already-approved safe contact to the user's staff support conversation.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun setSupportContact(uid: String) {
        if (!authorized() || runtime.state.accountRole != AccountRole.USER || runtime.localMode) return
        val old = plan ?: return
        if (old.staffUid.isBlank() || (uid.isNotBlank() && uid !in old.safeContactUids)) return
        val actor = runtime.coverAccountId ?: return
        val token = generation
        saving++
        error = null
        runtime.scope.launch {
            // Turn an unexpected set support contact failure into a readable message.
            try {
                repository.setSupportContact(uid)
                if (current(actor, actor, token)) {
                    val updated = withContext(Dispatchers.IO) {
                        store.update(actor, actor) { it.copy(supportSafeContactUid = uid) }
                    }
                    plan = updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Contact sharing stays unchanged when the server rejects or cannot receive the update.
                if (current(actor, actor, token)) error = R.string.safety_sync_error
            } finally {
                if (current(actor, actor, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Loads the plans that other users currently share with this safe contact.
     */
    fun refreshSharedPlans() {
        if (!authorized() || runtime.state.accountRole != AccountRole.SAFE_CONTACT || runtime.localMode) return
        val actor = runtime.coverAccountId
        val token = generation
        runtime.scope.launch {
            sharedPlansRefreshMutex.withLock {
                // Turn an unexpected refresh shared plans failure into a readable message.
                loadingAccess = true
                try {
                    if (actor != null && sharedPlans.isEmpty()) loadCachedAccessLists(actor)
                    val result = repository.sharedWithMe()
                    if (actor != null && runtime.coverAccountId == actor && authorized() && generation == token) {
                        sharedPlans = result
                        error = null
                        withContext(Dispatchers.IO) { accessIndex.replaceShared(actor, result) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { // Keep the current consultation state and show a storage error when the request fails.
                    if (actor != null && runtime.coverAccountId == actor && authorized()) error =
                        R.string.safety_sync_error
                } finally {
                    if (runtime.coverAccountId == actor && authorized()) loadingAccess = false
                }
            }
        }
    }

    /**
     * Loads the staff/admin encryption identities that can read attachments on the user's own plan.
     *
     * @return Map containing the resulting media recipients entries.
     */
    suspend fun mediaRecipients(): Map<String, String> {
        val actor = runtime.coverAccountId ?: return emptyMap()
        if (!authorized() || runtime.state.accountRole != AccountRole.USER || activeOwner != actor || runtime.localMode) {
            return emptyMap()
        }
        return repository.mediaRecipients()
    }

    /**
     * Starts observing recipient devices.
     *
     * @param usernames Collection of usernames values to process.
     * @param onChange Callback invoked when the value or observed state changes.
     * @return The resulting observe recipient devices.
     */
    fun observeRecipientDevices(usernames: Collection<String>, onChange: () -> Unit) =
        repository.observeRecipientDevices(usernames, onChange)

    /**
     * Uploads publish their ID after the encrypted file is available in the owner's document list.
     *
     * @param documents Collection of documents values to process.
     */
    fun attachPublishedDocuments(documents: List<SafetyDocument>) {
        val draft = plan ?: return
        val fields = draft.fields.filterKeys { it.endsWith("_documents") }.mapValues { (key, field) ->
            safetyChecklistWithPublishedDocuments(key, field.value, documents)
        }
        fields.forEach { (key, value) -> edit(key, value) }
    }

    /**
     * Writes one plan change in order, then asks the durable worker to sync it when online.
     *
     * @param change Function used to transform or provide change.
     */
    private fun persist(change: (SafetyPlan) -> SafetyPlan) {
        if (!authorized()) return
        val actor = runtime.coverAccountId ?: return
        val owner = activeOwner ?: return
        val token = generation
        saving++
        error = null
        runtime.scope.launch {
            // Use the fallback when persist is unavailable.
            try {
                withContext(Dispatchers.IO) {
                    writes.withLock {
                        store.update(
                            actor,
                            owner,
                            change
                        )
                    }
                }
                if (!runtime.localMode) {
                    SafetyPlanSyncWorker.enqueue(runtime.application, actor, owner)
                    if (current(actor, owner, token)) {
                        requestForegroundSync(actor, owner, token)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the local draft and show a storage error when saving fails.
                if (current(actor, owner, token)) error = R.string.safety_storage_error
            } finally {
                if (current(actor, owner, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Requests foreground sync.
     *
     * @param actor Actor supplied to [requestForegroundSync].
     * @param owner Owner supplied to [requestForegroundSync].
     * @param token Authentication, push notification, or session token.
     * @param immediate Whether to apply immediate.
     */
    private fun requestForegroundSync(actor: String, owner: String, token: Int, immediate: Boolean = false) {
        if (immediate) immediateSyncRequested = true
        foregroundSyncRequests.trySend(Unit)
        if (foregroundSync?.isActive == true) return
        val coordinator = runtime.scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (current(actor, owner, token)) {
                    foregroundSyncRequests.receive()
                    val firstEditAt = SystemClock.elapsedRealtime()
                    var lastEditAt = firstEditAt
                    var immediateBatch = immediateSyncRequested
                    immediateSyncRequested = false
                    while (!immediateBatch) {
                        val waitMillis = safetyPlanSyncDelayMillis(
                            firstEditAt,
                            lastEditAt,
                            SystemClock.elapsedRealtime(),
                        )
                        if (waitMillis == 0L) break
                        if (withTimeoutOrNull(waitMillis.milliseconds) {
                            foregroundSyncRequests.receive()
                        } == null) break
                        lastEditAt = SystemClock.elapsedRealtime()
                        if (immediateSyncRequested) {
                            immediateBatch = true
                            immediateSyncRequested = false
                        }
                    }
                    if (!current(actor, owner, token) || !hasValidatedInternetConnection(runtime.application)) continue
                    syncing = true
                    val saved = sync.withLock {
                        withContext(Dispatchers.IO) {
                            writes.withLock {
                                val queued = store.read(actor, owner)
                                if (queued?.pending == true) {
                                    syncSafetyPlan(store, repository, actor, owner)
                                    store.read(actor, owner)
                                } else queued
                            }
                        }
                    }
                    if (current(actor, owner, token) && saved != null) {
                        plan = mergeVisibleSafetyPlan(plan, saved, actor)
                        if (!saved.pending && error != R.string.safety_account_required) error = null
                    }
                    syncing = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current(actor, owner, token)) error = R.string.safety_sync_error
            } finally {
                syncing = false
                if (foregroundSync === currentCoroutineContext()[Job]) foregroundSync = null
            }
        }
        foregroundSync = coordinator
        coordinator.start()
    }

    /**
     * Flushes the latest locally durable edit when a field loses focus or a discrete control changes.
     */
    fun flushEdits() {
        if (runtime.localMode) return
        val actor = runtime.coverAccountId ?: return
        val owner = activeOwner ?: return
        requestForegroundSync(actor, owner, generation, immediate = true)
    }

    /**
     * Saves the current draft again after a local storage failure.
     */
    fun retrySave() {
        val draft = plan ?: return
        persist { saved -> saved.copy(fields = draft.fields, shareAction = draft.shareAction) }
    }

    /**
     * Computes flush consultation.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     */
    suspend fun flushConsultation(conversationId: String) {
        val actor = runtime.coverAccountId ?: return
        val draft = plan?.takeIf { it.consultationConversationId == conversationId } ?: return
        sync.withLock {
            withContext(Dispatchers.IO) {
                writes.withLock {
                    check(runtime.coverAccountId == actor)
                    syncSafetyPlan(store, repository, actor, draft.ownerUid)
                    check(store.read(actor, draft.ownerUid)?.pending != true)
                }
            }
        }
    }

    /**
     * Syncs queued changes, reloads the saved plan, and removes a shared copy when access is revoked.
     */
    suspend fun refresh() {
        if (error == R.string.safety_storage_error) return
        if (!authorized() || saving > 0 || !sync.tryLock()) return
        val actor = runtime.coverAccountId ?: run {
            sync.unlock()
            return
        }
        val owner = activeOwner ?: run {
            sync.unlock()
            return
        }
        val token = generation
        // Turn an unexpected refresh failure into a readable message.
        try {
            // Local demo plans are saved immediately because there is no cloud connection.
            if (!runtime.localMode && hasValidatedInternetConnection(runtime.application)) {
                withTimeout(35.seconds) {
                    withContext(Dispatchers.IO) {
                        syncSafetyPlan(
                            store,
                            repository,
                            actor,
                            owner
                        )
                    }
                }
            }
            val saved = withContext(Dispatchers.IO) { store.read(actor, owner) }
            if (current(actor, owner, token) && saving == 0) {
                plan = saved
                if (error != R.string.safety_account_required) error = null
            }
        } catch (_: TimeoutCancellationException) { /* Retain the local draft; the durable queue retries when connected. */
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FirebaseFunctionsException) {
            if (failure.code == FirebaseFunctionsException.Code.PERMISSION_DENIED && actor != owner) {
                withContext(Dispatchers.IO) { store.remove(actor, owner) }
                if (current(actor, owner, token)) {
                    plan = null
                    error = R.string.safety_access_revoked
                }
            }
        } catch (_: Exception) { // Connection failures leave already-saved edits queued for the next background sync.
        } finally {
            sync.unlock()
        }
    }

    /**
     * Loads assigned consultations and the staff member's availability from local and cloud storage.
     *
     * @param force Whether to apply force.
     * @return The resulting refresh consultations.
     */
    suspend fun refreshConsultations(force: Boolean = true) = consultationRefreshMutex.withLock {
        if (!authorized() || runtime.state.accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) || runtime.localMode) return@withLock
        val actor = runtime.coverAccountId ?: return@withLock
        val session = actor to runtime.epoch
        val revision = accessRevision
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && consultationsSession == session && now - consultationsRefreshedAt < 90_000) return@withLock
        // Once loaded, reconcile the index without replacing it with a skeleton.
        loadingAccess = consultationsSession != session
        try {
            if (consultations.isEmpty()) loadCachedAccessLists(actor)
            if (!hasValidatedInternetConnection(runtime.application)) return@withLock
            val result = repository.consultations()
            withContext(Dispatchers.IO) {
                val cached = store.all(actor)
                cached.filter { old -> result.second.none { it.ownerUid == old.ownerUid } }
                    .forEach { store.remove(actor, it.ownerUid) }
                accessIndex.replaceConsultations(actor, result.first, result.second)
            }
            if (authorized() && actor == runtime.coverAccountId && runtime.epoch == session.second && accessRevision == revision) {
                consultationsSession = session
                consultationsRefreshedAt = android.os.SystemClock.elapsedRealtime()
                if (!savingAvailability) available = result.first
                consultations = result.second
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // Retain the last plan snapshot and tell the signed-in account that refresh failed.
            if (authorized() && actor == runtime.coverAccountId && runtime.epoch == session.second && accessRevision == revision) error = R.string.safety_sync_error
        } finally {
            if (authorized() && actor == runtime.coverAccountId && runtime.epoch == session.second && accessRevision == revision) loadingAccess = false
        }
    }

    /**
     * Updates whether this staff member can receive new consultation requests.
     *
     * @param value Input value to store, parse, or format.
     */
    fun changeAvailability(value: Boolean) {
        if (!authorized() || runtime.state.accountRole != AccountRole.STAFF || runtime.localMode) return
        val actor = runtime.coverAccountId ?: return
        if (savingAvailability || available == value) return
        val previous = available
        val operation = actor to runtime.epoch
        availabilityWrite = operation
        availabilityRevision++
        available = value
        runtime.scope.launch {
            consultationRefreshMutex.withLock {
                try {
                    if (operation != (runtime.coverAccountId to runtime.epoch)) return@withLock
                    repository.available(value)
                    withContext(Dispatchers.IO) {
                        // A local cache failure cannot undo a confirmed server write.
                        runCatching { accessIndex.setAvailability(actor, value) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (authorized() && operation == (runtime.coverAccountId to runtime.epoch)) {
                        available = previous
                        error = R.string.safety_sync_error
                    }
                } finally {
                    if (availabilityWrite == operation) availabilityWrite = null
                }
            }
        }
    }
}
