package com.olive.app.location

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.OnDisconnect
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
class AdminLocationServiceTest {
    @Test
    fun adminFeedRestoresTheActiveAccountThenMarksTheLocationOfflineWhenStopped() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val fused = mock(FusedLocationProviderClient::class.java)
        val database = mock(FirebaseDatabase::class.java)
        val root = mock(DatabaseReference::class.java)
        val feed = mock(DatabaseReference::class.java)
        val owner = mock(DatabaseReference::class.java)
        val disconnect = mock(OnDisconnect::class.java)
        `when`(database.reference).thenReturn(root)
        `when`(root.child("adminLiveLocations")).thenReturn(feed)
        `when`(feed.child("owner")).thenReturn(owner)
        `when`(owner.onDisconnect()).thenReturn(disconnect)
        var callback: LocationCallback? = null
        doAnswer { call ->
            callback = call.getArgument(1)
            null
        }.`when`(fused).requestLocationUpdates(
            any<LocationRequest>() ?: LocationRequest.Builder(1_000).build(),
            any<LocationCallback>() ?: object : LocationCallback() {},
            any<Looper>() ?: Looper.getMainLooper(),
        )

        mockStatic(LocationServices::class.java).use { locationServices ->
            locationServices.`when`<FusedLocationProviderClient> {
                LocationServices.getFusedLocationProviderClient(any<Context>())
            }.thenReturn(fused)
            mockStatic(FirebaseDatabase::class.java).use { firebase ->
                firebase.`when`<FirebaseDatabase> { FirebaseDatabase.getInstance(anyString()) }.thenReturn(database)
                val service = Robolectric.buildService(AdminLocationService::class.java).get()
                val resources = spy(service.resources)
                doReturn("Synthetic admin tracking").`when`(resources).getString(anyInt())
                val context = object : ContextWrapper(service.baseContext) {
                    override fun getResources() = resources
                }
                ReflectionHelpers.setField(service, "mBase", context)
                service.onCreate()
                try {
                    AdminLocationService.start(app, "owner", "Synthetic user")
                    assertFalse(AdminLocationService.active(app))
                    assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
                    assertTrue(AdminLocationService.active(app))
                    assertNull(service.onBind(null))
                    verify(disconnect).updateChildren(mapOf("active" to false))
                    val initial = Location("fixture").apply {
                        latitude = 53.0
                        longitude = -113.0
                        accuracy = 5f
                    }
                    callback!!.onLocationResult(LocationResult.create(listOf(initial)))
                    callback!!.onLocationResult(LocationResult.create(listOf(initial)))
                    callback!!.onLocationResult(LocationResult.create(listOf(Location(initial).apply { latitude = 53.001 })))
                    val published = mockingDetails(owner).invocations.filter { it.method.name == "setValue" }
                        .map { it.arguments.single() as Map<*, *> }
                    assertEquals(listOf(53.0, 53.001), published.map { it["latitude"] })
                    assertTrue(published.all { it["name"] == "Synthetic user" && it["active"] == true })
                    AdminLocationService.stop(app)
                    val shadow = shadowOf(app)
                    val first = shadow.nextStartedService
                    val stop = if (first?.action.isNullOrBlank()) shadow.nextStartedService else first
                    assertNotNull(stop)
                    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))
                    assertFalse(AdminLocationService.active(app))
                    verify(disconnect).cancel()
                    assertTrue(mockingDetails(owner).invocations.any {
                        it.method.name == "updateChildren" && (it.arguments[0] as Map<*, *>)["active"] == false
                    })
                } finally {
                    service.onDestroy()
                }
            }
        }
    }
    @Test
    fun emergencyStartWithoutLocationAccessNeverRequestsAForegroundService() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(app)
        app.getSharedPreferences("olive_admin_location", Context.MODE_PRIVATE).edit().clear().commit()
        shadow.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        AdminLocationService.start(app, "synthetic-owner", "Synthetic user")
        assertFalse(AdminLocationService.active(app))
        assertNull(shadow.nextStartedService)
        shadow.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        AdminLocationService.start(app, "synthetic-owner", "Synthetic user")
        assertFalse(AdminLocationService.active(app))
        assertNull(shadow.nextStartedService)
        shadow.grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        AdminLocationService.start(app, "synthetic-owner", "Synthetic user")
        assertNotNull(shadow.nextStartedService)
        // The UI must not try to stop a service before its foreground notification exists.
        assertFalse(AdminLocationService.active(app))
    }
}
