package com.olive.app.data

import com.olive.app.data.safety.SafetyAccessIndexStore
import com.olive.app.data.safety.SafetyConsultation
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.security.JsonVault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAccessIndexStoreTest {
    @Test
    fun `access indexes isolate accounts and replace authoritative rows`() {
        val store = SafetyAccessIndexStore(MemoryVault())
        store.replaceConsultations("staff", false, listOf(
            SafetyConsultation("user-a", "A"), SafetyConsultation("user-a", "duplicate"),
        ))
        store.replaceShared("safe", listOf(SharedSafetyPlan("user-b", "B", 9)))

        assertEquals(false, store.read("staff").available)
        assertEquals(listOf("user-a"), store.read("staff").consultations.map { it.ownerUid })
        assertEquals(listOf("user-b"), store.read("safe").sharedPlans.map { it.ownerUid })
        assertTrue(store.read("staff").sharedPlans.isEmpty())

        store.purge("staff")
        assertTrue(store.read("staff").consultations.isEmpty())
        assertEquals(listOf("user-b"), store.read("safe").sharedPlans.map { it.ownerUid })
    }

    private class MemoryVault : JsonVault {
        private var value = JSONObject()
        override fun read() = JSONObject(value.toString())
        override fun update(change: (JSONObject) -> Unit) { value = read().also(change) }
        override fun clear() { value = JSONObject() }
    }
}
