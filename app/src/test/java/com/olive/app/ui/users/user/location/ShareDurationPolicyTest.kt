// Tests that user location sharing rejects invalid time values before saving them.
package com.olive.app.ui.users.user.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareDurationPolicyTest {
    // Confirms the test-only quick option is exactly ten seconds.
    @Test
    fun testDurationIsTenSeconds() {
        assertEquals(10_000L, ShareDurationPolicy.TEST_DURATION_MILLISECONDS)
    }

    // Confirms the custom duration accepts its smallest and largest supported hour values.
    @Test
    fun customHoursAcceptBothRangeEdges() {
        assertEquals(3_600_000L, ShareDurationPolicy.customDurationMilliseconds("1"))
        assertEquals(259_200_000L, ShareDurationPolicy.customDurationMilliseconds("72"))
    }

    // Confirms zero, negative, over-limit, malformed, and overflowing input are rejected.
    @Test
    fun customHoursRejectInvalidAndOverflowingValues() {
        listOf("0", "-1", "73", "abc", "999999999999999999999999").forEach { value ->
            assertNull("Expected '$value' to be rejected", ShareDurationPolicy.customDurationMilliseconds(value))
        }
    }
}
