package com.olive.app.ui.users.user.location

import android.app.Application
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.LocationTrailPoint
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.data.location.RouteProfile
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class LocationScreensUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val sampleRoute = LocationRoute(
        coordinates = listOf(
            RouteCoordinate(53.5461, -113.4938),
            RouteCoordinate(53.5471, -113.4938),
            RouteCoordinate(53.5481, -113.4920),
        ),
        distanceMeters = 1_450.0,
        durationSeconds = 420.0,
        directions = listOf(
            RouteDirection("Head north on 100 St", 0, 1),
            RouteDirection("Turn right on Jasper Ave", 1, 2),
        ),
    )

    private val sampleResource = HotlineResource(
        id = "res-1",
        region = "AB",
        city = "Edmonton",
        name = "Safe Haven Shelter",
        description = "24/7 emergency shelter and crisis support",
        kind = HotlineResourceKind.Shelter,
        phone = "780-555-0100",
        website = "https://example.org",
        source = "Olive",
        phoneUri = "tel:7805550100",
        hours = "24/7",
        address = "100 Jasper Ave",
        latitude = 53.5461,
        longitude = -113.4938,
    )

    @Test
    fun locationMapScreenRendersUserAndSafeContactFlows() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            handle.session.runtime.state.friends = listOf(Friend("f-1", "friend-1", "sam_contact"))
            handle.session.runtime.state.profile = UserProfile(
                username = "alex_user",
                fullName = "Alex Rivera",
                contacts = listOf(SafeContact("c-1", "Sam", "555-0101", safeToStay = true, linkedUid = "friend-1", relationship = "Friend")),
                address = DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", country = "Canada", latitude = 53.5461, longitude = -113.4938),
                role = AccountRole.USER,
            )

            compose.setContent {
                OliveUiTestHost(locationGranted = false) {
                    LocationMapScreen(handle.session, handle.session.locationMap)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("map_gps_destination_card").assertExists()
            compose.onNodeWithTag("map_filter_saved_only").performScrollTo().performClick()
            compose.onNodeWithTag("map_heading_chip").performScrollTo().performClick()
            compose.onNodeWithTag("location_expand_map").assertExists()
            compose.onNodeWithTag("start_location_sharing").assertExists()
        }
    }

    @Test
    fun locationMapScreenRendersSafeContactWorkspace() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.SAFE_CONTACT, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            handle.session.runtime.state.profile = UserProfile(
                username = "sam_safe",
                fullName = "Sam Contact",
                address = DeliveryAddress(city = "Edmonton", province = "AB", country = "Canada"),
                role = AccountRole.SAFE_CONTACT,
            )

            compose.setContent {
                OliveUiTestHost {
                    LocationMapScreen(handle.session, handle.session.locationMap)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.safe_contact_live_location)).assertExists()
        }
    }

    @Test
    fun fullScreenMapDirectoryDialogsGpsCardAndWebViewWork() {
        compose.mainClock.autoAdvance = false
        val own = MapLocationPoint("owner", "Alex", 53.5461, -113.4938, 1_700_000_000_000L, live = true, trail = listOf(LocationTrailPoint(53.545, -113.494, 1_699_999_000_000L)))
        val dest = MapLocationPoint("dest", "Safe Haven", 53.5481, -113.4920, 1_700_000_000_000L, live = false)
        val support = NearbySupportService("res-1", "Safe Haven Shelter", "100 Jasper Ave", 53.5461, -113.4938, saved = true)
        val heading = mutableFloatStateOf(45f)
        var exitClicks = 0
        var savedToggled = 0
        var dialed = 0
        var startedNav = 0

        compose.setContent {
            OliveUiTestHost {
                FullScreenLocationMap(
                    points = listOf(own, dest),
                    ownPoint = own,
                    route = sampleRoute,
                    destinationName = "Safe Haven",
                    routeWorking = false,
                    routeError = null,
                    navigating = true,
                    showOwnPointInBlue = true,
                    onExitFullScreen = { exitClicks++ },
                    onStopNavigation = {},
                    supportServices = listOf(support),
                    onSupportSelected = {},
                    headingUp = true,
                    headingState = heading,
                    onToggleHeadingUp = {},
                    showSavedOnly = false,
                    savedCount = 1,
                    onToggleSavedOnly = {},
                    routeProfile = RouteProfile.WALKING,
                )
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithTag("full_screen_navigation").assertExists()
        compose.onNodeWithTag("exit_full_screen_navigation").performClick()
        assertEquals(1, exitClicks)

        // Pure route & geometry helpers
        assertEquals(0.5f, routeCompletionProgress(1_000.0, 500.0), 0.01f)
        assertEquals(0f, routeCompletionProgress(0.0, 500.0), 0.01f)
        val next = upcomingRouteDirection(sampleRoute, sampleRoute.directions.first())
        assertEquals("Turn right on Jasper Ave", next?.text)
        assertNull(upcomingRouteDirection(sampleRoute, null))
        assertNotNull(distanceToDirectionMeters(sampleRoute, 53.5461, -113.4938, sampleRoute.directions.first()))
        assertEquals("500 m", formatRouteDistance(500.0))
        assertTrue(formatArrivalTime(120.0, 1_700_000_000_000L).isNotBlank())

        var readyCalls = 0
        var errorCalls = 0
        val webView = createLocationMapWebView(
            context = context,
            onViewportCenter = { _, _ -> },
            onSelectSupport = {},
            onSelectLocation = { _, _ -> },
            onGesture = {},
            onBearing = {},
            onResize = {},
            onError = { errorCalls++ },
            onReady = { readyCalls++ },
        )
        webView.webChromeClient?.onReceivedTitle(webView, "olive-map-ready")
        webView.webChromeClient?.onReceivedTitle(webView, "olive-map-error")
        assertTrue(webView.webViewClient.shouldOverrideUrlLoading(webView, mock(WebResourceRequest::class.java)))
        webView.loadOliveMap()
        assertEquals(1, readyCalls)
        assertEquals(1, errorCalls)
        assertTrue(savedToggled == 0 && dialed == 0 && startedNav == 0)
    }

    @Test
    fun directoryDialogsAndGpsCardRenderAndInteract() {
        var placeDetailClicks = 0
        var savedClicks = 0
        var dialClicks = 0
        var navClicks = 0

        compose.setContent {
            OliveUiTestHost {
                Column {
                    DirectoryMapFilterRow(
                        visibleCount = 2,
                        savedMappedCount = 1,
                        showSavedOnly = false,
                        headingUp = false,
                        onToggleSavedOnly = {},
                        onToggleHeadingUp = {},
                        onOpenDirectoryList = { placeDetailClicks++ },
                    )
                    DirectoryPlaceDetailDialog(
                        resource = sampleResource,
                        saved = true,
                        onToggleSave = { savedClicks++ },
                        onDial = { dialClicks++ },
                        onStartNavigation = { navClicks++ },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nearby_support_list").performClick()
        compose.onNodeWithTag("map_detail_save").performClick()
        compose.onNodeWithText(context.getString(R.string.support_open_dialer)).performClick()
        compose.onNodeWithText(context.getString(R.string.location_start_navigation)).performClick()
        assertEquals(1, placeDetailClicks)
        assertEquals(1, savedClicks)
        assertEquals(1, dialClicks)
        assertEquals(1, navClicks)
    }

    @Test
    fun gpsDestinationCardAndInteractiveMapErrorRender() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    Column {
                        FreeUseGpsDestinationCard(
                            controller = handle.session.locationMap,
                            state = LocationMapState(ownerUid = "owner"),
                            directoryPlaces = listOf(sampleResource),
                            routeNotConfigured = "Not configured",
                            routeUnavailable = "Unavailable",
                            routeFailed = "Failed",
                        )
                        InteractiveLocationMap(
                            points = listOf(MapLocationPoint("owner", "Alex", 53.5461, -113.4938, 1000L, true)),
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                            userSearchEnabled = true,
                            compassEnabled = true,
                            onToggleHeadingUp = {},
                            initialFollowUid = "owner",
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("map_gps_destination_input").performTextInput("Safe Haven")
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("map_route_profile_walking").performClick()
            compose.onNodeWithTag("map_gps_destination_card").assertExists()
            compose.onNodeWithTag("location_map_view").assertExists()
        }
    }
}
