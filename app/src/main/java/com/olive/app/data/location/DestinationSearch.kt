// Safe-app users: parses free-use GPS coordinates and fetches fast address autocomplete suggestions.
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
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.olive.app.BuildConfig
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.HotlineResource
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal enum class DestinationSuggestionKind {
    Coordinates,
    DirectoryPlace,
    BusinessOrLandmark,
    Address,
}

internal data class DestinationSuggestion(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val kind: DestinationSuggestionKind,
)

/**
 * Parses decimal or compass-labeled latitude/longitude text such as:
 * - "53.5461, -113.4938"
 * - "53.5461 -113.4938"
 * - "(53.5461, -113.4938)"
 * - "53.5461°N, 113.4938°W"
 * - "N 53.5461 W 113.4938"
 *
 * @param input The raw input to parse or validate.
 * @return The parsed latitude and longitude in that order, or null when the input is invalid.
 */
internal fun parseCoordinates(input: String): Pair<Double, Double>? {
    val cleaned = input.trim().removeSurrounding("(", ")").trim()
    if (cleaned.isBlank()) return null

    // Match optional prefix/suffix hemisphere markers (N/S, E/W) with numeric degrees.
    val compassRegex = Regex(
        """^\s*([NS])?\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*([NS])?\s*[,;\s]+\s*([EW])?\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*([EW])?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    val match = compassRegex.matchEntire(cleaned) ?: return null
    val latHemi = (match.groupValues[1].ifEmpty { match.groupValues[3] }).uppercase(Locale.ROOT)
    val lonHemi = (match.groupValues[4].ifEmpty { match.groupValues[6] }).uppercase(Locale.ROOT)
    // Reject contradictory prefix + suffix hemisphere markers or ambiguous bare integers without comma/hemisphere.
    if (match.groupValues[1].isNotEmpty() && match.groupValues[3].isNotEmpty()) return null
    if (match.groupValues[4].isNotEmpty() && match.groupValues[6].isNotEmpty()) return null
    val hasExplicitSeparator = ',' in cleaned || ';' in cleaned || '°' in cleaned ||
        latHemi.isNotEmpty() || lonHemi.isNotEmpty()
    val rawLatStr = match.groupValues[2]
    val rawLonStr = match.groupValues[5]
    if (!hasExplicitSeparator && '.' !in rawLatStr && '.' !in rawLonStr) return null

    val rawLat = rawLatStr.toDoubleOrNull() ?: return null
    val rawLon = rawLonStr.toDoubleOrNull() ?: return null
    val lat = if (latHemi == "S" && rawLat > 0) -rawLat else rawLat
    val lon = if (lonHemi == "W" && rawLon > 0) -rawLon else rawLon
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

/**
 * Computes instant local suggestions (direct coordinates + mapped directory places matching [query]).
 *
 * @param query The search text entered by the user.
 * @param directoryPlaces The mapped support resources available for local search.
 * @param coordinateTitle The display title for a direct coordinate match.
 * @param coordinateSubtitle The supporting label for a direct coordinate match.
 * @return The matching coordinate and local directory suggestions.
 */
internal fun localDestinationSuggestions(
    query: String,
    directoryPlaces: List<HotlineResource>,
    coordinateTitle: (String) -> String,
    coordinateSubtitle: String,
): List<DestinationSuggestion> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    return buildList {
        parseCoordinates(trimmed)?.let { (lat, lon) ->
            val formatted = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            add(
                DestinationSuggestion(
                    id = "coord:$formatted",
                    title = coordinateTitle(formatted),
                    subtitle = coordinateSubtitle,
                    latitude = lat,
                    longitude = lon,
                    kind = DestinationSuggestionKind.Coordinates,
                )
            )
        }
        val normalizedQuery = HotlineDirectory.normalize(trimmed)
        if (normalizedQuery.length >= 2) {
            val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
            directoryPlaces.asSequence()
                .filter { it.hasMapLocation }
                .filter { place ->
                    val haystack = HotlineDirectory.normalize("${place.name} ${place.address} ${place.city} ${place.region}")
                    tokens.all { it in haystack }
                }
                .take(3)
                .forEach { place ->
                    add(
                        DestinationSuggestion(
                            id = "dir:${place.id}",
                            title = place.name,
                            subtitle = place.address,
                            latitude = place.latitude!!,
                            longitude = place.longitude!!,
                            kind = DestinationSuggestionKind.DirectoryPlace,
                        )
                    )
                }
        }
    }
}

internal object DestinationAutocompleteClient {
    private const val MAX_CACHE_ENTRIES = 64
    private val cache = object : LinkedHashMap<String, List<DestinationSuggestion>>(MAX_CACHE_ENTRIES, 0.75f, true) {
        /**
         * Evicts the least-recently-used autocomplete cache entry past [MAX_CACHE_ENTRIES].
         *
         * @param eldest The least recently used cache entry being considered for eviction.
         * @return True when the cache entry should be evicted to enforce the size limit.
         */
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DestinationSuggestion>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val cacheLock = Any()
    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Looks up addresses and, for map navigation, businesses and landmarks near the search origin.
     *
     * @param query The search text entered by the user.
     * @param proximityLatitude The latitude used to bias search results, if available.
     * @param proximityLongitude The longitude used to bias search results, if available.
     * @param mapboxToken The Mapbox access token used for the request.
     * @param useGeoapifyFallback Whether to try Geoapify when Mapbox has no suggestions.
     * @param includePlaces Whether to use Mapbox Search Box for named places as well as addresses.
     * @param openConnection The injected factory used to open an HTTPS connection.
     * @return The available address suggestions for the current query and location bias.
     */
    suspend fun suggest(
        query: String,
        proximityLatitude: Double? = null,
        proximityLongitude: Double? = null,
        mapboxToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
        useGeoapifyFallback: Boolean = true,
        includePlaces: Boolean = false,
        openConnection: ((URL) -> HttpsURLConnection)? = null,
    ): List<DestinationSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2 || parseCoordinates(trimmed) != null) return@withContext emptyList()
        val key = cacheKey(trimmed, proximityLatitude, proximityLongitude, includePlaces)
        synchronized(cacheLock) { cache[key] }?.let { return@withContext it }

        var placeLookupAvailable = !includePlaces || mapboxToken.isBlank()
        val mapboxResult = if (mapboxToken.isNotBlank()) {
            /**
             * Loads the chosen Mapbox search endpoint using the configured transport.
             *
             * @param places Whether to use Search Box.
             * @return Parsed destinations, or null when the request fails.
             */
            suspend fun fetch(places: Boolean): List<DestinationSuggestion>? {
                val url = buildMapboxUrl(trimmed, proximityLatitude, proximityLongitude, mapboxToken, places)
                return if (openConnection == null) fetchMapboxSuggestionsOkHttp(url)
                else fetchMapboxSuggestionsLegacy(url, openConnection)
            }
            val places = if (includePlaces) fetch(places = true) else null
            if (places != null) placeLookupAvailable = true
            if (!places.isNullOrEmpty()) places else fetch(places = false)
        } else {
            emptyList()
        }
        val mapboxSucceeded = mapboxResult != null
        val mapboxItems = mapboxResult.orEmpty()
        val geoConfigured = useGeoapifyFallback && GeoapifyClient.configured
        var geoSucceeded = !geoConfigured
        val resolved = if (mapboxItems.isNotEmpty() || !geoConfigured) {
            mapboxItems
        } else {
            try {
                val items = GeoapifyClient.autocomplete(trimmed, cityOnly = false, minLength = 2).mapNotNull { item ->
                    val lat = item.address.latitude ?: return@mapNotNull null
                    val lon = item.address.longitude ?: return@mapNotNull null
                    DestinationSuggestion(
                        id = "geo:${item.id}",
                        title = item.primary,
                        subtitle = item.secondary,
                        latitude = lat,
                        longitude = lon,
                        kind = DestinationSuggestionKind.Address,
                    )
                }
                geoSucceeded = true
                items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (placeLookupAvailable && (resolved.isNotEmpty() || (mapboxSucceeded && geoSucceeded))) {
            synchronized(cacheLock) { cache[key] = resolved }
        }
        resolved
    }

    /**
     * Resolves coordinates, directory entries, named places, and street addresses for map navigation.
     *
     * @param context The Android context used for resources or system services.
     * @param query The search text entered by the user.
     * @param directoryPlaces The mapped support resources available for local search.
     * @param proximityLatitude The latitude used to bias search results, if available.
     * @param proximityLongitude The longitude used to bias search results, if available.
     * @return The best matching destination, or null when the input cannot be resolved.
     */
    suspend fun resolveFreeText(
        context: Context,
        query: String,
        directoryPlaces: List<HotlineResource>,
        proximityLatitude: Double?,
        proximityLongitude: Double?,
    ): DestinationSuggestion? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return null
        parseCoordinates(trimmed)?.let { (lat, lon) ->
            val formatted = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            return DestinationSuggestion("coord:$formatted", formatted, "", lat, lon, DestinationSuggestionKind.Coordinates)
        }
        localDestinationSuggestions(trimmed, directoryPlaces, { it }, "").firstOrNull()?.let { return it }
        suggest(trimmed, proximityLatitude, proximityLongitude, includePlaces = true).firstOrNull()?.let { return it }
        return DeviceLocation.coordinatesForQuery(context, trimmed)?.let { (lat, lon) ->
            DestinationSuggestion("geocoder:$trimmed", trimmed, "", lat, lon, DestinationSuggestionKind.Address)
        }
    }

    /**
     * Builds a Search Box or Geocoding request with proximity bias when coordinates are available.
     *
     * @param query The search text entered by the user.
     * @param proximityLatitude The latitude used to bias search results, if available.
     * @param proximityLongitude The longitude used to bias search results, if available.
     * @param mapboxToken The Mapbox access token used for the request.
     * @param includePlaces Whether to send the query to Search Box.
     * @return The forward-geocoding URL with the query and any proximity bias encoded.
     */
    private fun buildMapboxUrl(
        query: String,
        proximityLatitude: Double?,
        proximityLongitude: Double?,
        mapboxToken: String,
        includePlaces: Boolean = false,
    ): String {
        val params = buildList {
            add("q=${encode(query)}")
            if (includePlaces) add("limit=6") else {
                add("autocomplete=true")
                add("limit=5")
            }
            if (proximityLatitude != null && proximityLongitude != null &&
                proximityLatitude in -90.0..90.0 && proximityLongitude in -180.0..180.0
            ) {
                add("proximity=$proximityLongitude,$proximityLatitude")
            }
            add("access_token=${encode(mapboxToken)}")
        }.joinToString("&")
        val path = if (includePlaces) "searchbox/v1/forward" else "geocode/v6/forward"
        return "https://api.mapbox.com/search/$path?$params"
    }

    /**
     * Fetches Mapbox autocomplete suggestions asynchronously via the shared OkHttp client.
     *
     * @param url The request URL to open or fetch.
     * @return The parsed Mapbox suggestions, or null when Mapbox cannot provide a response.
     */
    private suspend fun fetchMapboxSuggestionsOkHttp(url: String): List<DestinationSuggestion>? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        val call = sharedHttpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                /**
                 * Resumes the coroutine with null when the Mapbox request fails.
                 *
                 * @param call The OkHttp call that completed or failed.
                 * @param e The network exception reported by OkHttp.
                 */
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                /**
                 * Parses and resumes Mapbox suggestions when the HTTP response succeeds.
                 *
                 * @param call The OkHttp call that completed or failed.
                 * @param response The upstream response to read and validate.
                 */
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { res ->
                            if (!res.isSuccessful) null
                            else parseMapboxDestinations(res.body.string())
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    /**
     * Fetches Mapbox autocomplete suggestions via an injected [HttpsURLConnection] factory.
     *
     * @param url The request URL to open or fetch.
     * @param openConnection The injected factory used to open an HTTPS connection.
     * @return The parsed Mapbox suggestions, or null when Mapbox cannot provide a response.
     */
    private suspend fun fetchMapboxSuggestionsLegacy(
        url: String,
        openConnection: (URL) -> HttpsURLConnection,
    ): List<DestinationSuggestion>? {
        currentCoroutineContext().ensureActive()
        return try {
            val connection = openConnection(URL(url))
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) null
                else parseMapboxDestinations(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses Mapbox Search Box and Geocoding GeoJSON responses into navigable suggestions.
     *
     * @param raw The unprocessed response or value to parse.
     * @return The valid destinations parsed from the Mapbox features.
     */
    internal fun parseMapboxDestinations(raw: String): List<DestinationSuggestion> = runCatching {
        val features = JSONObject(raw).optJSONArray("features") ?: return@runCatching emptyList()
        (0 until features.length()).mapNotNull { index ->
            val feature = features.optJSONObject(index) ?: return@mapNotNull null
            val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
            if (geometry.optString("type") != "Point") return@mapNotNull null
            val coords = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                return@mapNotNull null
            }
            val props = feature.optJSONObject("properties") ?: return@mapNotNull null
            val type = props.optString("feature_type")
            if (type == "category") return@mapNotNull null
            val isPlace = type == "poi"
            val name = props.optString("name").ifBlank { props.optString("name_preferred") }.trim()
            val fullAddress = props.optString("full_address")
                .ifBlank {
                    listOf(props.optString("address"), props.optString("place_formatted"))
                        .filter { it.isNotBlank() }.distinct().joinToString(", ")
                }
                .trim()
            val title = name.ifBlank { fullAddress.substringBefore(',').trim() }
            if (title.isBlank()) return@mapNotNull null
            val subtitle = if (isPlace) fullAddress else fullAddress.removePrefix(title).trimStart(',', ' ')
            val id = feature.optString("id").ifBlank { props.optString("mapbox_id") }.ifBlank { "$lat,$lon" }
            DestinationSuggestion(
                id = "mb:$id",
                title = title.take(160),
                subtitle = subtitle.take(240),
                latitude = lat,
                longitude = lon,
                kind = if (isPlace) DestinationSuggestionKind.BusinessOrLandmark else DestinationSuggestionKind.Address,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Builds a quantized cache key combining normalized [query] text and rounded coordinates.
     *
     * @param query The search text entered by the user.
     * @param lat The latitude in decimal degrees.
     * @param lon The longitude in decimal degrees.
     * @param includePlaces Whether the lookup includes named places.
     * @return The key combining the normalized search query with quantized coordinates.
     */
    private fun cacheKey(query: String, lat: Double?, lon: Double?, includePlaces: Boolean): String {
        val norm = query.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val precision = if (includePlaces) 100.0 else 10.0
        val latBucket = lat?.let { (it * precision).roundToLong() }
        val lonBucket = lon?.let { (it * precision).roundToLong() }
        return "$includePlaces|$norm|$latBucket|$lonBucket"
    }

    /**
     * URL-encodes [value] using UTF-8.
     *
     * @param value The query parameter value to URL-encode.
     * @return The query parameter text encoded using UTF-8.
     */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Clears all cached destination autocomplete entries. */
    internal fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }
}

/**
 * Normalizes any heading angle in degrees into `[0f, 360f)`.
 *
 * @param degrees The input angle expressed in degrees.
 * @return The equivalent angle in the range from 0 inclusive to 360 exclusive.
 */
internal fun normalizeHeadingDegrees(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    val mod = degrees % 360f
    return if (mod < 0f) mod + 360f else if (mod >= 360f) mod - 360f else mod
}

/**
 * Returns the shortest signed angular difference from [fromDegrees] to [toDegrees] in `(-180f, 180f]`.
 *
 * @param fromDegrees The starting heading in degrees.
 * @param toDegrees The target heading in degrees.
 * @return The shortest signed rotation in degrees from the starting heading to the target heading.
 */
internal fun shortestAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
    val diff = (normalizeHeadingDegrees(toDegrees) - normalizeHeadingDegrees(fromDegrees)) % 360f
    return when {
        diff > 180f -> diff - 360f
        diff <= -180f -> diff + 360f
        else -> diff
    }
}

/**
 * Applies circular exponential smoothing across the 0°/360° boundary.
 *
 * @param current The current position, instruction, or value used as the starting point.
 * @param target The desired value to move or interpolate toward.
 * @param alpha The smoothing factor applied to the new heading sample.
 * @return The updated heading after circular interpolation between the two samples.
 */
internal fun smoothHeading(current: Float?, target: Float, alpha: Float = 0.22f): Float {
    val normalizedTarget = normalizeHeadingDegrees(target)
    if (current == null || !current.isFinite()) return normalizedTarget
    val clampedAlpha = alpha.coerceIn(0f, 1f)
    val delta = shortestAngleDelta(current, normalizedTarget)
    return normalizeHeadingDegrees(current + clampedAlpha * delta)
}

/**
 * Prefers GPS movement bearing once moving at walking speed (>= 0.8 m/s), falling back to compass azimuth.
 *
 * @param gpsBearing The latest GPS movement bearing in degrees, if available.
 * @param gpsSpeedMetersPerSecond The latest GPS speed in meters per second, if available.
 * @param compassAzimuth The latest compass heading, if it is available.
 * @return The chosen movement bearing or compass heading, or null when neither is usable.
 */
internal fun selectPreferredHeading(
    gpsBearing: Float?,
    gpsSpeedMetersPerSecond: Float?,
    compassAzimuth: Float?,
): Float? {
    val movingWithBearing = gpsBearing != null && gpsBearing.isFinite() &&
        gpsSpeedMetersPerSecond != null && gpsSpeedMetersPerSecond.isFinite() &&
        gpsSpeedMetersPerSecond >= 0.8f
    return when {
        movingWithBearing -> normalizeHeadingDegrees(gpsBearing)
        compassAzimuth != null && compassAzimuth.isFinite() -> normalizeHeadingDegrees(compassAzimuth)
        gpsBearing != null && gpsBearing.isFinite() -> normalizeHeadingDegrees(gpsBearing)
        else -> null
    }
}

/**
 * Adjusts magnetic azimuth for the current Android display rotation.
 *
 * @param azimuthDegrees The magnetic azimuth in degrees.
 * @param surfaceRotation The Android display rotation constant.
 * @return The heading adjusted for the device's display orientation.
 */
internal fun adjustAzimuthForDisplayRotation(azimuthDegrees: Float, surfaceRotation: Int): Float {
    val offset = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 270f
        else -> 0f
    }
    return normalizeHeadingDegrees(azimuthDegrees + offset)
}

/**
 * Computes a tilt-invariant compass azimuth from a screen-remapped 3x3 rotation matrix
 * (east = col/row world X, north = world Y). Smoothly blends device +Y (when held flat)
 * and back-of-phone -Z (when held upright while walking) to eliminate gimbal lock at 45°–90° tilt.
 *
 * @param rotationMatrix The rotation matrix used to calculate the heading.
 * @return The computed compass azimuth, or null if the input matrix cannot be used.
 */
internal fun computeTiltInvariantAzimuthDegrees(rotationMatrix: FloatArray): Float? {
    if (rotationMatrix.size < 9) return null
    val flatWeight = rotationMatrix[8].coerceAtLeast(0f)
    val uprightWeight = rotationMatrix[7]
    var east = rotationMatrix[1] * flatWeight - rotationMatrix[2] * uprightWeight
    var north = rotationMatrix[4] * flatWeight - rotationMatrix[5] * uprightWeight
    if (east * east + north * north < 1e-4f) {
        east = rotationMatrix[1]
        north = rotationMatrix[4]
    }
    if (!east.isFinite() || !north.isFinite() || (east == 0f && north == 0f)) return null
    val degrees = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
    return normalizeHeadingDegrees(degrees)
}

/**
 * Converts magnetic compass heading to true north heading using magnetic declination.
 *
 * @param magneticHeadingDegrees The magnetic north heading in degrees.
 * @param declinationDegrees The correction from magnetic north to true north in degrees.
 * @return The true-north heading normalized to a full rotation.
 */
internal fun applyMagneticDeclination(magneticHeadingDegrees: Float, declinationDegrees: Float): Float {
    val normalized = normalizeHeadingDegrees(magneticHeadingDegrees)
    if (!declinationDegrees.isFinite()) return normalized
    return normalizeHeadingDegrees(normalized + declinationDegrees)
}

// Grab a one-off coarse location for cover content; nothing from here is saved to the account.
object CoverLocation {
    /**
     * Joins available address parts into the city label shown by the cover.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param fallback Fallback supplied to [city].
     * @return The resulting city string.
     */
    suspend fun city(context: Context, fallback: String): String {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return fallback
        val manager = context.getSystemService(LocationManager::class.java)
        val cached = newestCachedLocation(manager)
        val location = cached ?: withTimeoutOrNull(6.seconds) { current(context, manager) }
        if (location == null) return fallback
        return reverseGeocode(context, location) ?: fallback
    }

    /**
     * Keeps the newest cached GPS point returned by Android.
     *
     * @param manager Manager supplied to [newestCachedLocation].
     * @return The resulting [Location], or `null` if unavailable.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun newestCachedLocation(manager: LocationManager): Location? =
        manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull(Location::getTime)

    /**
     * Requests the best available current device location.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param manager Manager supplied to [current].
     * @return The resulting [Location], or `null` if unavailable.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun current(context: Context, manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val provider = when {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) continuation.resume(null)
            else runCatching {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, null as CancellationSignal?,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    /**
     * Gives location sharing the reverse geocode it needs.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param location Location supplied to [reverseGeocode].
     * @return The resulting reverse geocode string, or `null` when unavailable.
     */
    private suspend fun reverseGeocode(context: Context, location: Location): String? {
        if (Build.VERSION.SDK_INT < 33 || !Geocoder.isPresent()) return null
        val address = withTimeoutOrNull(6.seconds) {
            suspendCancellableCoroutine<Address?> { continuation ->
                Geocoder(context, Locale.getDefault()).getFromLocation(
                    location.latitude, location.longitude, 1,
                    // Converts the cover's coordinates into the first local address returned by Android.
                    object : Geocoder.GeocodeListener {
                        /**
                         * Runs the geocode callback when location sharing finishes that step.
                         *
                         * @param addresses Collection of addresses values to process.
                         */
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        /**
                         * Returns a readable location error when the device request fails.
                         *
                         * @param errorMessage Optional error message supplied to [onError].
                         */
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            }
        } ?: return null
        return withContext(Dispatchers.Default) {
            listOfNotNull(address.locality, address.adminArea, address.countryName)
                .filter(String::isNotBlank).joinToString(", ").takeUnless(String::isBlank)
        }
    }
}
