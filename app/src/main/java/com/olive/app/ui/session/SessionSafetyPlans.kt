// Signed-in users: controls safety-plan access, editing, sharing, and sync.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import androidx.compose.runtime.*
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import com.olive.app.data.AccountRole
import com.olive.app.security.DeviceLockChallenge
import com.olive.app.security.DeviceLockKey
import com.olive.app.security.SafeEntryMethod
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher


// Private presentation state is cleared on exit; captured local writes finish without reopening a private screen.
internal class SessionSafetyPlans(private val runtime: SessionRuntime) {
    private val store = SafetyPlanStore(runtime.application, runtime.localMode)
    private val repository by lazy { SafetyPlanRepository() }
    private val writes = Mutex()
    private val sync = Mutex()
    private var activeOwner: String? = null
    private var lease: Any? = null
    private var planListener: ListenerRegistration? = null
    private var generation = 0
    var plan by mutableStateOf<SafetyPlan?>(null)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var saving by mutableIntStateOf(0)
        private set
    var consultations by mutableStateOf<List<SafetyConsultation>>(emptyList())
        private set
    var sharedPlans by mutableStateOf<List<SharedSafetyPlan>>(emptyList())
        private set
    var available by mutableStateOf(true)
        private set

    // Allows plan work only while the signed-in role is on a screen that may use safety plans.
    private fun authorized() = runtime.enrollment != null &&
            ((runtime.state.accountRole in setOf(
                AccountRole.USER,
                AccountRole.SAFE_CONTACT
            ) && runtime.state.screen == SessionScreen.Safe && runtime.state.page in setOf(SafePage.SafetyPlan, SafePage.StaffHelp)) ||
                    (runtime.state.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
                            runtime.state.screen == SessionScreen.Portal))

    // Rejects old background results after the account, plan owner, or screen session changes.
    private fun current(actor: String, owner: String, token: Int) =
        authorized() && runtime.coverAccountId == actor && activeOwner == owner && generation == token

    // Clears private plan data when its screen closes; a different screen cannot clear the active one.
    fun close(screenLease: Any? = null) {
        if (screenLease != null && lease !== screenLease) return
        planListener?.remove()
        planListener = null
        lease =
            null
        generation++
        activeOwner = null
        plan = null
        error = null
        saving =
            0
        if (screenLease == null) consultations = emptyList()
    }

