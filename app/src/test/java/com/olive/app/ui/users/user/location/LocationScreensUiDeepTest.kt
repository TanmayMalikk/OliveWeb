package com.olive.app.ui.users.user.location

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class LocationScreensUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun locationMapScreenAndInteractiveLocationMapRenderPointsRouteAndServices() {
        compose.mainClock.autoAdvance = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val controller = LocationMapController(context, scope, mock(LocationSharingDataSource::class.java))
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            handle.session.runtime.state.profile = UserProfile(
                username = "test_user",
                fullName = "Alex",
                address = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                role = AccountRole.USER,
            )
            controller.queuePrivateDestination("Shelter", 53.5461, -113.4938)
            var stage by mutableIntStateOf(0)
            val points = listOf(
                MapLocationPoint("owner", "You", 53.5444, -113.4909, 1000L, true, 8f),
                MapLocationPoint("f1", "Sam", 53.5500, -113.4950, 2000L, true, 12f),
            )
            val route = LocationRoute(
                coordinates = listOf(RouteCoordinate(53.5444, -113.4909), RouteCoordinate(53.5461, -113.4938)),
                distanceMeters = 450.0,
                durationSeconds = 320.0,
                directions = listOf(RouteDirection("Turn left onto Jasper Ave", 0, 1)),
            )

            compose.setContent {
                val grantPerm: suspend () -> Boolean = { true }
                OliveUiTestHost(locationGranted = true) {
                    CompositionLocalProvider(LocalLocationPermissionRequest provides grantPerm) {
                        if (stage == 0) {
                            LocationMapScreen(handle.session, controller)
                        } else {
                            Box(Modifier.fillMaxSize().testTag("interactive_location_map_wrapper")) {
                                InteractiveLocationMap(
                                    points = points,
                                    modifier = Modifier.fillMaxSize(),
                                    ownPointUid = "owner",
                                    showOwnPointInBlue = true,
                                    route = route,
                                    navigationActive = true,
                                    detailsOnlyForSelectedPoint = true,
                                    userSearchEnabled = true,
                                    selectedLocation = 53.5461 to -113.4938,
                                    supportServices = listOf(
                                        NearbySupportService("s1", "Crisis Line", "Edmonton", 53.548, -113.496, false),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            Thread.sleep(80)
            compose.mainClock.advanceTimeBy(250)
        }
        scope.cancel()
    }

    @Test
    fun interactiveLocationMapRendersPointsRouteAndServices() {
        compose.mainClock.autoAdvance = false
        val points = listOf(
            MapLocationPoint("owner", "You", 53.5444, -113.4909, 1000L, true, 8f),
            MapLocationPoint("f1", "Sam", 53.5500, -113.4950, 2000L, true, 12f),
        )
        val route = LocationRoute(
            coordinates = listOf(RouteCoordinate(53.5444, -113.4909), RouteCoordinate(53.5461, -113.4938)),
            distanceMeters = 450.0,
            durationSeconds = 320.0,
            directions = listOf(RouteDirection("Turn left onto Jasper Ave", 0, 1)),
        )
        compose.setContent {
            val grantPerm: suspend () -> Boolean = { true }
            OliveUiTestHost(locationGranted = true) {
                CompositionLocalProvider(LocalLocationPermissionRequest provides grantPerm) {
                    Box(Modifier.fillMaxSize().testTag("interactive_location_map_wrapper")) {
                        InteractiveLocationMap(
                            points = points,
                            modifier = Modifier.fillMaxSize(),
                            ownPointUid = "owner",
                            showOwnPointInBlue = true,
                            route = route,
                            navigationActive = true,
                            detailsOnlyForSelectedPoint = true,
                            userSearchEnabled = true,
                            selectedLocation = 53.5461 to -113.4938,
                            supportServices = listOf(
                                NearbySupportService("s1", "Crisis Line", "Edmonton", 53.548, -113.496, false),
                            ),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("interactive_location_map_wrapper").assertExists()
    }
}
