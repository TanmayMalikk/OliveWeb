package com.olive.app.data.location

import android.Manifest
import android.content.Context
import android.location.*
import android.os.Looper
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.CancellationToken
import com.olive.app.data.account.DeliveryAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DeviceLocationApiTest {
    private fun fixture(action:(Context,FusedLocationProviderClient,LocationManager)->Unit) {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        val context=spy(app)
        doReturn(context).`when`(context).applicationContext
        val manager=mock(LocationManager::class.java)
        doReturn(manager).`when`(context).getSystemService(LocationManager::class.java)
        `when`(manager.isLocationEnabled).thenReturn(true)
        val fused=mock(FusedLocationProviderClient::class.java)
        mockStatic(LocationServices::class.java).use { services ->
            services.`when`<FusedLocationProviderClient> { LocationServices.getFusedLocationProviderClient(any<Context>()) }.thenReturn(fused)
            action(context,fused,manager)
        }
    }
    @Test
    fun locationUpdatesExposeAllFreshSamplesAndStoppingRemovesTheExactCallback()=fixture { context,fused,_ ->
        var callback:LocationCallback?=null
        `when`(fused.requestLocationUpdates(any<LocationRequest>() ?: LocationRequest.Builder(1000).build(),any<LocationCallback>() ?: object:LocationCallback(){},any<Looper>() ?: Looper.getMainLooper())).thenAnswer {
            callback=it.getArgument(1);Tasks.forResult<Void>(null)
        }
        val points=mutableListOf<DeviceCoordinates>()
        val updates=DeviceLocation.startUpdates(context,2000,3f) { points+=it }!!
        val point=Location("fixture").apply { latitude=53.0;longitude=-113.0;accuracy=4f;time=100 }
        callback!!.onLocationResult(LocationResult.create(listOf(point,Location(point).apply { latitude=53.1;time=200 })))
        assertEquals(2,points.size)
        assertEquals(53.1,points.last().latitude,0.0)
        assertEquals(4f,points.first().accuracy)
        updates.stop()
        verify(fused).removeLocationUpdates(callback!!)
    }
    @Test
    fun revokedPermissionsAndDisabledLocationDoNotSubscribeToGps()=fixture { context,fused,manager ->
        `when`(manager.isLocationEnabled).thenReturn(false)
        assertNull(DeviceLocation.startUpdates(context) {})
        runBlocking { assertNull(DeviceLocation.currentCoordinates(context)) }
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(DeviceLocation.startUpdates(context) {})
        runBlocking { assertNull(DeviceLocation.currentCoordinates(context));assertNull(DeviceLocation.address(context)) }
        verifyNoInteractions(fused)
    }
    @Test
    fun oneOffCoordinatesUseAFreshFusedFixWithoutGeocodingIt()=fixture { context,fused,_ ->
        val point=Location("fixture").apply { latitude=53.0;longitude=-113.0;time=100 }
        `when`(fused.getCurrentLocation(any<CurrentLocationRequest>() ?: CurrentLocationRequest.Builder().build(),any<CancellationToken>())).thenReturn(Tasks.forResult(point))
        val coordinates=runBlocking { DeviceLocation.currentCoordinates(context) }!!
        assertEquals(53.0,coordinates.latitude,0.0)
        assertEquals(100L,coordinates.timestamp)
        assertNull(coordinates.accuracy)
    }

    @Test
    fun fallbackChoosesAFreshAccurateCachedSampleWhenPlayServicesAndGpsFail() = fixture { context, fused, manager ->
        val now = System.currentTimeMillis()
        fun fix(provider: String, latitude: Double, age: Long, accuracy: Float) = Location(provider).apply {
            this.latitude = latitude
            longitude = -113.5
            time = now - age
            this.accuracy = accuracy
        }
        val gps = LocationManager.GPS_PROVIDER
        val network = LocationManager.NETWORK_PROVIDER
        val recentGps = fix(gps, 53.0, 12_000, 9f)
        val recentNetwork = fix(network, 53.2, 1_000, 120f)
        val stale = fix("stale", 52.2, 360_000, 1f)
        `when`(fused.getCurrentLocation(any<CurrentLocationRequest>() ?: CurrentLocationRequest.Builder().build(), any<CancellationToken>()))
            .thenReturn(Tasks.forException(IllegalStateException("Synthetic service failure")))
        `when`(manager.isProviderEnabled(gps)).thenReturn(true)
        doThrow(SecurityException("Synthetic provider failure")).`when`(manager)
            .getCurrentLocation(eq(gps), any(), any(java.util.concurrent.Executor::class.java), any())
        `when`(manager.getProviders(true)).thenReturn(listOf(gps, network, "stale", "revoked"))
        `when`(manager.getLastKnownLocation(gps)).thenReturn(recentGps)
        `when`(manager.getLastKnownLocation(network)).thenReturn(recentNetwork)
        `when`(manager.getLastKnownLocation("stale")).thenReturn(stale)
        `when`(manager.getLastKnownLocation("revoked")).thenThrow(SecurityException("Synthetic cache revocation"))

        assertEquals(DeviceCoordinates(53.0, -113.5, 9f, recentGps.time), runBlocking { DeviceLocation.currentCoordinates(context) })
        `when`(manager.getProviders(true)).thenReturn(listOf("stale", "revoked"))
        assertNull(runBlocking { DeviceLocation.currentCoordinates(context) })
    }

    @Test
    fun coarseOnlySubscriptionsUseBalancedLocationWithoutInventingAccuracy() = fixture { context, fused, _ ->
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val received = mutableListOf<DeviceCoordinates>()
        var priority = -1
        `when`(fused.requestLocationUpdates(any<LocationRequest>() ?: LocationRequest.Builder(1_000).build(),
            any<LocationCallback>() ?: object : LocationCallback() {}, any<Looper>() ?: Looper.getMainLooper())).thenAnswer {
            priority = it.getArgument<LocationRequest>(0).priority
            it.getArgument<LocationCallback>(1).onLocationResult(LocationResult.create(listOf(Location("network").apply {
                latitude = 53.2; longitude = -113.5; time = 123
            })))
            Tasks.forResult<Void>(null)
        }
        assertNotNull(DeviceLocation.startUpdates(context) { received += it })
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, priority)
        assertEquals(listOf(DeviceCoordinates(53.2, -113.5, null, 123)), received)
    }
    @Test
    fun addressLookupsSupportForwardAndReverseCallbacksAndGracefullyHandleAnEmptyResult()=fixture { context,fused,_ ->
        val point=Location("fixture").apply { latitude=53.0;longitude=-113.0;time=100 }
        `when`(fused.getCurrentLocation(any<CurrentLocationRequest>() ?: CurrentLocationRequest.Builder().build(),any<CancellationToken>())).thenReturn(Tasks.forResult(point))
        val address=Address(Locale.CANADA).apply { latitude=53.0;longitude=-113.0;locality="Synthetic city";adminArea="Alberta";countryCode="CA" }
        var matches=true
        mockStatic(Geocoder::class.java,CALLS_REAL_METHODS).use { geocoder ->
            geocoder.`when`<Boolean> { Geocoder.isPresent() }.thenReturn(true)
            mockConstruction(Geocoder::class.java) { instance,_ ->
                doAnswer { call -> call.getArgument<Geocoder.GeocodeListener>(2).onGeocode(if(matches) mutableListOf(address) else mutableListOf());null }
                    .`when`(instance).getFromLocationName(anyString(),anyInt(),any<Geocoder.GeocodeListener>())
                doAnswer { call -> call.getArgument<Geocoder.GeocodeListener>(3).onGeocode(if(matches) mutableListOf(address) else mutableListOf());null }
                    .`when`(instance).getFromLocation(anyDouble(),anyDouble(),anyInt(),any<Geocoder.GeocodeListener>())
            }.use {
                assertEquals(53.0 to -113.0,runBlocking { DeviceLocation.coordinatesForAddress(context,DeliveryAddress(city="Synthetic city")) })
                assertEquals("Synthetic city",runBlocking { DeviceLocation.address(context) }?.city)
                matches=false
                assertNull(runBlocking { DeviceLocation.address(context) })
                assertNull(runBlocking { DeviceLocation.coordinatesForAddress(context,DeliveryAddress(city="Missing")) })
            }
        }
    }
}
