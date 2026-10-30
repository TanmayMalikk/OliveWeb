// Tests step and calorie calculations for calculator cover users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessMathTest {
    // Inputs: the values shown below. Expected: steps produce distance calories and active minutes.
    @Test
    fun stepsProduceDistanceCaloriesAndActiveMinutes() {
        val profile = FitnessProfile(weightKg = 70f, heightCm = 170f, stepGoal = 8_000)

        assertEquals(0.7021f, FitnessMath.distanceKm(1_000, profile.heightCm), 0.0001f)
        assertEquals(36.86f, FitnessMath.calories(1_000, profile), 0.02f)
        assertEquals(10, FitnessMath.activeMinutes(1_000))
    }

    // Inputs: the values shown below. Expected: walk speed and pace use elapsed time.
    @Test
    fun walkSpeedAndPaceUseElapsedTime() {
        assertEquals(5f, FitnessMath.speedKmh(5_000f, 3_600_000L), 0.001f)
        assertEquals(12f, FitnessMath.paceMinutesPerKm(5_000f, 3_600_000L), 0.001f)
    }

    // Inputs: the values shown below. Expected: invalid walk values stay at zero.
    @Test
    fun invalidWalkValuesStayAtZero() {
        assertTrue(FitnessMath.speedKmh(0f, 0L) == 0f)
        assertTrue(FitnessMath.paceMinutesPerKm(0f, 0L) == 0f)
    }

    // Inputs: the values shown below. Expected: hostile profile and step values are clamped to safe ranges.
    @Test
    fun hostileProfileAndStepValuesAreClampedToSafeRanges() {
        assertEquals(0f, FitnessMath.distanceKm(-10_000, 170f), 0f)
        assertEquals(FitnessMath.strideMeters(100f), FitnessMath.strideMeters(-1_000f), 0f)
        assertEquals(FitnessMath.strideMeters(230f), FitnessMath.strideMeters(10_000f), 0f)
        assertEquals(0, FitnessMath.activeMinutes(Int.MIN_VALUE))

        val tooLight = FitnessProfile(weightKg = -50f, heightCm = 170f)
        val minimum = FitnessProfile(weightKg = 30f, heightCm = 170f)
        assertEquals(FitnessMath.calories(1_000, minimum), FitnessMath.calories(1_000, tooLight), 0f)
        assertEquals(0f, FitnessMath.speedKmh(100f, -1L), 0f)
        assertEquals(0f, FitnessMath.paceMinutesPerKm(-10f, 1_000L), 0f)
    }
}
