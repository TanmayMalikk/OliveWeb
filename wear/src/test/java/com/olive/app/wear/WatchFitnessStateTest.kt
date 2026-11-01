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

    // Records three readings and checks every value shown on the watch Vitals tab.
    @Test
    fun heartRateSummaryCalculatesDailyVitals() {
        val summary = WatchHeartRateSummary()
            .record(bpm = 72, resting = true)
            .record(bpm = 104, resting = false)
            .record(bpm = 91, resting = true)

        assertEquals(104, summary.peakBpm)
        assertEquals(72, summary.lowestBpm)
        assertEquals(89, summary.averageBpm)
        assertEquals(81, summary.restingBpm)
    }

    // Sends impossible sensor values and checks that they cannot change the daily summary.
    @Test
    fun heartRateSummaryRejectsValuesOutsideSensorRange() {
        val original = WatchHeartRateSummary().record(bpm = 80, resting = true)

        assertEquals(original, original.record(bpm = 29, resting = false))
        assertEquals(original, original.record(bpm = 241, resting = false))
    }

    // Supplies negative totals and checks that corrupted persisted steps cannot appear in the UI.
    @Test
    fun displayedStepsNeverReturnsNegativeData() {
        assertEquals(0, WatchFitnessState(watchSteps = -10, phoneSteps = -20).displayedSteps)
    }

    // Supplies one valid edge reading and checks that the supported lower boundary is retained.
    @Test
    fun heartRateSummaryAcceptsLowerSensorBoundary() {
        val summary = WatchHeartRateSummary().record(bpm = 30, resting = true)
        assertEquals(30, summary.lowestBpm)
        assertEquals(30, summary.averageBpm)
        assertEquals(30, summary.restingBpm)
    }

    // Starts at numeric limits and checks that another reading cannot overflow stored totals.
    @Test
    fun heartRateSummaryRejectsOverflowingCounters() {
        val full = WatchHeartRateSummary(
            peakBpm = 100,
            lowestBpm = 60,
            totalBpm = Long.MAX_VALUE,
            sampleCount = Int.MAX_VALUE,
        )
        assertEquals(full, full.record(bpm = 80, resting = false))
    }
}
