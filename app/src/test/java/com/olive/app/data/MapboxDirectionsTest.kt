// Tests route results shown to safe contacts navigating to a user.
package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class MapboxDirectionsTest {
    // Inputs: the values shown below. Expected: response uses shortest alternative and keeps traffic eta and directions.
    @Test
    fun responseUsesShortestAlternativeAndKeepsTrafficEtaAndDirections() {
        val route = MapboxDirectionsClient.parseResponse(
            """
            {"code":"Ok","routes":[
              {"distance":1800.0,"duration":300.0,"geometry":{"coordinates":[[-113.50,53.53],[-113.48,53.55]]},"legs":[]},
              {"distance":1450.0,"duration":240.0,"geometry":{"coordinates":[[-113.49,53.54],[-113.48,53.55]]},
               "legs":[{"steps":[{"maneuver":{"instruction":"Turn right","location":[-113.48,53.55]}}]}]}
            ]}
            """.trimIndent()
        )

        assertNotNull(route)
        assertEquals(1450.0, route!!.distanceMeters, 0.1)
        assertEquals(240.0, route.durationSeconds, 0.1)
        assertEquals(53.54, route.coordinates.first().latitude, 0.0001)
        assertEquals("Turn right", route.directions.single().text)
    }

    // Inputs: the values shown below. Expected: distance from route detects on route and off route locations.
    @Test
    fun distanceFromRouteDetectsOnRouteAndOffRouteLocations() {
        val route = LocationRoute(
            listOf(RouteCoordinate(53.54, -113.49), RouteCoordinate(53.55, -113.49)),
            1_000.0,
            120.0,
            emptyList(),
        )

        assertTrue(distanceFromRouteMeters(53.545, -113.49, route) < 2.0)
        assertTrue(distanceFromRouteMeters(53.545, -113.48, route) > 500.0)
    }

    // Inputs: the values shown below. Expected: route progress decreases near the destination.
    @Test
    fun routeProgressDecreasesNearTheDestination() {
        val route = LocationRoute(
            listOf(
                RouteCoordinate(53.54, -113.49),
                RouteCoordinate(53.545, -113.49),
                RouteCoordinate(53.55, -113.49),
            ),
            1_000.0,
            120.0,
            emptyList(),
        )

        val progress = route.progress(53.545, -113.49)
        assertEquals(500.0, progress.distanceMeters, 15.0)
        assertEquals(60.0, progress.durationSeconds, 2.0)
    }

    // Inputs: the values shown below. Expected: invalid api responses and coordinates do not create routes.
    @Test
    fun invalidApiResponsesAndCoordinatesDoNotCreateRoutes() {
        assertNull(MapboxDirectionsClient.parseResponse("{}"))
        assertNull(MapboxDirectionsClient.parseResponse("""{"code":"NoRoute","routes":[]}"""))
        assertNull(MapboxDirectionsClient.parseResponse("""{"code":"Ok","routes":[]}"""))
        assertNull(MapboxDirectionsClient.parseResponse(
            """{"code":"Ok","routes":[{"geometry":{"coordinates":[[999,999],[0,0]]}}]}"""
        ))
    }

    // Inputs: the values shown below. Expected: empty and single point routes have safe progress and distance values.
    @Test
    fun emptyAndSinglePointRoutesHaveSafeProgressAndDistanceValues() {
        val empty = LocationRoute(emptyList(), 500.0, 60.0, emptyList())
        assertTrue(distanceFromRouteMeters(53.5, -113.5, empty).isInfinite())
        assertEquals(RouteProgress(0.0, 0.0), empty.progress(53.5, -113.5))
        assertNull(empty.nextDirection(53.5, -113.5))

        val single = LocationRoute(
            listOf(RouteCoordinate(53.5, -113.5)),
            500.0,
            60.0,
            listOf(RouteDirection("Arrive", 0, 0)),
        )
        assertEquals(RouteProgress(0.0, 0.0), single.progress(53.5, -113.5))
        assertEquals("Arrive", single.nextDirection(0.0, 0.0)?.text)
    }
}
