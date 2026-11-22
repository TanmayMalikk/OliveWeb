// Schedules priority reconciliation and persists or reports its progress.
@file:Suppress("SimplifyBooleanWithConstants")
//!BUILDCONFIG.FIREBASE_CONFIGURED || uid.isBlank()
//the linter thinks that line is better as just uid.isBlank()
//dropping buildconfig means we don't have any check to drop the function if BUILDCONFIG.FIREBASE_CONFIGURED is false.

package com.olive.app.data.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.olive.app.BuildConfig
import com.olive.app.data.account.AccountRole
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.MessageOutboxStore
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.network.cloudStringMap
import com.olive.app.data.safety.DocumentIndexStore
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.SafetyAccessIndexStore
import com.olive.app.data.safety.SafetyDocumentRepository
import com.olive.app.data.safety.SafetyPlanRepository
import com.olive.app.data.safety.SafetyPlanStore
import com.olive.app.security.DeviceStore
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class PriorityReconciliationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        val uid = inputData.getString(INPUT_UID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid != uid) return Result.success()
        val checkpoints = SyncCheckpointStore(applicationContext)
        return try {
            val target = SyncInvalidationRepository().get(uid) ?: SyncRevisions()
            val runner = PriorityReconciliationRunner(
                checkpoints = checkpoints,
                activeUid = { auth.currentUser?.uid },
                reconcilers = mapOf(
                    SyncDomain.ACCOUNT to AccountAccessReconciler(auth),
                    SyncDomain.SAFETY to SafetyPlanReconciler(applicationContext, auth),
                    SyncDomain.MEDIA to DocumentAccessReconciler(applicationContext),
                    SyncDomain.CONVERSATION to ConversationAccessReconciler(applicationContext),
                ),
            )
            if (runner.reconcile(uid, target) == ReconciliationOutcome.COMPLETE) {
                SyncCompletionEvents.publish(uid)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FirebaseAuthInvalidUserException) {
            revoke(uid, checkpoints, auth)
        } catch (failure: FirebaseFirestoreException) {
            when (failure.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> try {
                    AccountAccessReconciler(auth).reconcile(uid)
                    Result.failure()
                } catch (_: FirebaseAuthInvalidUserException) {
                    revoke(uid, checkpoints, auth)
                } catch (_: Exception) {
                    Result.retry()
                }
                else -> Result.retry()
            }
        } catch (_: FirebaseNetworkException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /**
     * Revokes the priority reconciliation worker.
     *
     * @param uid Firebase UID of the target user account.
     * @param checkpoints Checkpoints supplied to [revoke].
     * @param auth Auth supplied to [revoke].
     * @return The resulting [Result] instance.
     */
    private fun revoke(
        uid: String,
        checkpoints: SyncCheckpointDataSource,
        auth: FirebaseAuth,
    ): Result {
        if (auth.currentUser?.uid?.let { it != uid } == true) return Result.success()
        purgeRevokedAccount(uid, checkpoints)
        if (auth.currentUser?.uid == uid) auth.signOut()
        AccountRevocationEvents.publish(uid)
        return Result.success()
    }

    /**
     * Purges revoked account.
     *
     * @param uid Firebase UID of the target user account.
     * @param checkpoints Checkpoints supplied to [purgeRevokedAccount].
     */
    private fun purgeRevokedAccount(uid: String, checkpoints: SyncCheckpointDataSource) {
        MessageOutboxStore(applicationContext).purge(uid)
        ChatCacheStore(applicationContext).purge(uid)
        DocumentIndexStore(applicationContext).purge(uid)
        OfflineMediaCache(applicationContext).purge(uid)
        SafetyPlanStore(applicationContext, false).purge(uid)
        SafetyAccessIndexStore(applicationContext).purge(uid)
        checkpoints.purge(uid)
        DeviceStore(
            EncryptedVault(applicationContext, "firebase"),
            EncryptedVault(applicationContext, "firebase-activity"),
        ).forgetDevice(expectedUid = uid)
    }

    companion object {
        private const val INPUT_UID = "uid"

        private val scheduledRevisions = ConcurrentHashMap<String, Long>()

        /**
         * Enqueues the priority reconciliation worker.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param uid Firebase UID of the target user account.
         * @param revision Optional revision supplied to [enqueue].
         * @param expedited Whether to apply expedited.
         */
        fun enqueue(
            context: Context,
            uid: String,
            revision: Long? = null,
            expedited: Boolean = false,
        ) {
            if (!BuildConfig.FIREBASE_CONFIGURED || uid.isBlank()) return
            if (revision != null) {
                var accepted = false
                scheduledRevisions.compute(uid) { _, previous ->
                    if (previous == null || revision > previous) {
                        accepted = true
                        revision
                    } else previous
                }
                if (!accepted) return
            }
            val builder = OneTimeWorkRequestBuilder<PriorityReconciliationWorker>()
                .setInputData(Data.Builder().putString(INPUT_UID, uid).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Older Android versions require a foreground service for expedited work.
            if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = builder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "olive-priority-reconciliation-$uid",
                // A running worker may already have read the previous server marker.
                if (revision != null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Cancels the priority reconciliation worker.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param uid Firebase UID of the target user account.
         */
        fun cancel(context: Context, uid: String) {
            scheduledRevisions.remove(uid)
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("olive-priority-reconciliation-$uid")
        }
    }
}

// Signed-in accounts: persists account-scoped reconciliation checkpoints in Keystore-protected storage.
internal class SyncCheckpointStore(
    private val vault: JsonVault,
) : SyncCheckpointDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "priority-sync"))

    /**
     * Reads the sync checkpoint store.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SyncCheckpoint] instance.
     */
    override fun read(uid: String): SyncCheckpoint {
        require(validUid(uid))
        val value = runCatching { vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }
            .getOrNull() ?: return SyncCheckpoint()
        return runCatching {
            SyncCheckpoint(
                initialized = value.optBoolean(INITIALIZED, false),
                revisions = SyncRevisions(
                    revision = value.nonNegative(REVISION),
                    account = value.nonNegative(ACCOUNT),
                    safety = value.nonNegative(SAFETY),
                    media = value.nonNegative(MEDIA),
                    conversation = value.nonNegative(CONVERSATION),
                ),
            )
        }.getOrElse {
            purge(uid)
            SyncCheckpoint()
        }
    }

    /**
     * Writes the sync checkpoint store.
     *
     * @param uid Firebase UID of the target user account.
     * @param checkpoint Checkpoint supplied to [write].
     */
    override fun write(uid: String, checkpoint: SyncCheckpoint) {
        require(validUid(uid))
        val revisions = checkpoint.revisions
        vault.update { root ->
            root.child(ACCOUNTS).put(uid, JSONObject()
                .put(INITIALIZED, checkpoint.initialized)
                .put(REVISION, revisions.revision)
                .put(ACCOUNT, revisions.account)
                .put(SAFETY, revisions.safety)
                .put(MEDIA, revisions.media)
                .put(CONVERSATION, revisions.conversation))
        }
    }

    /**
     * Purges the sync checkpoint store.
     *
     * @param uid Firebase UID of the target user account.
     */
    override fun purge(uid: String) {
        require(validUid(uid))
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    /**
     * Computes non negative for this JSON object.
     *
     * @param name Field, key, or display name.
     * @return The computed non negative numeric value.
     */
    private fun JSONObject.nonNegative(name: String): Long = getLong(name).also { require(it >= 0) }
    /**
     * Validates user ID.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting valid user ID.
     */
    private fun validUid(uid: String) = uid.isNotBlank() && uid.length <= 128

    private companion object {
        const val ACCOUNTS = "accounts"
        // Older versions could mark the baseline initialized before all domains completed.
        const val INITIALIZED = "baselineComplete"
        const val REVISION = "revision"
        const val ACCOUNT = "accountRevision"
        const val SAFETY = "safetyRevision"
        const val MEDIA = "mediaRevision"
        const val CONVERSATION = "conversationRevision"
    }
}

internal open class UidEventBus(extraBufferCapacity: Int) {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = extraBufferCapacity)
    val events = mutableEvents.asSharedFlow()

    /**
     * Publishes a user ID event to active collectors.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun publish(uid: String) {
        mutableEvents.tryEmit(uid)
    }
}

// Signed-in accounts: lets active screens reload encrypted local results without adding backend listeners.
internal object SyncCompletionEvents : UidEventBus(8)

// Signed-in accounts: closes an active private session after authoritative account revocation.
internal object AccountRevocationEvents : UidEventBus(1)

internal interface SyncCheckpointDataSource {
    /**
     * Reads the sync checkpoint data source.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SyncCheckpoint] instance.
     */
    fun read(uid: String): SyncCheckpoint
    /**
     * Writes the sync checkpoint data source.
     *
     * @param uid Firebase UID of the target user account.
     * @param checkpoint Checkpoint supplied to [write].
     */
    fun write(uid: String, checkpoint: SyncCheckpoint)
    /**
     * Purges the sync checkpoint data source.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun purge(uid: String)
}

internal data class SyncCheckpoint(
    val initialized: Boolean = false,
    val revisions: SyncRevisions = SyncRevisions(),
)

internal fun interface PriorityDomainReconciler {
    /**
     * Reconciles the priority domain reconciler.
     *
     * @param uid Firebase UID of the target user account.
     */
    suspend fun reconcile(uid: String)
}

internal enum class ReconciliationOutcome { COMPLETE, STALE_ACCOUNT }

internal class PriorityReconciliationRunner(
    private val checkpoints: SyncCheckpointDataSource,
    private val activeUid: () -> String?,
    private val reconcilers: Map<SyncDomain, PriorityDomainReconciler>,
) {
    /**
     * Reconciles the priority reconciliation runner.
     *
     * @param uid Firebase UID of the target user account.
     * @param target Target destination or output object to write to.
     * @return The resulting [ReconciliationOutcome] instance.
     */
    suspend fun reconcile(uid: String, target: SyncRevisions): ReconciliationOutcome {
        if (activeUid() != uid) return ReconciliationOutcome.STALE_ACCOUNT
        var checkpoint = checkpoints.read(uid)
        for (domain in ORDER) {
            if (activeUid() != uid) return ReconciliationOutcome.STALE_ACCOUNT
            val applied = checkpoint.revisions.value(domain)
            val requested = target.value(domain)
            if ((checkpoint.initialized || applied > 0) && requested <= applied) continue
            checkNotNull(reconcilers[domain]) { "Missing $domain reconciler" }.reconcile(uid)
            if (activeUid() != uid) return ReconciliationOutcome.STALE_ACCOUNT
            checkpoint = checkpoint.copy(revisions = checkpoint.revisions.withApplied(domain, requested))
            checkpoints.write(uid, checkpoint)
        }
        if (activeUid() != uid) return ReconciliationOutcome.STALE_ACCOUNT
        checkpoints.write(uid, checkpoint.copy(
            initialized = true,
            revisions = checkpoint.revisions.copy(revision = maxOf(checkpoint.revisions.revision, target.revision)),
        ))
        return ReconciliationOutcome.COMPLETE
    }

    private companion object {
        val ORDER = listOf(
            SyncDomain.ACCOUNT,
            SyncDomain.SAFETY,
            SyncDomain.MEDIA,
            SyncDomain.CONVERSATION,
        )
    }
}

// Signed-in accounts: validates the authoritative Firebase account before other protected data is refreshed.
internal class AccountAccessReconciler(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : PriorityDomainReconciler {
    /**
     * Reconciles the account access reconciler.
     *
     * @param uid Firebase UID of the target user account.
     */
    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        user.getIdToken(true).await()
    }
}

// Chat participants: refreshes the encrypted conversation index and purges revoked room access.
internal class ConversationAccessReconciler(
    context: Context,
    private val repository: MessagingRepository = MessagingRepository(),
) : PriorityDomainReconciler {
    private val cache = ChatCacheStore(context.applicationContext)

    /**
     * Reconciles the conversation access reconciler.
     *
     * @param uid Firebase UID of the target user account.
     */
    override suspend fun reconcile(uid: String) {
        val conversations = repository.listConversations(uid)
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Signed-in account changed" }
        cache.replaceIndex(uid, conversations, System.currentTimeMillis())
    }
}

// Authorized media viewers: refreshes metadata and purges revoked local ciphertext.
internal class DocumentAccessReconciler(
    context: Context,
) : PriorityDomainReconciler {
    private val application = context.applicationContext

    /**
     * Reconciles the document access reconciler.
     *
     * @param uid Firebase UID of the target user account.
     */
    override suspend fun reconcile(uid: String) {
        val snapshot = SafetyDocumentRepository(media = MediaRepository()).listFor(uid)
        check(snapshot.source == DocumentListSource.SERVER)
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Signed-in account changed" }
        val syncedAt = snapshot.lastSyncedAt ?: System.currentTimeMillis()
        DocumentIndexStore(application).replace(uid, snapshot.documents, syncedAt)
        OfflineMediaCache(application).reconcile(uid, snapshot.documents)
    }
}

// Safety-plan roles: refreshes authorized plans into the existing encrypted plan store.
internal class SafetyPlanReconciler(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: SafetyPlanRepository = SafetyPlanRepository(),
) : PriorityDomainReconciler {
    private val store = SafetyPlanStore(context.applicationContext, false)
    private val accessIndex = SafetyAccessIndexStore(context.applicationContext)

    /**
     * Reconciles the safety plan reconciler.
     *
     * @param uid Firebase UID of the target user account.
     */
    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        val role = AccountRole.fromStored(user.getIdToken(false).await().claims["role"] as? String ?: "")
        val owners = when (role) {
            AccountRole.USER -> listOf(uid)
            AccountRole.SAFE_CONTACT -> repository.sharedWithMe().also {
                accessIndex.replaceShared(uid, it)
            }.map { it.ownerUid }
            AccountRole.STAFF, AccountRole.ADMIN -> repository.consultations().also { result ->
                accessIndex.replaceConsultations(uid, result.first, result.second)
            }.second.map { it.ownerUid }
        }.distinct()
        check(auth.currentUser?.uid == uid) { "Signed-in account changed" }
        store.all(uid).filterNot { it.ownerUid in owners }.forEach { store.remove(uid, it.ownerUid) }
        for (owner in owners) {
            val remote = repository.get(owner) ?: continue
            check(auth.currentUser?.uid == uid) { "Signed-in account changed" }
            store.mergeRemote(uid, remote)
        }
    }
}

