package com.olive.app.ui.users.user.location

import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.data.location.supportDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date

class NavigationTripSummaryTest {
    @Test
    fun routeCompletionProgressHandlesBoundariesAndInvalidValues() {
        assertEquals(0f, routeCompletionProgress(1_000.0, 1_000.0), 0.0001f)
        assertEquals(0.5f, routeCompletionProgress(1_000.0, 500.0), 0.0001f)
        assertEquals(1f, routeCompletionProgress(1_000.0, 0.0), 0.0001f)
        assertEquals(1f, routeCompletionProgress(1_000.0, -50.0), 0.0001f)
        assertEquals(0f, routeCompletionProgress(1_000.0, 1_400.0), 0.0001f)
        assertEquals(0f, routeCompletionProgress(0.0, 100.0), 0.0001f)
        assertEquals(0f, routeCompletionProgress(Double.NaN, 100.0), 0.0001f)
        assertEquals(0f, routeCompletionProgress(1_000.0, Double.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun upcomingRouteDirectionReturnsNextStepUntilFinalManeuver() {
        val first = RouteDirection("Turn right onto 109 Street NW", 0, 0)
        val second = RouteDirection("Turn left onto Jasper Ave", 1, 1)
        val third = RouteDirection("Arrive at destination", 2, 2)
        val route = LocationRoute(
            coordinates = listOf(
                RouteCoordinate(53.5461, -113.5080),
                RouteCoordinate(53.5440, -113.5080),
                RouteCoordinate(53.5410, -113.4950),
            ),
            distanceMeters = 1_200.0,
            durationSeconds = 300.0,
            directions = listOf(first, second, third),
        )

        assertEquals(second, upcomingRouteDirection(route, first))
        assertEquals(third, upcomingRouteDirection(route, second))
        assertNull(upcomingRouteDirection(route, third))
        assertNull(upcomingRouteDirection(route, null))
    }

    @Test
    fun distanceToDirectionMetersComputesRemainingSegmentsAlongPolyline() {
        val coords = listOf(
            RouteCoordinate(53.5400, -113.5000),
            RouteCoordinate(53.5450, -113.5000),
            RouteCoordinate(53.5450, -113.4900),
        )
        val turn = RouteDirection("Turn right", 2, 2)
        val route = LocationRoute(coords, 1_500.0, 240.0, listOf(turn))

        val expected = supportDistanceMeters(53.5400, -113.5000, 53.5450, -113.5000) +
            supportDistanceMeters(53.5450, -113.5000, 53.5450, -113.4900)
        val computed = distanceToDirectionMeters(route, 53.5400, -113.5000, turn)!!
        assertEquals(expected, computed, 0.5)

        assertEquals(0.0, distanceToDirectionMeters(route, 53.5450, -113.4900, turn)!!, 0.001)
    }

    @Test
    fun formatArrivalTimeFormatsClockOffsetAndHandlesInvalidSeconds() {
        val nowMillis = 1_700_000_000_000L
        val expectedNineMinutes = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nowMillis + 540_000L))
        assertEquals(expectedNineMinutes, formatArrivalTime(540.0, nowMillis))

        val expectedZero = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nowMillis))
        assertEquals(expectedZero, formatArrivalTime(Double.NaN, nowMillis))
        assertTrue(formatArrivalTime(0.0, nowMillis).isNotBlank())
    }
}
