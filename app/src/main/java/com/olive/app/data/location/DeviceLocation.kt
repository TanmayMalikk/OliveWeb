//suppressing SameParameterValue since 'value' in buildconfig is always MAPBOX_ACCESS_TOKEN which is dynamic
// Const val will not work since value is not known at compile time.
@file:Suppress("SameParameterValue")
// Users and approved supporters: reads the device position for safe-app maps.
package com.olive.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import com.olive.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.account.DeliveryAddress
import java.net.URL

internal data class DeviceCoordinates(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long
)

// Stops a shared device-location listener when a feature no longer needs GPS updates.
internal class DeviceLocationUpdates internal constructor(
    private val fusedLocation: FusedLocationProviderClient,
    private val callback: LocationCallback,
) {
    // Cancels location callbacks when the request is no longer needed.
    fun stop() {
        fusedLocation.removeLocationUpdates(callback)
    }
}

// Safe App and cover features: provides private one-off or continuous device GPS readings.
internal object DeviceLocation {
    // Uses the same high-accuracy GPS source for safe-app maps and private fitness routes.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    fun startUpdates(
        context: Context,
        intervalMillis: Long = 5_000L,
        minimumDistanceMeters: Float = 5f,
        onLocation: (DeviceCoordinates) -> Unit,
    ): DeviceLocationUpdates? {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null
        val manager = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null

        val fusedLocation = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val callback = object : LocationCallback() {
            // Converts the newest Android location into the app's map format.
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    onLocation(
                        DeviceCoordinates(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy.takeIf { location.hasAccuracy() },
                            timestamp = location.time,
                        )
                    )
                }
            }
        }
        val priority = if (fineGranted) Priority.PRIORITY_HIGH_ACCURACY
        else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis((intervalMillis / 2L).coerceAtLeast(1_000L))
            .setMinUpdateDistanceMeters(minimumDistanceMeters)
            .build()
        fusedLocation.requestLocationUpdates(request, callback, context.mainLooper)
        return DeviceLocationUpdates(fusedLocation, callback)
    }

    // Reads coordinates from the best available device location.
    suspend fun currentCoordinates(context: Context): DeviceCoordinates? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null
        return withTimeoutOrNull(15.seconds) {
            val location = fusedCurrent(context) ?: current(context, manager) ?: recent(manager)
            ?: return@withTimeoutOrNull null
            DeviceCoordinates(
                location.latitude,
                location.longitude,
                location.accuracy.takeIf { location.hasAccuracy() },
                location.time
            )
        }
    }

    // Geocodes a typed address into map coordinates.
    suspend fun coordinatesForAddress(
        context: Context,
        address: DeliveryAddress
    ): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        val query = listOf(
            address.line1,
            address.city,
            address.province,
            address.postalCode,
            address.country
        ).filter(String::isNotBlank).joinToString(", ")
        if (query.isBlank()) return null
        val address = forwardGeocode(Geocoder(context, Locale.getDefault()), query) ?: return null
        return address.latitude to address.longitude
    }

    // Reads the phone location and converts it into the account's general address.
    suspend fun address(context: Context): DeliveryAddress? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager) || !Geocoder.isPresent()) return null
        return withTimeoutOrNull(15.seconds) {
            val location = fusedCurrent(context) ?: current(context, manager) ?: recent(manager)
            ?: return@withTimeoutOrNull null
            val address = geocode(context, location) ?: return@withTimeoutOrNull null
            DeliveryAddress(
                city = address.locality ?: address.subAdminArea.orEmpty(),
                province = address.adminArea.orEmpty(),
                postalCode = address.postalCode.orEmpty(),
                country = address.countryCode ?: address.countryName.orEmpty()
            )
                .takeIf { it.country.isNotBlank() && (it.city.isNotBlank() || it.province.isNotBlank() || it.postalCode.isNotBlank()) }
        }
    }

    // Rejects an old cached GPS point when a fresh location is required.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun recent(manager: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= MAX_CACHED_LOCATION_AGE_MS }
            .minByOrNull { location ->
                val ageSeconds = ((now - location.time).coerceAtLeast(0L) / 1_000L).toFloat()
                ageSeconds * 2f + (if (location.hasAccuracy()) location.accuracy else 1_000f)
            }
    }

    // Requests a fresh location from Google Play services.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun fusedCurrent(context: Context): Location? {
        val cancellation = CancellationTokenSource()
        return try {
            val request =
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0L).setDurationMillis(14_000L).build()
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, cancellation.token).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // A location fix is optional; callers handle null by retaining the last known location or showing unavailable.
            null
        } finally {
            cancellation.cancel()
        }
    }

    // Requests the best available current device location.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun current(context: Context, manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) }
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    cancellation,
                    ContextCompat.getMainExecutor(context)
                ) {
                    if (continuation.isActive) continuation.resume(it)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    // Builds the reverse-geocoding request that turns coordinates into an address.
    private suspend fun geocode(context: Context, location: Location): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            runCatching {
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                    // Runs the geocode callback when location sharing finishes that step.
                    object : Geocoder.GeocodeListener {
                        // Runs the geocode callback when location sharing finishes that step.
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        // Returns a readable location error when the device request fails.
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        } else legacyReverseGeocode(geocoder, location)
    }

    // Builds the address search request that returns map coordinates.
    private suspend fun forwardGeocode(geocoder: Geocoder, query: String): Address? =
        // Skip the next action unless the Android version is at least 33.
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            runCatching {
                geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    // Runs the geocode callback when location sharing finishes that step.
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    // Returns a readable location error when the device request fails.
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        } else legacyForwardGeocode(geocoder, query)

    // Gives location sharing the legacy forward geocode it needs.
    @Suppress("DEPRECATION")
    private suspend fun legacyForwardGeocode(geocoder: Geocoder, query: String): Address? =
        withContext(Dispatchers.IO) {
            // Android 9–12 expose only this blocking overload, so it must run away from the UI thread.
            runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
        }

    // Uses Android's older geocoder callback on devices below Android 13.
    @Suppress("DEPRECATION")
    private suspend fun legacyReverseGeocode(geocoder: Geocoder, location: Location): Address? =
        withContext(Dispatchers.IO) {
            // Android 9–12 expose only this blocking overload, so it must run away from the UI thread.
            runCatching {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
        }

    private const val MAX_CACHED_LOCATION_AGE_MS = 5 * 60 * 1_000L
}

// Safe contacts: gets traffic-aware routes to a user and tracks route progress.

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
    // Finds the next turn after the route point closest to the user's location.
    fun nextDirection(latitude: Double, longitude: Double): RouteDirection? {
        val currentIndex = nearestCoordinateIndex(latitude, longitude)
        return directions.firstOrNull { it.toIndex >= currentIndex } ?: directions.lastOrNull()
    }

    // Estimates the distance and time left from the nearest route point.
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

    // Finds which route point is closest to the supplied latitude and longitude.
    private fun nearestCoordinateIndex(latitude: Double, longitude: Double): Int =
        coordinates.indices.minByOrNull { index ->
            val point = coordinates[index]
            squaredDistance(latitude, longitude, point.latitude, point.longitude)
        } ?: 0
}

