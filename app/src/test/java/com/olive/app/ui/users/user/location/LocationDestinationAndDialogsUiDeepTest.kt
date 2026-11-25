package com.olive.app.ui.users.user.location

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.R
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteProfile
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.support.OliveUiTestHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
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
class LocationDestinationAndDialogsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun samplePlaces() = listOf(
        HotlineResource(
            id = "h1",
            region = "AB",
            city = "Edmonton",
            name = "Haven Women Shelter",
            description = "24/7 crisis shelter",
            kind = HotlineResourceKind.Shelter,
            phone = "780-555-0101",
            website = "https://example.org",
            source = "dir",
            phoneUri = "tel:7805550101",
            hours = "24/7",
            address = "100 Haven Way",
            latitude = 53.5461,
            longitude = -113.4938,
        ),
        HotlineResource(
            id = "h2",
            region = "AB",
            city = "Edmonton",
            name = "Legal Aid Clinic",
            description = "Free legal help",
            kind = HotlineResourceKind.LegalAid,
            phone = "780-555-0202",
            website = "https://example.org",
            source = "dir",
            phoneUri = "tel:7805550202",
            hours = "Mon-Fri 9-5",
            address = "",
            latitude = 53.5501,
            longitude = -113.4988,
        ),
    )

    @Test
    fun destinationBarFiltersSuggestionsAndHeadingStateWork() {
        compose.mainClock.autoAdvance = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = LocationMapController(context, scope, mock(LocationSharingDataSource::class.java))
        val places = samplePlaces()
        var state by mutableStateOf(
            LocationMapState(
                ownerUid = "owner-1",
                ownPoint = MapLocationPoint("owner-1", "You", 53.5444, -113.4909, 1000L, true),
                routeProfile = RouteProfile.DRIVING,
            )
        )
        var headingEnabled by mutableStateOf(true)
        var showSavedOnly by mutableStateOf(false)
        var headingUp by mutableStateOf(false)
        var openedList = 0

        compose.setContent {
            OliveUiTestHost {
                Column {
                    val heading by rememberDeviceHeadingState(
                        enabled = headingEnabled,
                        latitude = 53.5444,
                        longitude = -113.4909,
                        gpsBearing = 120f,
                        gpsSpeedMetersPerSecond = 4.5f,
                    )
                    FreeUseGpsDestinationCard(
                        controller = controller,
                        state = state,
                        directoryPlaces = places,
                        routeNotConfigured = "Not configured",
                        routeUnavailable = "Unavailable",
                        routeFailed = "Failed",
                    )
                    DirectoryMapFilterRow(
                        visibleCount = if (showSavedOnly) 0 else places.size,
                        savedMappedCount = 1,
                        showSavedOnly = showSavedOnly,
                        headingUp = headingUp,
                        onToggleSavedOnly = { showSavedOnly = !showSavedOnly },
                        onToggleHeadingUp = { headingUp = !headingUp },
                        onOpenDirectoryList = { openedList++ },
                    )
                    if (heading != null) {
                        // Read observable heading state
                        assertTrue(heading!! in 0f..360f)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(250)

        compose.onNodeWithTag("map_route_profile_walking").performClick()
        compose.onNodeWithTag("map_route_profile_driving").performClick()
        compose.onNodeWithTag("map_filter_saved_only").performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("map_saved_empty_hint").assertExists()
        compose.onNodeWithTag("map_filter_saved_only").performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("map_heading_chip").performClick()
        compose.onNodeWithTag("nearby_support_list").performClick()
        assertEquals(1, openedList)

        compose.onNodeWithTag("map_gps_destination_input").performTextInput("Haven")
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("map_gps_suggestion_0").performClick()
        compose.mainClock.advanceTimeBy(200)

        compose.onNodeWithTag("map_gps_destination_input").performTextReplacement("53.5500, -113.5000")
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("map_gps_start_route").performClick()
        compose.mainClock.advanceTimeBy(200)

        headingEnabled = false
        compose.mainClock.advanceTimeBy(150)
        scope.cancel()
    }

    @Test
    fun directoryDialogsAndBackgroundPermissionButtonExerciseAllActions() {
        compose.mainClock.autoAdvance = false
        val places = samplePlaces()
        var selectedPlace by mutableStateOf<HotlineResource?>(null)
        var detailPlace by mutableStateOf<HotlineResource?>(places.first())
        var inWindow by mutableStateOf(false)
        var savedIds by mutableStateOf(setOf("h1"))
        var dialed = 0
        var startedNav = 0
        var permissionRequested = 0

        compose.setContent {
            OliveUiTestHost {
                Column {
                    DirectoryPlacesListDialog(
                        places = places,
                        savedIds = savedIds,
                        onToggleSave = { res ->
                            savedIds = if (res.id in savedIds) savedIds - res.id else savedIds + res.id
                        },
                        onSelect = { selectedPlace = it },
                        onDismiss = {},
                        inWindow = inWindow,
                    )
                    detailPlace?.let { res ->
                        DirectoryPlaceDetailDialog(
                            resource = res,
                            saved = res.id in savedIds,
                            onToggleSave = {
                                savedIds = if (res.id in savedIds) savedIds - res.id else savedIds + res.id
                            },
                            onDial = { dialed++ },
                            onStartNavigation = { startedNav++ },
                            onDismiss = {},
                        )
                    }
                    BackgroundLocationButton(
                        requestPermission = {
                            permissionRequested++
                            true
                        },
                        autoExplain = true,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)

        compose.onNodeWithTag("map_directory_filter_shelter").performClick()
        compose.onNodeWithTag("map_directory_filter_all").performClick()
        compose.onNodeWithTag("map_detail_save").performClick()
        compose.onAllNodesWithText(context.getString(R.string.support_open_dialer)).onFirst().performClick()
        compose.onAllNodesWithText(context.getString(R.string.location_start_navigation)).onFirst().performClick()
        compose.onNodeWithTag("background_location_continue").performClick()
        compose.mainClock.advanceTimeBy(200)

        assertTrue(dialed == 1)
        assertTrue(startedNav == 1)
        assertTrue(permissionRequested == 1)

        inWindow = true
        detailPlace = places[1]
        compose.mainClock.advanceTimeBy(250)
    }
}
