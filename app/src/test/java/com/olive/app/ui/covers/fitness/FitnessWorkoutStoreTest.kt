package com.olive.app.ui.covers.fitness

import com.olive.app.data.location.DeviceCoordinates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FitnessWorkoutStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val account = "workout-fixture"
    private val store = FitnessWorkoutStore(context, account)

    @Test
    fun workoutRestoresRouteAndExcludesPausedTimeFromStoredHistory() {
        store.start(OutdoorWorkoutType.RUN, now = 1000)
        store.addLocation(DeviceCoordinates(53.5, -113.5, 7f, 1500))
        store.addLocation(DeviceCoordinates(53.5002, -113.5, 5f, 5000))
        store.pause(now = 7000)
        store.pause(now = 8000)
        store.addLocation(DeviceCoordinates(53.5003, -113.5, 5f, 10000))
        val paused = FitnessWorkoutStore(context, account).snapshot()
        assertTrue(paused.active)
        assertTrue(paused.paused)
        assertEquals(2, paused.route.size)
        assertEquals(6000, paused.elapsedMillis(now = 20000))
        assertEquals(22.2, paused.distanceMeters, 0.3)

        store.resume(now = 12000)
        store.resume(now = 13000)
        store.finish(now = 18000)
        store.finish(now = 19000)

        val finished = FitnessWorkoutStore(context, account).history().single()
        assertEquals(OutdoorWorkoutType.RUN, finished.type)
        assertEquals(18000, finished.finishedAt)
        assertEquals(5000, finished.pausedDuration)
        assertEquals(12000, finished.elapsedMillis(now = 99999))
        assertEquals(paused.distanceMeters, finished.distanceMeters, 0.001)
        assertTrue(finished.route.isEmpty())
        assertTrue(FitnessWorkoutStore(context, "other-account").history().isEmpty())
    }

    @Test
    fun staleInaccurateAndImpossibleFixesDoNotIncreaseDistance() {
        store.addLocation(DeviceCoordinates(53.5, -113.5, 7f, 2000))
        assertTrue(store.snapshot().route.isEmpty())
        store.start(OutdoorWorkoutType.WALK, now = 1000)
        store.addLocation(DeviceCoordinates(53.5, -113.5, 61f, 2000))
        assertTrue(store.snapshot().route.isEmpty())
        store.addLocation(DeviceCoordinates(53.5, -113.5, null, 2500))
        store.addLocation(DeviceCoordinates(53.5002, -113.5, 4f, 2500))
        store.addLocation(DeviceCoordinates(53.7, -113.5, 4f, 3000))
        assertEquals(1, store.snapshot().route.size)
        assertEquals(0.0, store.snapshot().distanceMeters, 0.0)
        store.addLocation(DeviceCoordinates(53.5002, -113.5, 4f, 4000))
        assertEquals(2, store.snapshot().route.size)
        assertTrue(store.snapshot().distanceMeters > 20.0)
    }

    @Test
    fun historyUsesLegacySummaryAndKeepsOnlyFiveWithoutTheLocationTrail() {
        val preferences = fitnessPreferences(context, account, workout = true)
        preferences.edit().putString("type", OutdoorWorkoutType.RUN.storedName)
            .putLong("started_at", 500).putLong("finished_at", 1000)
            .putString("route", """[{"lat":53.5,"lon":-113.5,"at":700}]""").commit()
        assertEquals(1, store.snapshot().route.size)
        assertEquals(1000, store.history().single().finishedAt)
        assertTrue(store.history().single().route.isEmpty())

        for (startedAt in 2000L..7000L step 1000) {
            store.start(OutdoorWorkoutType.WALK, now = startedAt)
            store.pause(now = startedAt + 400)
            store.finish(now = startedAt + 700)
        }
        assertEquals(listOf(7700L, 6700L, 5700L, 4700L, 3700L), store.history().map { it.finishedAt })
        assertTrue(store.history().all { it.elapsedMillis() == 400L && it.route.isEmpty() })
        preferences.edit().putString("workout_history", "corrupted").putString("route", "invalid").commit()
        assertTrue(store.snapshot().route.isEmpty())
        assertEquals(7700, store.history().single().finishedAt)
    }
}
