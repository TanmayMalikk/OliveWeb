package com.olive.app.data.sync

import com.olive.app.security.JsonVault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncCheckpointStoreTest {
    @Test
    fun `checkpoint persistence isolates accounts and survives reconstruction`() {
        val vault = MemoryVault()
        SyncCheckpointStore(vault).apply {
            write("alice", SyncCheckpoint(true, SyncRevisions(9, 1, 2, 3, 4)))
            write("bob", SyncCheckpoint(true, SyncRevisions(8, 4, 3, 2, 1)))
        }

        val restored = SyncCheckpointStore(vault)
        assertEquals(SyncRevisions(9, 1, 2, 3, 4), restored.read("alice").revisions)
        assertEquals(SyncRevisions(8, 4, 3, 2, 1), restored.read("bob").revisions)
        restored.purge("alice")
        assertFalse(restored.read("alice").initialized)
        assertEquals(8L, restored.read("bob").revisions.revision)
    }

    @Test
    fun `corrupt checkpoint fails closed and can be rebuilt`() {
        val vault = MemoryVault(JSONObject("""{"accounts":{"alice":{"initialized":true,"revision":-1}}}"""))
        val store = SyncCheckpointStore(vault)

        assertEquals(SyncCheckpoint(), store.read("alice"))
        store.write("alice", SyncCheckpoint(true, SyncRevisions(4, 1, 1, 1, 1)))
        assertEquals(4L, store.read("alice").revisions.revision)
    }

    @Test
    fun `wide repeated writes retain exact monotonic values`() {
        val store = SyncCheckpointStore(MemoryVault())
        repeat(1_000) { value ->
            val revision = value.toLong() + 1
            store.write("alice", SyncCheckpoint(true, SyncRevisions(revision, revision, revision, revision, revision)))
            assertEquals(revision, store.read("alice").revisions.revision)
        }
    }

    private class MemoryVault(initial: JSONObject = JSONObject()) : JsonVault {
        private var value = JSONObject(initial.toString())
        override fun read() = JSONObject(value.toString())
        override fun update(change: (JSONObject) -> Unit) { value = read().also(change) }
        override fun clear() { value = JSONObject() }
    }
}