// Requests traffic-aware driving routes. Geoapify remains responsible for address lookup.
internal object MapboxDirectionsClient {
    val configured get() = BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()

    // Requests a driving route and converts the response into map points and directions.
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
            (URL("https://api.mapbox.com/directions/v5/mapbox/driving-traffic/$coordinates?$parameters")
                .openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
        // Always close the network connection, including when the server rejects the request.
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            parseResponse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    // Reads the shortest valid route from the Mapbox response.
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

    // Converts GeoJSON longitude-latitude pairs into the app's route points.
    private fun parseCoordinates(rows: JSONArray) = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val longitude = row.optDouble(0, Double.NaN)
            val latitude = row.optDouble(1, Double.NaN)
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0)
                add(RouteCoordinate(latitude, longitude))
        }
    }

    // Matches each written turn instruction to its nearest route point.
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

    // Escapes the API token before placing it in the request URL.
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}

// Calculates how far a location is from the nearest part of a route.
internal fun distanceFromRouteMeters(
    latitude: Double,
    longitude: Double,
    route: LocationRoute,
): Double {
    if (route.coordinates.isEmpty()) return Double.POSITIVE_INFINITY
    val originLatitudeRadians = Math.toRadians(latitude)
    // Projects nearby map coordinates onto a flat metre grid for this calculation.
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

// Compares two nearby coordinates without doing an unnecessary square root.
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

// Uses the haversine formula to measure the distance between two route points.
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

// Staff and admins: reads the user locations published for the protected organization map.

/** Listens to the organization location feed after Firebase verifies the account role. */
internal class AdminLocationRepository(
    private val databaseProvider: () -> FirebaseDatabase = LocationDatabase::instance,
) {
    // Keeps the admin map updated whenever a user's phone publishes a new location.
    fun observe(
        onUpdate: (List<MapLocationPoint>) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription {
        val reference = databaseProvider().reference.child(ADMIN_LOCATION_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val points = snapshot.children.mapNotNull(::locationPoint).sortedBy { it.name }
                onUpdate(points)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }
        reference.addValueEventListener(listener)
        return ChatSubscription { reference.removeEventListener(listener) }
    }

    // Rejects incomplete or invalid Firebase rows before they reach the map.
    private fun locationPoint(snapshot: DataSnapshot): MapLocationPoint? {
        val uid = snapshot.key?.takeIf(String::isNotBlank) ?: return null
        val latitude = snapshot.child("latitude").getValue(Double::class.java) ?: return null
        val longitude = snapshot.child("longitude").getValue(Double::class.java) ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: return null
        val active = snapshot.child("active").getValue(Boolean::class.java) == true
        val name = snapshot.child("name").getValue(String::class.java).orEmpty().ifBlank { uid }
        val accuracy = snapshot.child("accuracy").getValue(Double::class.java)?.toFloat()
        return MapLocationPoint(
            uid = uid,
            name = name,
            latitude = latitude,
            longitude = longitude,
            updatedAt = updatedAt,
            live = active,
            accuracy = accuracy,
            lastKnown = !active,
        )
    }

    private companion object {
        const val ADMIN_LOCATION_PATH = "adminLiveLocations"
    }
}
