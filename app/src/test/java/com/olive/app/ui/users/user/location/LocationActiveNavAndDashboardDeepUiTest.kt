package com.olive.app.ui.users.user.location

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.data.messaging.Friend
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.dashboard.SafeArea
import com.olive.app.ui.users.user.dashboard.SafeSupportControls
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class LocationActiveNavAndDashboardDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun locationFriendPickerShareDurationAndCustomHoursWork() {
        var durationChosen = 0L
        var selectedFriends by mutableStateOf(setOf<String>())
        val friend = Friend("fs-1", "friend-u1", "jordan_s")

        compose.setContent {
            OliveUiTestHost(locationGranted = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocationShareFriendPicker(
                        accountId = "u1",
                        friends = listOf(friend),
                        contactNames = mapOf("friend-u1" to "Jordan"),
                        selected = selectedFriends,
                        enabled = true,
                        onSelectionChange = { uid, checked ->
                            selectedFriends = if (checked) selectedFriends + uid else selectedFriends - uid
                        },
                    )
                    ShareDurationDialog(
                        onDismiss = {},
                        inWindow = true,
                        onSelected = { durationChosen = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("location_friend_friend-u1").performScrollTo().performClick()
        compose.onNodeWithTag("location_custom_hours").performScrollTo().performTextReplacement("4")
        compose.onNodeWithTag("location_duration_10_seconds").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("friend-u1" in selectedFriends)
        assertTrue(durationChosen > 0L)
    }

    @Test
    fun fullScreenLocationMapRendersActiveNavigationAndControls() {
        val own = MapLocationPoint("u1", "You", 53.5444, -113.4909, 1000L, true)
        val dest = MapLocationPoint("dest", "Shelter", 53.5461, -113.4938, 1000L, true)
        val route = LocationRoute(
            coordinates = listOf(RouteCoordinate(53.5444, -113.4909), RouteCoordinate(53.5461, -113.4938)),
            distanceMeters = 520.0,
            durationSeconds = 360.0,
            directions = listOf(
                RouteDirection("Head north on 101 St", 0, 1),
                RouteDirection("Arrive at Shelter", 1, 1),
            ),
        )
        compose.setContent {
            OliveUiTestHost(locationGranted = true) {
                FullScreenLocationMap(
                    points = listOf(own, dest),
                    ownPoint = own,
                    route = route,
                    destinationName = "Shelter",
                    routeWorking = false,
                    routeError = null,
                    navigating = true,
                    showOwnPointInBlue = true,
                    onExitFullScreen = {},
                    onStopNavigation = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun safeAreaStaffHelpAndExpandedSupportControlsNavigateCleanly() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.StaffHelp, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.profile = UserProfile(
                username = "alex",
                fullName = "Alex",
                dob = "1995-05-05",
                address = DeliveryAddress(city = "Edmonton"),
                role = AccountRole.USER,
            )
            var expanded by mutableStateOf(true)
            compose.setContent {
                OliveUiTestHost {
                    SafeArea(session, exit = {})
                    SafeSupportControls(
                        session = session,
                        exit = {},
                        showExit = true,
                        showSupport = true,
                        expanded = expanded,
                        onExpanded = { expanded = it },
                    )
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("support_walkthrough").performClick()
            compose.waitForIdle()
            expanded = true
            compose.waitForIdle()
            compose.onNodeWithTag("support_location").performClick()
            compose.waitForIdle()
            expanded = true
            compose.waitForIdle()
            compose.onNodeWithTag("support_staff_help").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("staff_help_back").assertExists().performClick()
            compose.waitForIdle()
        }
    }
}
