package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifecycleResultGateTest {
    @Test
    fun `stopped activity defers a result until resume exactly once`() {
        val gate = LifecycleResultGate<String>()
        assertNull(gate.offer("credential", resumed = false))
        assertEquals("credential", gate.resume())
        assertNull(gate.resume())
    }

    @Test
    fun `resumed activity delivers immediately across repeated ceremonies`() {
        val gate = LifecycleResultGate<Int>()
        repeat(100) { value ->
            assertEquals(value, gate.offer(value, resumed = true))
            assertNull(gate.resume())
        }
    }

    @Test
    fun `replacement and cancellation discard deferred secret-bearing results`() {
        val discarded = mutableListOf<String>()
        val gate = LifecycleResultGate<String>(discarded::add)
        gate.offer("first", resumed = false)
        gate.offer("second", resumed = false)
        gate.cancel()
        assertEquals(listOf("first", "second"), discarded)
        assertNull(gate.resume())
    }
}
