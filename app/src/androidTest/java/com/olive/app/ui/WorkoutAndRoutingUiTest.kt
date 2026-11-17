package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.DeviceCoordinates
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.ui.covers.fitness.FitnessOutdoorWorkoutCard
import com.olive.app.ui.covers.fitness.FitnessWorkoutStore
import com.olive.app.ui.covers.fitness.OutdoorWorkoutType
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.location.FullScreenLocationMap
import com.olive.app.ui.users.user.location.ShareDurationDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkoutAndRoutingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test
    fun activeWorkoutControlsMatchPersistedPauseStateBeforeGpsArrives() {
        val first = "fixture-running-${System.nanoTime()}"
        val second = "fixture-paused-${System.nanoTime()}"
        val now = System.currentTimeMillis()
        FitnessWorkoutStore(context, first).start(OutdoorWorkoutType.RUN, now - 15000)
        FitnessWorkoutStore(context, second).apply {
            start(OutdoorWorkoutType.CYCLE, now - 10000)
            pause(now - 1000)
        }
        var account by mutableStateOf(first)
        compose.setContent {
            OliveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FitnessOutdoorWorkoutCard(account, enabled = false)
                }
            }
        }
        compose.onNodeWithText(text(R.string.fitness_running)).assertExists()
        compose.onNodeWithText(text(R.string.fitness_waiting_for_gps)).assertExists()
        compose.onNodeWithText(text(R.string.fitness_pause)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.fitness_finish)).assertIsNotEnabled()
        compose.runOnIdle { account = second }
        compose.onNodeWithText(text(R.string.fitness_cycling)).assertExists()
        compose.onNodeWithText(text(R.string.fitness_resume)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.fitness_pause)).assertDoesNotExist()
    }

    @Test
    fun selectingAnOutdoorWorkoutTypeDoesNotStartLocationTracking() {
        val account = "fixture-history-${System.nanoTime()}"
        val store = FitnessWorkoutStore(context, account)
        val now = System.currentTimeMillis()
        store.start(OutdoorWorkoutType.HIKE, now - 65000)
        store.finish(now)
        compose.setContent {
            OliveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FitnessOutdoorWorkoutCard(account, enabled = false)
                }
            }
        }
        compose.onNodeWithText(text(R.string.fitness_cycling)).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.fitness_start_outdoor_workout)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.fitness_recent_outdoor_workouts)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.fitness_hiking), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.fitness_waiting_for_gps)).assertDoesNotExist()
    }

    @Test
    fun routeGuidanceShowsTheNextTurnAndMakesStopAndExitSeparateActions() {
        val point = MapLocationPoint("me", "Sample location", 53.5, -113.5, 1, true)
        val route = LocationRoute(
            listOf(RouteCoordinate(53.5, -113.5), RouteCoordinate(53.51, -113.5)),
            1200.0, 110.0, listOf(RouteDirection("Continue north", 0, 1)),
        )
        var navigating by mutableStateOf(true)
        var stops = 0
        var exits = 0
        var loads = 0
        compose.setContent {
            OliveTheme {
                FullScreenLocationMap(
                    listOf(point), point, route.takeIf { navigating }, "Meeting point",
                    routeWorking = false, routeError = if (navigating) null else "Routing unavailable",
                    navigating = navigating, showOwnPointInBlue = true,
                    onExitFullScreen = { exits++ }, onStopNavigation = { stops++; navigating = false },
                    onLoadMoreSupport = { loads++ },
                )
            }
        }
        compose.onNodeWithText("Continue north").assertIsDisplayed()
        compose.onNodeWithTag("exit_full_screen_navigation").performClick()
        compose.onNodeWithTag("full_screen_stop_navigation").performClick()
        compose.onNodeWithTag("full_screen_location_map").assertExists()
        compose.onNodeWithText("Continue north").assertDoesNotExist()
        compose.onNodeWithText("Routing unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.load_more_hotline_locations)).performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(1, exits)
            assertEquals(1, loads)
        }
    }

    @Test
    fun locationShareDurationRejectsOutOfRangeHoursAndKeepsPresetChoices() {
        var chosen: Long? = null
        compose.setContent { OliveTheme { ShareDurationDialog({}, { chosen = it }) } }
        val confirm = compose.onNodeWithTag("location_custom_confirm")
        confirm.assertIsNotEnabled()
        val input = compose.onNodeWithTag("location_custom_hours")
        input.performTextReplacement("99")
        confirm.assertIsNotEnabled()
        input.performTextReplacement("03")
        confirm.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(10_800_000L, chosen) }
        compose.onNodeWithText(text(R.string.location_one_hour)).performClick()
        compose.runOnIdle { assertEquals(3_600_000L, chosen) }
    }

    @Test
    fun anActiveWorkoutShowsItsRouteAndMetricsAsSoonAsGpsPointsAreAvailable() {
        val account = "fixture-route-${System.nanoTime()}"
        val store = FitnessWorkoutStore(context, account)
        val now = System.currentTimeMillis()
        store.start(OutdoorWorkoutType.RUN, now - 60_000)
        store.addLocation(DeviceCoordinates(53.5, -113.5, 4f, now - 30_000))
        store.addLocation(DeviceCoordinates(53.501, -113.5, 4f, now - 10_000))
        compose.setContent {
            OliveTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FitnessOutdoorWorkoutCard(account, enabled = false)
                }
            }
        }
        compose.onNodeWithText(text(R.string.fitness_waiting_for_gps)).assertDoesNotExist()
        compose.onNodeWithContentDescription("${text(R.string.distance)}, 0.11 km").performScrollTo().assertIsDisplayed()
        val pace = hasContentDescription("${text(R.string.fitness_pace)}, ", substring = true)
        compose.onNode(pace).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            store.finish(now)
            assertEquals(1, store.history().size)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.fitness_recent_outdoor_workouts)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.fitness_running), substring = true).assertExists()
    }

}
