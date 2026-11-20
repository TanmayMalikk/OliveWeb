// Receives and coordinates priority sync invalidations.
package com.olive.app.data.sync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.BuildConfig
import kotlinx.coroutines.tasks.await

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
