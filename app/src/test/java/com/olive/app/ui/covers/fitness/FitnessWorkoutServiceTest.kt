package com.olive.app.ui.covers.fitness

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitnessWorkoutServiceTest {
    @Test
    fun workoutServiceRecordsGpsPausesAndRestoresThePausedWorkoutBeforeFinishing() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        val gps = mock(FusedLocationProviderClient::class.java)
        mockStatic(LocationServices::class.java).use { location ->
            location.`when`<FusedLocationProviderClient> {
                LocationServices.getFusedLocationProviderClient(any<Context>())
            }.thenReturn(gps)
            val service = service()
            val account = "synthetic-workout"
            val store = FitnessWorkoutStore(app, account)
            fun command(action: String) = Intent(action)
                .putExtra(FitnessWorkoutService.EXTRA_ACCOUNT_ID, account)
                .putExtra(FitnessWorkoutService.EXTRA_TYPE, OutdoorWorkoutType.entries.first().storedName)
            assertEquals(Service.START_STICKY, service.onStartCommand(command(FitnessWorkoutService.ACTION_START), 0, 1))
            assertTrue(store.snapshot().active)
            val callback = mockingDetails(gps).invocations.single { it.method.name == "requestLocationUpdates" }
                .arguments.filterIsInstance<LocationCallback>().single()
            val now = System.currentTimeMillis()
            callback.onLocationResult(LocationResult.create(listOf(Location("fixture").apply {
                latitude = 53.1
                longitude = -113.0
                accuracy = 4f
                time = now
            })))
            assertEquals(1, store.snapshot().route.size)
            service.onStartCommand(command(FitnessWorkoutService.ACTION_PAUSE), 0, 2)
            assertTrue(store.snapshot().paused)
            verify(gps).removeLocationUpdates(callback)
            service.onDestroy()

            val restored = service()
            assertEquals(Service.START_STICKY, restored.onStartCommand(null, 0, 3))
            assertTrue(store.snapshot().paused)
            restored.onStartCommand(command(FitnessWorkoutService.ACTION_RESUME), 0, 4)
            assertFalse(store.snapshot().paused)
            assertEquals(2, mockingDetails(gps).invocations.count { it.method.name == "requestLocationUpdates" })
            restored.onStartCommand(command(FitnessWorkoutService.ACTION_FINISH), 0, 5)
            assertFalse(store.snapshot().active)
            assertEquals(1, store.history().size)
            assertNull(restored.onBind(null))
            restored.onDestroy()
        }
    }

    @Test
    fun noSavedAccountStopsTheServiceWithoutStartingTracking() {
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        service.onDestroy()
    }

    private fun service(): FitnessWorkoutService {
        val service = Robolectric.buildService(FitnessWorkoutService::class.java).get()
        val resources = spy(service.resources)
        doReturn("Synthetic workout").`when`(resources).getString(anyInt())
        ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(service.baseContext) {
            override fun getResources() = resources
        })
        service.onCreate()
        return service
    }
}
