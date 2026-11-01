// Fitness workout tests: checks GPS distance, pace, and elapsed-time edge cases.
package com.olive.app.ui.covers.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitnessWorkoutTrackingTest {
    // Uses one degree of latitude and expects the standard distance of about 111.2 kilometres.
    @Test
    fun distanceUsesGeographicCoordinates() {
        val first = WorkoutRoutePoint(53.5461, -113.4938, 1_000L)
        val second = WorkoutRoutePoint(54.5461, -113.4938, 2_000L)

        assertEquals(111_195.0, FitnessWorkoutMath.distanceMeters(first, second), 150.0)
    }

    // Uses a five-kilometre workout lasting 25 minutes and expects a five-minute pace.
    @Test
    fun paceUsesElapsedTimeAndDistance() {
        assertEquals(
            5.0,
            FitnessWorkoutMath.paceMinutesPerKilometre(5_000.0, 25L * 60L * 1_000L)!!,
            0.001,
        )
    }

    // Uses a movement shorter than ten metres and expects no unreliable pace estimate.
    @Test
    fun paceWaitsForEnoughDistance() {
        assertNull(FitnessWorkoutMath.paceMinutesPerKilometre(9.0, 60_000L))
    }

    // Pauses after ten minutes and checks that later clock time does not increase workout time.
    @Test
    fun pausedWorkoutFreezesElapsedTime() {
        val workout = OutdoorWorkoutSnapshot(
            active = true,
            paused = true,
            startedAt = 1_000L,
            pausedAt = 601_000L,
        )

        assertEquals(600_000L, workout.elapsedMillis(now = 1_201_000L))
    }

    // Adds a sixth completed workout and expects only the newest five distinct results to remain.
    @Test
    fun recentWorkoutHistoryKeepsNewestFive() {
        val existing = (1L..5L).map { finishedAt ->
            OutdoorWorkoutSnapshot(finishedAt = finishedAt)
        }
        val newest = OutdoorWorkoutSnapshot(finishedAt = 6L)

        val history = FitnessWorkoutMath.keepRecentWorkouts(existing, newest)

        assertEquals(listOf(6L, 1L, 2L, 3L, 4L), history.map { it.finishedAt })
    }
}
