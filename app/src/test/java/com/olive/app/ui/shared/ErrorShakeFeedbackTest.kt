// Unit tests verifying error shake keyframes, repeat-attempt triggers, and session error versioning.
package com.olive.app.ui.shared

import com.olive.app.ui.session.SessionState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorShakeFeedbackTest {
    @Test
    fun keyframesOscillateWithDampedAmplitudeAndReturnToRest() {
        val frames = errorShakeKeyframes(amplitudeDp = 14f)
        assertTrue(frames.size >= 5)
        assertEquals(0f to 0, frames.first())
        assertEquals(0f to ERROR_SHAKE_DURATION_MS, frames.last())

        val displacements = frames.map { (offset, _) -> abs(offset) }
        assertEquals(14f, displacements.maxOrNull() ?: 0f, 0.001f)

        // Pairwise negative/positive swings decay monotonically after the initial peak.
        val peakPairs = frames.drop(1).dropLast(1).chunked(2).map { pair -> pair.maxOf { abs(it.first) } }
        for (i in 1 until peakPairs.size) {
            assertTrue("Expected swing $i to decay from ${peakPairs[i - 1]} to ${peakPairs[i]}", peakPairs[i] < peakPairs[i - 1])
        }

        // Timestamps strictly increase up to ERROR_SHAKE_DURATION_MS.
        val times = frames.map { it.second }
        assertEquals(times.sorted().distinct(), times)
    }

    @Test
    fun shouldTriggerFieldErrorShakeHandlesMountRetriesAndClearing() {
        // Initial mount without an error never shakes.
        assertFalse(shouldTriggerFieldErrorShake(null, null, 0, 0))
        assertFalse(shouldTriggerFieldErrorShake("Required", "Required", 1, 1))

        // First error appearance shakes even if trigger counter did not increment yet.
        assertTrue(shouldTriggerFieldErrorShake(null, "Required", 0, 0))

        // Different error message on the same field shakes again.
        assertTrue(shouldTriggerFieldErrorShake("Required", "Must be 8+ chars", 1, 1))

        // Repeated submit attempts while the same error text remains set re-trigger the shake.
        assertTrue(shouldTriggerFieldErrorShake("Required", "Required", 1, 2))
        assertTrue(shouldTriggerFieldErrorShake("Required", "Required", 2, 3))

        // Clearing an error does not shake.
        assertFalse(shouldTriggerFieldErrorShake("Required", null, 3, 4))
    }

    @Test
    fun sessionStateInputErrorCounterAdvancesMonotonically() {
        val state = SessionState { true }
        assertEquals(0, state.inputErrorVersion)

        state.recordInputError()
        assertEquals(1, state.inputErrorVersion)

        state.failInput("Incorrect PIN")
        assertEquals(2, state.inputErrorVersion)
        assertEquals("Incorrect PIN", state.message)
        assertEquals(2, state.accountState.value.inputErrorVersion)

        // Calling failInput again with the identical message still bumps the counter for repeat shakes.
        state.failInput("Incorrect PIN")
        assertEquals(3, state.inputErrorVersion)
    }
}
