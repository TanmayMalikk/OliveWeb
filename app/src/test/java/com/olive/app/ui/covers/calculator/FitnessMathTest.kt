// Tests step and calorie calculations for calculator cover users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessMathTest {
    @Test
    fun stepsProduceDistanceCaloriesAndActiveMinutes() {
        val profile = FitnessProfile(weightKg = 70f, heightCm = 170f, stepGoal = 8_000)

        assertEquals(0.7021f, FitnessMath.distanceKm(1_000, profile.heightCm), 0.0001f)
        assertEquals(36.86f, FitnessMath.calories(1_000, profile), 0.02f)
        assertEquals(10, FitnessMath.activeMinutes(1_000))
    }

    @Test
    fun walkSpeedAndPaceUseElapsedTime() {
        assertEquals(5f, FitnessMath.speedKmh(5_000f, 3_600_000L), 0.001f)
        assertEquals(12f, FitnessMath.paceMinutesPerKm(5_000f, 3_600_000L), 0.001f)
    }

    @Test
    fun invalidWalkValuesStayAtZero() {
        assertTrue(FitnessMath.speedKmh(0f, 0L) == 0f)
        assertTrue(FitnessMath.paceMinutesPerKm(0f, 0L) == 0f)
    }
}