internal class PrioritySyncCoordinator(
    context: Context,
    private val repository: SyncInvalidationRepository? =
        if (BuildConfig.FIREBASE_CONFIGURED) runCatching { SyncInvalidationRepository() }.getOrNull() else null,
) {
    private val application = context.applicationContext
    private var activeUid: String? = null
    private var subscription: ChatSubscription? = null
    private var lastUrgentRevision = 0L

    /**
     * Starts priority sync coordinator.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun start(uid: String) {
        val repository = repository ?: return
        if (activeUid == uid && subscription != null) {
            PriorityReconciliationWorker.enqueue(application, uid)
            return
        }
        stop()
        activeUid = uid
        PriorityReconciliationWorker.enqueue(application, uid)
        subscription = repository.observe(
            uid,
            onUpdate = { revisions ->
                if (activeUid == uid) {
                    val urgent = revisions.urgent > lastUrgentRevision
                    lastUrgentRevision = maxOf(lastUrgentRevision, revisions.urgent)
                    PriorityReconciliationWorker.enqueue(
                        application,
                        uid,
                        revision = revisions.revision,
                        expedited = urgent,
                    )
                }
            },
            onError = { if (activeUid == uid) PriorityReconciliationWorker.enqueue(application, uid) },
        )
    }

    /**
     * Requests the priority sync coordinator.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun request(uid: String) {
        if (activeUid == uid) PriorityReconciliationWorker.enqueue(application, uid)
    }

    /**
     * Stops priority sync coordinator.
     */
    fun stop() {
        subscription?.close()
        subscription = null
        activeUid = null
        lastUrgentRevision = 0L
    }
}

