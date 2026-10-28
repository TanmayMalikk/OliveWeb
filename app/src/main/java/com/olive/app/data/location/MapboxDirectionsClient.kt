// Contains traffic-aware routing and local route progress helpers.
package com.olive.app.data

import com.olive.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*

data class RouteCoordinate(val latitude: Double, val longitude: Double)

data class RouteDirection(
    val text: String,
    val fromIndex: Int,
    val toIndex: Int,
)

data class RouteProgress(val distanceMeters: Double, val durationSeconds: Double)

data class LocationRoute(
    val coordinates: List<RouteCoordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val directions: List<RouteDirection>,
) {
    fun nextDirection(latitude: Double, longitude: Double): RouteDirection? {
        val currentIndex = nearestCoordinateIndex(latitude, longitude)
        return directions.firstOrNull { it.toIndex >= currentIndex } ?: directions.lastOrNull()
    }

    fun progress(latitude: Double, longitude: Double): RouteProgress {
        val index = nearestCoordinateIndex(latitude, longitude)
        val segmentLengths = coordinates.zipWithNext { first, second ->
            coordinateDistanceMeters(first, second)
        }
        val totalGeometryDistance = segmentLengths.sum()
        val remainingGeometryDistance = segmentLengths.drop(index).sum()
        val remainingRatio = if (totalGeometryDistance <= 0.0) 0.0
        else (remainingGeometryDistance / totalGeometryDistance).coerceIn(0.0, 1.0)
        return RouteProgress(distanceMeters * remainingRatio, durationSeconds * remainingRatio)
    }

    private fun nearestCoordinateIndex(latitude: Double, longitude: Double): Int =
        coordinates.indices.minByOrNull { index ->
            val point = coordinates[index]
            squaredDistance(latitude, longitude, point.latitude, point.longitude)
        } ?: 0
}

// Requests traffic-aware driving routes. Geoapify remains responsible for address lookup.
internal object MapboxDirectionsClient {
    val configured get() = BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()

    suspend fun drivingRoute(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
    ): LocationRoute? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val coordinates = "$startLongitude,$startLatitude;$endLongitude,$endLatitude"
        val parameters = listOf(
            "alternatives=true",
            "geometries=geojson",
            "overview=full",
            "steps=true",
            "language=en",
            "voice_units=metric",
            "access_token=${encode(BuildConfig.MAPBOX_ACCESS_TOKEN)}",
        ).joinToString("&")
        val connection =
            (java.net.URL("https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$coordinates?$parameters")
                .openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            parseResponse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(json: String): LocationRoute? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val routes = root.optJSONArray("routes") ?: return null
        val route = (0 until routes.length()).mapNotNull(routes::optJSONObject)
            .minByOrNull { it.optDouble("distance", Double.MAX_VALUE) } ?: return null
        val geometry = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val coordinates = parseCoordinates(geometry)
        if (coordinates.size < 2) return null
        val directions = parseDirections(route.optJSONArray("legs"), coordinates)
        return LocationRoute(
            coordinates = coordinates,
            distanceMeters = route.optDouble("distance", 0.0),
            durationSeconds = route.optDouble("duration", 0.0),
            directions = directions,
        )
    }

    private fun parseCoordinates(rows: JSONArray) = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val longitude = row.optDouble(0, Double.NaN)
            val latitude = row.optDouble(1, Double.NaN)
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0)
                add(RouteCoordinate(latitude, longitude))
        }
    }

    private fun parseDirections(
        legs: JSONArray?,
        routeCoordinates: List<RouteCoordinate>,
    ) = buildList {
        if (legs == null) return@buildList
        for (legIndex in 0 until legs.length()) {
            val steps = legs.optJSONObject(legIndex)?.optJSONArray("steps") ?: continue
            for (stepIndex in 0 until steps.length()) {
                val maneuver = steps.optJSONObject(stepIndex)?.optJSONObject("maneuver") ?: continue
                val text = maneuver.optString("instruction")
                val location = maneuver.optJSONArray("location") ?: continue
                if (text.isBlank()) continue
                val longitude = location.optDouble(0, Double.NaN)
                val latitude = location.optDouble(1, Double.NaN)
                val index = routeCoordinates.indices.minByOrNull { routeIndex ->
                    val point = routeCoordinates[routeIndex]
                    squaredDistance(latitude, longitude, point.latitude, point.longitude)
                } ?: 0
                add(RouteDirection(text, index, index))
            }
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal fun distanceFromRouteMeters(
    latitude: Double,
    longitude: Double,
    route: LocationRoute,
): Double {
    if (route.coordinates.isEmpty()) return Double.POSITIVE_INFINITY
    val originLatitudeRadians = Math.toRadians(latitude)
    fun projected(point: RouteCoordinate): Pair<Double, Double> {
        val x = Math.toRadians(point.longitude - longitude) * cos(originLatitudeRadians) * EARTH_RADIUS_METERS
        val y = Math.toRadians(point.latitude - latitude) * EARTH_RADIUS_METERS
        return x to y
    }
    return route.coordinates.zipWithNext().minOfOrNull { (first, second) ->
        val (ax, ay) = projected(first)
        val (bx, by) = projected(second)
        val dx = bx - ax
        val dy = by - ay
        val ratio = if (dx == 0.0 && dy == 0.0) 0.0
        else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        hypot(ax + ratio * dx, ay + ratio * dy)
    } ?: 0.0
}

private fun squaredDistance(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double,
): Double {
    val latitudeScale = cos(Math.toRadians((firstLatitude + secondLatitude) / 2.0))
    val latitudeDelta = firstLatitude - secondLatitude
    val longitudeDelta = (firstLongitude - secondLongitude) * latitudeScale
    return latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
}

private fun coordinateDistanceMeters(first: RouteCoordinate, second: RouteCoordinate): Double {
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a = sin(latitudeDelta / 2).pow(2) +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).pow(2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
