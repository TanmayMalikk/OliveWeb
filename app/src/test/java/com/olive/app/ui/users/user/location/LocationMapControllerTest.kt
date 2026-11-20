package com.olive.app.ui.users.user.location

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.Tasks
import android.content.res.Resources
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.AddressCoordinateResolver
import com.olive.app.data.location.DeviceLocation
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.location.LiveLocationController
import com.olive.app.security.EncryptionUtils
import com.olive.app.support.TestAndroidKeyStore
import org.mockito.Mockito.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import com.olive.app.data.location.SavedLocationUnavailableException
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.Friend
import com.olive.app.location.LiveLocationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class LocationMapControllerTest {
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val appResources = Mockito.spy(baseContext.resources).also { resources ->
            Mockito.doReturn("Saved address unavailable").`when`(resources)
                .getString(R.string.location_saved_address_unavailable)
        }
        override fun getApplicationContext() = this
        override fun getResources(): Resources = appResources
    }
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val source = LocationFixture()
    private val controller = LocationMapController(context, scope, source)
    private val friend = Friend("pair", "trusted", "Trusted")

    private fun configuration(role: AccountRole = AccountRole.USER, local: Boolean = false) =
        LocationMapConfiguration(
            AccountIdentity("owner", "fixture_owner"),
            UserProfile("fixture_owner", "Fixture owner", "", "", "1991-01-02", "English", "", "", "", role = role),
            listOf(friend), local, "You", "Saved address",
        )

    @After
    fun clearLocationState() {
        controller.clear()
        LiveLocationState.update(null)
        scope.cancel()
    }

    @Test
    fun failedInitialLocationRequestDoesNotLeaveTheMapLoading() {
        var failure: Throwable? = null
        val failingScope = CoroutineScope(Job() + Dispatchers.Unconfined +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error -> failure = error })
        val map = LocationMapController(context, failingScope, source)
        try {
            map.open(configuration()) { throw IllegalStateException("Synthetic permission failure") }
            assertTrue(failure is IllegalStateException)
            assertFalse(map.state.locating)
        } finally { map.clear(); failingScope.cancel() }
    }

    @Test
    fun staticRecipientUpdatesStopAtTheAccountBoundary() {
        val profile = configuration()
        controller.open(profile) { false }
        assertEquals("owner", controller.state.ownerUid)
        assertFalse(controller.state.locating)
        val first = requireNotNull(source.staticUpdate)
        val contact = point("trusted", live = false)
        first(listOf(contact))
        assertEquals(listOf(contact), controller.state.contacts)

        controller.open(profile.copy(identity = AccountIdentity("next", "next_owner"))) { false }
        assertEquals(1, source.closed)
        first(listOf(point("previous", live = false)))
        assertTrue(controller.state.contacts.isEmpty())
        source.staticUpdate?.invoke(listOf(contact))
        assertEquals(listOf(contact), controller.state.contacts)
        controller.clear()
        assertEquals(2, source.closed)
        assertNull(controller.state.ownerUid)
    }

    @Test
    fun liveTargetsFollowAvailableFriendsAndIgnoreSavedMarkers() {
        controller.open(configuration(AccountRole.SAFE_CONTACT)) { true }
        val update = requireNotNull(source.liveUpdate)
        val saved = point("saved", live = false)
        val trusted = point("trusted", live = true)
        val other = point("other", live = true)
        update(listOf(saved, trusted, other))
        assertEquals("trusted", controller.state.navigationTargetUid)
        controller.selectNavigationTarget("saved")
        assertEquals("trusted", controller.state.navigationTargetUid)
        controller.selectNavigationTarget("other")
        assertEquals("other", controller.state.navigationTargetUid)

        update(listOf(saved, trusted))
        assertEquals("trusted", controller.state.navigationTargetUid)
        update(listOf(saved))
        assertNull(controller.state.navigationTargetUid)
        assertFalse(controller.state.navigating)

        controller.selectPrivateDestination("Public meeting point", 53.4, -113.6)
        assertEquals("Public meeting point", controller.state.privateDestination?.name)
        controller.stopNavigation()
        assertNull(controller.state.route)
        controller.closeMap()
        assertNull(controller.state.privateDestination)
    }

    @Test
    fun sharingRequestsPublishOnlySelectedFriendsAfterPermissionAndRevokeOnFailure() {
        val config = configuration()
        controller.open(config) { false }
        controller.selectContact("trusted", true)
        controller.selectContact("unknown", true)
        controller.startSharing(config, 10000, { false }, "Permission denied", "Unable to start")
        assertEquals("Permission denied", controller.state.error)
        assertEquals(setOf("trusted"), controller.state.selected)
        assertTrue(source.recipientUpdates.isEmpty())

        source.liveFailure = IllegalStateException("Friend has no usable key")
        var callback: String? = null
        controller.startSharingWith(config, listOf(friend), 10000, { true }, "Permission denied", "Unable to start",
            onError = { callback = it })
        assertEquals(listOf(listOf(friend), emptyList()), source.recipientUpdates)
        assertEquals("Friend has no usable key", callback)
        assertFalse(controller.state.working)
        assertFalse(controller.state.liveActive)
        controller.selectContact("trusted", false)
        assertTrue(controller.state.selected.isEmpty())
    }

    @Test
    fun savedAddressToggleWaitsForRepositoryAndExplainsMissingCoordinates() {
        val config = configuration()
        controller.open(config) { false }
        source.staticFailure = SavedLocationUnavailableException()
        controller.updateStaticSharing(config, true)
        assertFalse(controller.state.staticSharing)
        assertEquals(context.getString(R.string.location_saved_address_unavailable), controller.state.error)
        source.staticFailure = null

        controller.updateStaticSharing(config, true)
        assertTrue(controller.state.staticSharing)
        assertNull(controller.state.error)
        controller.updateStaticSharing(config, false)
        assertFalse(controller.state.staticSharing)
        controller.updateStaticSharing(config.copy(localMode = true), true)
        assertEquals(listOf(true, true, false), source.staticWrites)
    }

    @Test
    @Config(sdk = [35])
    fun navigationUsesTheCurrentFixAndOnlyRequestsRoutesForTheSelectedDestination() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = context.getSystemService(LocationManager::class.java)
        shadowOf(locationManager).setLocationEnabled(true)
        val fused = mock(FusedLocationProviderClient::class.java)
        val firstFix = Location("fixture").apply {
            latitude = 53.5
            longitude = -113.5
            time = System.currentTimeMillis()
        }
        `when`(fused.getCurrentLocation(
            any<CurrentLocationRequest>() ?: CurrentLocationRequest.Builder().build(),
            any<CancellationToken>(),
        )).thenReturn(Tasks.forResult(firstFix))
        val main = CoroutineScope(Job() + Dispatchers.Main.immediate)
        val requests = mutableListOf<Pair<MapLocationPoint, MapLocationPoint>>()
        val route = LocationRoute(listOf(RouteCoordinate(53.5, -113.5), RouteCoordinate(53.6, -113.4)),
            10_000.0, 600.0, emptyList())
        val map = LocationMapController(context, main, source) { origin, target ->
            requests += origin to target
            route.takeIf { target.name != "Unavailable destination" }
        }
        mockStatic(LocationServices::class.java).use { services ->
            services.`when`<FusedLocationProviderClient> {
                LocationServices.getFusedLocationProviderClient(any<Context>())
            }.thenReturn(fused)
            try {
                map.open(configuration()) { true }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(53.5, map.state.ownPoint!!.latitude, 0.0)
                assertEquals(1, map.state.ownPoint!!.trail.size)
                map.startNavigation("No routing key", "Missing points", "No route")
                assertEquals("Missing points", map.state.routeError)
                map.selectPrivateDestination("Synthetic destination", 53.6, -113.4)
                map.startNavigation("No routing key", "Missing points", "No route")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
                assertEquals(route, map.state.route)
                assertEquals("Synthetic destination", requests.single().second.name)
                assertEquals(1, map.state.ownPoint!!.trail.size)
                map.stopNavigation()
                assertFalse(map.state.navigating)
                assertNull(map.state.route)
                map.selectPrivateDestination("Unavailable destination", 53.7, -113.8)
                map.startNavigation("No routing key", "Missing points", "No route")
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("No route", map.state.routeError)
            } finally {
                map.clear()
                main.cancel()
            }
        }
    }

    @Test
    @Config(sdk = [35])
    fun liveSharingWaitsForTheOwnersFirstFixAndClearsTheSessionWhenStopped() {
        TestAndroidKeyStore.install().use {
            val main = CoroutineScope(Job() + Dispatchers.Main.immediate)
            val map = LocationMapController(context, main, source, requestRoute = null)
            try {
                source.liveKey = EncryptionUtils.generateSymmetricKey()
                val config = configuration()
                map.open(config) { false }
                map.startNavigation("No routing key", "Missing points", "No route")
                assertEquals("No routing key", map.state.routeError)
                var startedUntil: Long? = null
                map.startSharingWith(config, listOf(friend), 10_000, { true }, "permission", "error",
                    onStarted = { startedUntil = it })
                assertTrue(map.state.working)
                LiveLocationState.update(point("wrong", live = true).copy(updatedAt = System.currentTimeMillis()))
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(startedUntil)
                LiveLocationState.update(point("owner", live = true).copy(updatedAt = System.currentTimeMillis()))
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(map.state.liveActive)
                assertEquals(startedUntil, map.state.expiresAt)
                assertEquals(setOf("trusted"), LiveLocationController.selected(context))
                assertEquals("owner", map.state.displayedOwn(AccountRole.USER)?.uid)
                map.stopSharing()
                assertFalse(map.state.liveActive)
                assertFalse(LiveLocationController.active(context))
                assertTrue(LiveLocationController.selected(context).isEmpty())
            } finally {
                map.stopSharing()
                map.clear()
                main.cancel()
            }
        }
    }

    @Test
    @Config(sdk = [35])
    @Suppress("DEPRECATION")
    fun safeContactResolvesAddressGeocoderAndNavigationHandlesRouteFailures() {
        val address = android.location.Address(java.util.Locale.CANADA).apply {
            latitude = 53.5461
            longitude = -113.4938
        }
        val contactSource = LocationFixture().apply { staticEnabled = true }
        val main = CoroutineScope(Job() + Dispatchers.Main.immediate)
        val addressResolver = AddressCoordinateResolver { DeviceLocation.coordinatesForAddress(context, it) }
        val map = LocationMapController(context, main, contactSource, addressResolver) { _, _ ->
            throw IllegalStateException("Routing unavailable")
        }
        try {
            mockConstruction(android.location.Geocoder::class.java) { geocoder, _ ->
                doAnswer { inv ->
                    @Suppress("UNCHECKED_CAST")
                    inv.getArgument<android.location.Geocoder.GeocodeListener>(2).onGeocode(mutableListOf(address))
                    null
                }.`when`(geocoder).getFromLocationName(
                    anyString(),
                    anyInt(),
                    any<android.location.Geocoder.GeocodeListener>() ?: android.location.Geocoder.GeocodeListener {},
                )
            }.use {
                val safeConfig = configuration(role = AccountRole.SAFE_CONTACT).let {
                    it.copy(
                        profile = it.profile.copy(
                            address = com.olive.app.data.account.DeliveryAddress(
                                line1 = "100 Main St",
                                city = "Edmonton",
                                province = "AB",
                                postalCode = "T5J 0N3",
                            ),
                        ),
                    )
                }
                map.open(safeConfig) { false }
                kotlinx.coroutines.runBlocking {
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (map.state.ownPoint == null && System.currentTimeMillis() < deadline) {
                        shadowOf(Looper.getMainLooper()).idle()
                        kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(53.5461, map.state.ownPoint!!.latitude, 0.0001)
                assertTrue(contactSource.staticWrites.last())
                contactSource.liveUpdate?.invoke(listOf(point("trusted", live = true)))
                map.startNavigation("No key", "Unavailable", "Route failed")
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("trusted", map.state.navigationTargetUid)
                assertEquals("Route failed", map.state.routeError)
            }
        } finally {
            map.clear()
            main.cancel()
        }
    }

    private fun point(uid: String, live: Boolean) = MapLocationPoint(uid, uid, 53.5, -113.5, 2000, live)

    private class LocationFixture : LocationSharingDataSource {
        var staticEnabled = false
        var staticUpdate: ((List<MapLocationPoint>) -> Unit)? = null
        var liveUpdate: ((List<MapLocationPoint>) -> Unit)? = null
        var closed = 0
        var staticFailure: Exception? = null
        var liveFailure: Exception? = null
        var liveKey: SecretKey? = null
        val staticWrites = mutableListOf<Boolean>()
        val recipientUpdates = mutableListOf<List<Friend>>()

        override fun staticSharingEnabled(uid: String) = staticEnabled
        override suspend fun setStaticSharing(
            identity: AccountIdentity, profile: UserProfile, friends: List<Friend>, enabled: Boolean,
        ) {
            staticWrites.add(enabled)
            staticFailure?.let { throw it }
        }

        override suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>): SecretKey? {
            recipientUpdates.add(friends.toList())
            if (friends.isEmpty()) return null
            liveFailure?.let { throw it }
            return liveKey
        }

        override fun observeStaticContacts(
            currentUid: String, friends: List<Friend>, onUpdate: (List<MapLocationPoint>) -> Unit,
        ): ChatSubscription {
            staticUpdate = onUpdate
            return ChatSubscription { closed++ }
        }

        override fun observeLiveFriends(
            currentUid: String, friends: List<Friend>, onUpdate: (List<MapLocationPoint>) -> Unit,
        ): ChatSubscription {
            liveUpdate = onUpdate
            return ChatSubscription { closed++ }
        }
    }
}