// Signed-in accounts: reads the server-owned, payload-free reconciliation marker.
internal class SyncInvalidationRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /**
     * Returns the sync invalidation repository.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SyncRevisions], or `null` if unavailable.
     */
    suspend fun get(uid: String): SyncRevisions? {
        val snapshot = firestore.collection(COLLECTION).document(uid).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return syncRevisionsFrom(uid, snapshot.data)
            ?: error("Invalid reconciliation marker")
    }

    /**
     * Starts observing the sync invalidation repository.
     *
     * @param uid Firebase UID of the target user account.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(
        uid: String,
        onUpdate: (SyncRevisions) -> Unit,
        onError: (Exception) -> Unit,
    ): ChatSubscription {
        val listener = firestore.collection(COLLECTION).document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) {
                    onError(failure)
                    return@addSnapshotListener
                }
                if (snapshot?.exists() != true) return@addSnapshotListener
                val revisions = syncRevisionsFrom(uid, snapshot.data)
                if (revisions == null) onError(IllegalStateException("Invalid reconciliation marker"))
                else onUpdate(revisions)
            }
        return ChatSubscription { listener.remove() }
    }

    private companion object { const val COLLECTION = "syncInvalidations" }
}

// Signed-in accounts: validates content-free push events before any work is scheduled.
internal data class SyncInvalidation(val revision: Long, val urgent: Boolean)

/**
 * Synchronizes invalidation from.
 *
 * @param data Key-value mapping of data entries.
 * @return The resulting [SyncInvalidation], or `null` if unavailable.
 */
