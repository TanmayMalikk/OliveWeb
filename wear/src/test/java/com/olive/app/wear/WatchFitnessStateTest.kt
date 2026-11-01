// Watch fitness tests: verifies that phone and watch steps are not added together twice.
package com.olive.app.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFitnessStateTest {
    @Test
    fun displayedStepsUsesWatchWhenWatchHasHigherTotal() {
        val state = WatchFitnessState(watchSteps = 6_000, phoneSteps = 5_000)
        assertEquals(6_000, state.displayedSteps)
    }

    @Test
    fun displayedStepsUsesPhoneWhenPhoneHasHigherTotal() {
        val state = WatchFitnessState(watchSteps = 4_000, phoneSteps = 7_000)
        assertEquals(7_000, state.displayedSteps)
    }
}