    // Checks access, loads or creates the selected plan, queues cloud sync, and ignores stale results.
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
        error = null
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
            refresh()
            if (!runtime.localMode && current(actor, ownerUid, token)) {
                planListener = repository.observe(
                    ownerUid,
                    onUpdate = { remote ->
                        runtime.scope.launch {
                            // Keep unsent local edits while refreshing every section changed on another device.
                            val updated = withContext(Dispatchers.IO) {
                                store.update(actor, ownerUid) { local ->
                                    val combined = local.copy(fields = remote.fields + local.fields)
                                    mergeSafetyPlan(combined, combined, remote)
                                }
                            }
                            if (current(actor, ownerUid, token)) {
                                plan = updated
                                if (updated.pending) SafetyPlanSyncWorker.enqueue(
                                    runtime.application,
                                    actor,
                                    ownerUid
                                )
                            }
                        }
                    },
                    onError = { failure ->
                        if (current(actor, ownerUid, token)) error = R.string.safety_sync_error
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // Keep locally available plan data on screen and report that cloud storage could not be loaded.
            if (current(actor, ownerUid, token)) error = R.string.safety_storage_error
        }
    }

    // Applies an allowed field change locally and saves it for background sync.
    fun edit(key: String, value: String) {
        val old = plan ?: return
        if (!authorized() || runtime.state.accountRole == AccountRole.SAFE_CONTACT || key !in SAFETY_FIELD_KEYS || value.length > 4000) return
        val field = old.fields[key] ?: return
        if (field.value == value) return
        val edited = field.edit(value)
        plan = old.copy(fields = old.fields + (key to edited))
        persist { saved -> saved.copy(fields = saved.fields + (key to edited)) }
    }

    // Resolves a sync conflict by keeping either the local text or the newer server text.
    fun resolve(key: String, keepMine: Boolean) {
        val old = plan ?: return
        if (runtime.state.accountRole == AccountRole.SAFE_CONTACT) return
        val field = old.fields[key] ?: return
        val revision = field.conflictRevision ?: return
        val edited = if (keepMine) field.copy(
            revision = revision,
            conflictRevision = null,
            conflictValue = null
        ).edit(field.value)
        else SafetyField(field.conflictValue.orEmpty(), revision)
        plan = old.copy(fields = old.fields + (key to edited))
        persist { saved -> saved.copy(fields = saved.fields + (key to edited)) }
    }

    // Requests or ends staff help for the account owner's safety plan.
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

    // Verifies the selected friends, syncs pending edits, and shares the plan with up to ten contacts.
    fun shareWithContacts(friends: List<Friend>) {
        if (!authorized() || runtime.state.accountRole != AccountRole.USER || runtime.localMode || friends.size > 10) return
        val draft = plan ?: return
        val actor = runtime.coverAccountId ?: return
        val token = generation
        saving++
        error = null
        runtime.scope.launch {
            // Use the fallback when share with contacts is unavailable.
            try {
                repository.sync(draft)
                val updated = repository.shareContacts(friends)
                if (current(actor, actor, token)) {
                    plan = updated
                    error = null
                    withContext(Dispatchers.IO) { store.write(actor, actor, updated) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Preserve the local conflict resolution and tell the user that cloud sync must be retried.
                if (current(actor, actor, token)) error = R.string.safety_sync_error
            } finally {
                if (current(actor, actor, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    // Adds one already-approved safe contact to the user's staff support conversation.
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
                    val updated = old.copy(supportSafeContactUid = uid)
                    plan = updated
                    withContext(Dispatchers.IO) { store.write(actor, actor, updated) }
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

    // Loads the plans that other users currently share with this safe contact.
    fun refreshSharedPlans() {
        if (!authorized() || runtime.state.accountRole != AccountRole.SAFE_CONTACT || runtime.localMode) return
        val actor = runtime.coverAccountId
        val token = generation
        runtime.scope.launch {
            // Turn an unexpected refresh shared plans failure into a readable message.
            try {
                val result = repository.sharedWithMe()
                if (actor != null && runtime.coverAccountId == actor && authorized() && generation == token) {
                    sharedPlans = result
                    error = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the current consultation state and show a storage error when the request fails.
                if (actor != null && runtime.coverAccountId == actor && authorized()) error =
                    R.string.safety_sync_error
            }
        }
    }

    // Loads the staff/admin encryption identities that can read attachments on the user's own plan.
    suspend fun mediaRecipients(): Map<String, String> {
        val actor = runtime.coverAccountId ?: return emptyMap()
        if (!authorized() || runtime.state.accountRole != AccountRole.USER || activeOwner != actor || runtime.localMode) {
            return emptyMap()
        }
        return repository.mediaRecipients()
    }

    // Writes one plan change in order, then asks the durable worker to sync it when online.
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
                if (!runtime.localMode) SafetyPlanSyncWorker.enqueue(
                    runtime.application,
                    actor,
                    owner
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the local draft and show a storage error when saving fails.
                if (current(actor, owner, token)) error = R.string.safety_storage_error
            } finally {
                if (current(actor, owner, token)) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    // Saves the current draft again after a local storage failure.
    fun retrySave() {
        val draft = plan ?: return
        persist { saved -> saved.copy(fields = draft.fields, shareAction = draft.shareAction) }
    }

    // Syncs queued changes, reloads the saved plan, and removes a shared copy when access is revoked.
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
                error = null
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

    // Loads assigned consultations and the staff member's availability from local and cloud storage.
    suspend fun refreshConsultations() {
        if (!authorized() || runtime.state.accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) || runtime.localMode) return
        val actor = runtime.coverAccountId ?: return
        // Turn an unexpected refresh consultations failure into a readable message.
        try {
            val cached = withContext(Dispatchers.IO) { store.all(actor) }
            // Show saved plans only while the consultation screen has no list yet.
            // Later refreshes keep the current cloud list visible instead of briefly replacing it with a smaller cache.
            if (consultations.isEmpty() && authorized() && actor == runtime.coverAccountId) {
                consultations = cached.map {
                    SafetyConsultation(
                        it.ownerUid,
                        it.ownerName,
                        it.consultationConversationId,
                        it.staffUid,
                        it.staffName,
                    )
                }
            }
            if (!hasValidatedInternetConnection(runtime.application)) return
            val result = repository.consultations()
            withContext(Dispatchers.IO) {
                cached.filter { old -> result.second.none { it.ownerUid == old.ownerUid } }
                    .forEach { store.remove(actor, it.ownerUid) }
            }
            if (authorized() && actor == runtime.coverAccountId) {
                available = result.first
                consultations = result.second
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // Retain the last plan snapshot and tell the signed-in account that refresh failed.
            if (authorized() && actor == runtime.coverAccountId) error = R.string.safety_sync_error
        }
    }

    // Updates whether this staff member can receive new consultation requests.
    fun changeAvailability(value: Boolean) {
        if (!authorized() || runtime.state.accountRole != AccountRole.STAFF || runtime.localMode) return
        val actor = runtime.coverAccountId
        runtime.scope.launch {
            // Turn an unexpected change availability failure into a readable message.
            try {
                repository.available(value)
                if (authorized() && actor == runtime.coverAccountId) available =
                    value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { // Keep the current availability value when the staff setting cannot be updated.
                if (authorized() && actor == runtime.coverAccountId) error =
                    R.string.safety_sync_error
            }
        }
    }
}

// Signed-in users: checks device-lock and PIN entry.

internal class DeviceLockRequest internal constructor(
    val challenge: DeviceLockChallenge,
    val settingsChange: Boolean = false
) {
    internal val result = CompletableDeferred<Cipher?>()
    // Session device access: Finishes the active task.
    fun complete(cipher: Cipher?) {
        result.complete(cipher)
    }
}

// A prompt result is useful only to its original account, screen and cancellation epoch.
internal class SessionDeviceAccess(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    // Authenticates the account for session device access.
    fun authenticate(enroll: Boolean, trigger: String? = null): Unit = with(runtime) {
        with(state) {
            if (busy || privacyBlackout) return
            if (accountRole != AccountRole.USER) return
            val identity = pendingIdentity
            val value = pendingProfile
            val device = enrollment
            val expectedScreen =
                if (enroll) SessionScreen.DevicePin else if (trigger != null) SessionScreen.Safe else SessionScreen.Pin
            if (screen != expectedScreen) return
            if (enroll && (identity == null || value == null || device != null || value.role != AccountRole.USER)) return
            if (!enroll && device?.entryMethod != SafeEntryMethod.DEVICE_LOCK) return
            if (!enroll && device?.role != AccountRole.USER) return
            if (!enroll && trigger == null && activeTriggerId == null) return
            if (trigger != null && !InputPolicy.validTrigger(trigger)) {
                message = application.getString(R.string.entry_invalid_trigger)
                return
            }
            val uid = if (enroll) identity!!.uid else device!!.uid
            // Counts changes to value.
            val version = pinChallengeVersion
            operations.runOperation(
                application.getString(R.string.entry_auth_failed),
                timeoutMillis = 120_000
            ) { token ->
                val challenge = withContext(Dispatchers.IO) {
                    keys.prepare(uid, if (enroll) null else store.deviceLockGrant(uid))
                }
                if (!current(token, expectedScreen, uid, enroll)) {
                    challenge.close()
                    return@runOperation
                }
                val request = DeviceLockRequest(challenge, settingsChange = trigger != null)
                deviceLockRequest = request
                // Use the fallback when authenticate is unavailable.
                try {
                    val cipher = request.result.await()
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (cipher == null) {
                        message =
                            application.getString(R.string.entry_auth_cancelled)
                        return@runOperation
                    }
                    // The callback alone grants no access: Android Keystore must complete the protected operation.
                    val grant = withContext(Dispatchers.IO) { challenge.finish(cipher) }
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    // Save device-lock enrolment only when this prompt came from setup.
                    if (enroll) {
                        val saved = withContext(Dispatchers.IO) {
                            store.enrollDeviceLock(identity!!, value!!, checkNotNull(grant))
                                .also { challenge.markEnrolled() }
                        }
                        if (current(token, expectedScreen, uid, true)) accountSession.finishEnrollment(
                            saved,
                            value!!,
                            token
                        )
                    } else if (trigger != null) {
                        settings.saveVerifiedTrigger(device!!, trigger, token)
                    } else {
                        withContext(Dispatchers.IO) {
                            triggerJob?.join()
                            activeTriggerId?.let { store.recordTriggerAttempt(uid, it, true) }
                        }
                        if (current(
                                token,
                                expectedScreen,
                                uid,
                                false
                            ) && version == pinChallengeVersion
                        )
                            access.unlockVerifiedDevice(device!!, token, expectedScreen, version)
                    }
                } finally {
                    if (deviceLockRequest === request) deviceLockRequest = null
                    challenge.close()
                }
            }
        }
    }

    // Reads the current private-entry settings before changing them.
    private fun current(token: Long, expected: SessionScreen, uid: String, enroll: Boolean) =
        with(runtime) {
            epoch == token && state.screen == expected && !state.privacyBlackout && state.accountRole == AccountRole.USER &&
                    (if (enroll) pendingIdentity?.uid == uid else enrollment?.uid == uid)
        }
}