internal fun syncInvalidationFrom(data: Map<String, String>): SyncInvalidation? {
    if (data.keys != setOf("type", "revision", "urgent") || data["type"] != "sync_invalidation") return null
    val revision = data["revision"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val urgent = when (data["urgent"]) { "0" -> false; "1" -> true; else -> return null }
    return SyncInvalidation(revision, urgent)
}

// Signed-in accounts: describes content-free server revisions that require local reconciliation.
internal enum class SyncDomain { ACCOUNT, SAFETY, MEDIA, CONVERSATION }

internal data class SyncRevisions(
    val revision: Long = 0,
    val account: Long = 0,
    val safety: Long = 0,
    val media: Long = 0,
    val conversation: Long = 0,
    val urgent: Long = 0,
) {
    init {
        require(listOf(revision, account, safety, media, conversation, urgent).all { it >= 0 })
    }

    /**
     * Computes value.
     *
     * @param domain Domain supplied to [value].
     * @return The computed value numeric value.
     */
    fun value(domain: SyncDomain): Long = when (domain) {
        SyncDomain.ACCOUNT -> account
        SyncDomain.SAFETY -> safety
        SyncDomain.MEDIA -> media
        SyncDomain.CONVERSATION -> conversation
    }

    /**
     * Computes with applied.
     *
     * @param domain Domain supplied to [withApplied].
     * @param value Input value to store, parse, or format.
     * @return The resulting [SyncRevisions] for with applied.
     */
    fun withApplied(domain: SyncDomain, value: Long): SyncRevisions = when (domain) {
        SyncDomain.ACCOUNT -> copy(account = value)
        SyncDomain.SAFETY -> copy(safety = value)
        SyncDomain.MEDIA -> copy(media = value)
        SyncDomain.CONVERSATION -> copy(conversation = value)
    }
}

/**
 * Synchronizes revisions from.
 *
 * @param uid Firebase UID of the target user account.
 * @param data Key-value mapping of data entries.
 * @return The resulting [SyncRevisions], or `null` if unavailable.
 */
internal fun syncRevisionsFrom(uid: String, data: Map<String, Any?>?): SyncRevisions? {
    data ?: return null
    if (data["ownerUid"] != uid || data["allowedUsers"] != listOf(uid)) return null
    /**
     * Computes revision.
     *
     * @param name Field, key, or display name.
     * @return The resulting revision.
     */
    fun revision(name: String) = (data[name] as? Number)?.toLong()?.takeIf { it >= 0 }
    return SyncRevisions(
        revision = revision("revision") ?: return null,
        account = revision("accountRevision") ?: return null,
        safety = revision("safetyRevision") ?: return null,
        media = revision("mediaRevision") ?: return null,
        conversation = revision("conversationRevision") ?: return null,
        urgent = revision("urgentRevision") ?: return null,
    )
}
