package com.olive.app.location

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.*
import com.olive.app.security.EncryptionUtils
import com.olive.app.support.TestAndroidKeyStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveLocationServiceLifecycleTest {
    @Test
    fun sharingPublishesEncryptedMovementThrottlesStationaryUpdatesAndStopsOnCommand() {
        TestAndroidKeyStore.install().use {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val fused = mock(FusedLocationProviderClient::class.java)
            val database = mock(FirebaseDatabase::class.java)
            val refs = mutableMapOf<String, DatabaseReference>()
            val updates = mutableListOf<Map<String, Any?>>()
            val disconnect = mock(OnDisconnect::class.java)
            fun ref(path: String): DatabaseReference = refs.getOrPut(path) {
                mock(DatabaseReference::class.java, Answer { call -> when(call.method.name) {
                    "child" -> ref(path + "/" + call.getArgument<String>(0))
                    "push" -> ref(path + "/new")
                    "onDisconnect" -> disconnect
                    "updateChildren" -> { if(path == "") updates += call.getArgument<Map<String, Any?>>(0); Tasks.forResult<Void>(null) }
                    "orderByKey", "endAt" -> refs[path]
                    "removeValue" -> Tasks.forResult<Void>(null)
                    "get" -> Tasks.forResult(mock(DataSnapshot::class.java).also { `when`(it.children).thenReturn(emptyList()) })
                    else -> RETURNS_DEFAULTS.answer(call)
                } })
            }
            `when`(database.reference).thenReturn(ref(""))
            var callback: LocationCallback? = null
            `when`(fused.requestLocationUpdates(any<LocationRequest>() ?: LocationRequest.Builder(1000).build(), any<LocationCallback>() ?: object:LocationCallback(){}, any<Looper>() ?: Looper.getMainLooper())).thenAnswer {
                callback = it.getArgument(1); Tasks.forResult<Void>(null)
            }
            mockStatic(LocationServices::class.java).use { locationServices ->
                locationServices.`when`<FusedLocationProviderClient> { LocationServices.getFusedLocationProviderClient(any<Context>()) }.thenReturn(fused)
                mockStatic(FirebaseDatabase::class.java).use { firebase ->
                    firebase.`when`<FirebaseDatabase> { FirebaseDatabase.getInstance(anyString()) }.thenReturn(database)
                    val original = Robolectric.buildService(LiveLocationService::class.java).get()
                    val service = original
                    val resources = spy(service.resources)
                    doReturn("Synthetic location sharing").`when`(resources).getString(anyInt())
                    val context = object : android.content.ContextWrapper(service.baseContext) { override fun getResources() = resources }
                    org.robolectric.util.ReflectionHelpers.setField(service, "mBase", context)
                    service.onCreate()
                    val key = EncryptionUtils.generateSymmetricKey()
                    LiveLocationKeyStore(service).save("owner", key)
                    service.getSharedPreferences("olive_location_sharing", 0).edit().putStringSet("live_contacts", setOf("friend")).apply()
                    val command = Intent().putExtra(LiveLocationService.OWNER_UID, "owner").putExtra(LiveLocationService.DISPLAY_NAME, "Synthetic user")
                        .putExtra(LiveLocationService.EXPIRES_AT, System.currentTimeMillis()+60000).putExtra(LiveLocationService.RESET_TRAIL, true)
                    assertEquals(Service.START_REDELIVER_INTENT, service.onStartCommand(command,0,1))
                    assertNull(service.onBind(null))
                    assertNotNull("Database root was not resolved: " + mockingDetails(database).invocations, LiveLocationService::class.java.getDeclaredField("rootReference").apply { isAccessible=true }.get(service))
                    val first = Location("fixture").apply { latitude=53.0; longitude=-113.0; accuracy=5f }
                    callback!!.onLocationResult(LocationResult.create(listOf(first)))
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(1, updates.size)
                    val row = updates.single()["liveLocations/owner"] as Map<*, *>
                    assertEquals(mapOf("friend" to true), row["authorized"])
                    val decoded = JSONObject(EncryptionUtils.decryptText(row["ciphertext"] as String,key))
                    assertEquals("Synthetic user", decoded.getString("name"))
                    assertEquals(53.0, decoded.getDouble("latitude"),0.0)
                    callback!!.onLocationResult(LocationResult.create(listOf(first)))
                    assertEquals(1, updates.size)
                    callback!!.onLocationResult(LocationResult.create(listOf(Location(first).apply { latitude=53.001 })))
                    assertEquals(2, updates.size)
                    assertEquals(Service.START_REDELIVER_INTENT,service.onStartCommand(Intent().setAction(LiveLocationService.PING),0,2))
                    assertEquals(Service.START_NOT_STICKY,service.onStartCommand(Intent().setAction(LiveLocationService.STOP),0,3))
                    assertNull(LiveLocationKeyStore(service).load("owner"))
                    assertNull(LiveLocationState.current.value)
                    service.onDestroy()
                }
            }
        }
    }

    @Test
    fun missingOrExpiredSessionNeverStartsGpsTracking() {
        TestAndroidKeyStore.install().use {
            val service = Robolectric.buildService(LiveLocationService::class.java).get()
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,1))
            assertFalse(service.getSharedPreferences("olive_location_sharing",0).getBoolean("live_active",true))
            service.onDestroy()
        }
    }
}
