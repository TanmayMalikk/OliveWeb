package com.olive.app.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityReconciliationRunnerTest {
    @Test
    fun `first run reconciles every domain in security order`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.runner.reconcile(OWNER, SyncRevisions(4, 1, 1, 1, 1))

        assertEquals(ReconciliationOutcome.COMPLETE, result)
        assertEquals(SyncDomain.entries, fixture.calls)
        assertEquals(SyncRevisions(4, 1, 1, 1, 1), fixture.store.read(OWNER).revisions)
    }

    @Test
    fun `only advanced domains reconcile on later runs`() = runBlocking {
        val fixture = Fixture(SyncCheckpoint(true, SyncRevisions(4, 2, 2, 2, 2)))
        fixture.runner.reconcile(OWNER, SyncRevisions(6, 2, 5, 2, 3))

        assertEquals(listOf(SyncDomain.SAFETY, SyncDomain.CONVERSATION), fixture.calls)
        assertEquals(SyncRevisions(6, 2, 5, 2, 3), fixture.store.read(OWNER).revisions)
    }

    @Test
    fun `failure checkpoints earlier domains but never overtakes failed domain`() = runBlocking {
        val fixture = Fixture(failAt = SyncDomain.MEDIA)
        val failure = runCatching {
            fixture.runner.reconcile(OWNER, SyncRevisions(4, 1, 1, 1, 1))
        }.exceptionOrNull()

        assertTrue(failure is TemporarySyncFailure)
        assertEquals(listOf(SyncDomain.ACCOUNT, SyncDomain.SAFETY, SyncDomain.MEDIA), fixture.calls)
        assertEquals(SyncRevisions(0, 1, 1, 0, 0), fixture.store.read(OWNER).revisions)
    }

    @Test
    fun `account switch stops queued work without touching either account`() = runBlocking {
        val fixture = Fixture(activeUid = "bob")
        assertEquals(
            ReconciliationOutcome.STALE_ACCOUNT,
            fixture.runner.reconcile(OWNER, SyncRevisions(4, 1, 1, 1, 1)),
        )
        assertTrue(fixture.calls.isEmpty())
        assertEquals(SyncCheckpoint(), fixture.store.read(OWNER))
    }

    @Test
    fun `account switch during reconciliation stops all later domains`() = runBlocking {
        var active = OWNER
        val calls = mutableListOf<SyncDomain>()
        val store = MemoryCheckpoints(SyncCheckpoint())
        val runner = PriorityReconciliationRunner(
            store,
            activeUid = { active },
            reconcilers = SyncDomain.entries.associateWith { domain -> PriorityDomainReconciler {
                calls += domain
                if (domain == SyncDomain.SAFETY) active = "bob"
            } },
        )

        assertEquals(
            ReconciliationOutcome.STALE_ACCOUNT,
            runner.reconcile(OWNER, SyncRevisions(4, 1, 1, 1, 1)),
        )
        assertEquals(listOf(SyncDomain.ACCOUNT, SyncDomain.SAFETY), calls)
        assertEquals(SyncRevisions(0, 1, 1, 0, 0), store.read(OWNER).revisions)
    }

    @Test
    fun `repeated duplicate invalidations become deterministic no ops`() = runBlocking {
        val fixture = Fixture()
        val target = SyncRevisions(40, 10, 20, 30, 40)
        repeat(1_000) { fixture.runner.reconcile(OWNER, target) }

        assertEquals(SyncDomain.entries, fixture.calls)
        assertEquals(target, fixture.store.read(OWNER).revisions)
    }

    private class Fixture(
        checkpoint: SyncCheckpoint = SyncCheckpoint(),
        activeUid: String = OWNER,
        failAt: SyncDomain? = null,
    ) {
        val calls = mutableListOf<SyncDomain>()
        val store = MemoryCheckpoints(checkpoint)
        val runner = PriorityReconciliationRunner(
            store,
            activeUid = { activeUid },
            reconcilers = SyncDomain.entries.associateWith { domain ->
                PriorityDomainReconciler {
                    calls += domain
                    if (domain == failAt) throw TemporarySyncFailure()
                }
            },
        )
    }

    private class MemoryCheckpoints(initial: SyncCheckpoint) : SyncCheckpointDataSource {
        private val values = mutableMapOf(OWNER to initial)
        override fun read(uid: String) = values[uid] ?: SyncCheckpoint()
        override fun write(uid: String, checkpoint: SyncCheckpoint) { values[uid] = checkpoint }
        override fun purge(uid: String) { values.remove(uid) }
    }

    private class TemporarySyncFailure : IllegalStateException()
    private companion object { const val OWNER = "alice" }
}
