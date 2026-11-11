// Signed-in accounts: applies authoritative domain refreshes in a deterministic order.
package com.olive.app.data.sync

internal interface SyncCheckpointDataSource {
    fun read(uid: String): SyncCheckpoint
    fun write(uid: String, checkpoint: SyncCheckpoint)
    fun purge(uid: String)
}

internal data class SyncCheckpoint(
    val initialized: Boolean = false,
    val revisions: SyncRevisions = SyncRevisions(),
)

internal fun interface PriorityDomainReconciler {
    suspend fun reconcile(uid: String)
}

internal enum class ReconciliationOutcome { COMPLETE, STALE_ACCOUNT }

internal class PriorityReconciliationRunner(
    private val checkpoints: SyncCheckpointDataSource,
    private val activeUid: () -> String?,
    private val reconcilers: Map<SyncDomain, PriorityDomainReconciler>,
) {
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

