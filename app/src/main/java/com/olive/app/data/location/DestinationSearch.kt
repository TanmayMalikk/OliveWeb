// Safe-app users: parses free-use GPS coordinates and fetches fast address autocomplete suggestions.
package com.olive.app.data.location

import android.content.Context
import com.olive.app.BuildConfig
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.HotlineResource
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class DestinationSuggestionKind {
    Coordinates,
    DirectoryPlace,
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DestinationSuggestion>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val cacheLock = Any()

    /**
     * Fetches address autocomplete suggestions using Mapbox Geocoding v6 with Geoapify fallback.
     */
    suspend fun suggest(
        query: String,
        proximityLatitude: Double? = null,
        proximityLongitude: Double? = null,
        mapboxToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
        openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    ): List<DestinationSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2 || parseCoordinates(trimmed) != null) return@withContext emptyList()
        val key = cacheKey(trimmed, proximityLatitude, proximityLongitude)
        synchronized(cacheLock) { cache[key] }?.let { return@withContext it }

        val mapboxResults = if (mapboxToken.isNotBlank()) {
            runCatching {
                fetchMapboxSuggestions(trimmed, proximityLatitude, proximityLongitude, mapboxToken, openConnection)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val resolved = if (mapboxResults.isNotEmpty() || !GeoapifyClient.configured) {
            mapboxResults
        } else {
            runCatching {
                GeoapifyClient.autocomplete(trimmed, cityOnly = false, minLength = 2).mapNotNull { item ->
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
            }.getOrDefault(emptyList())
        }
        if (resolved.isNotEmpty()) {
            synchronized(cacheLock) { cache[key] = resolved }
        }
        resolved
    }

    /**
     * Resolves a submitted free-text destination (coordinates, directory match, autocomplete, or Android Geocoder).
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
        suggest(trimmed, proximityLatitude, proximityLongitude).firstOrNull()?.let { return it }
        return DeviceLocation.coordinatesForQuery(context, trimmed)?.let { (lat, lon) ->
            DestinationSuggestion("geocoder:$trimmed", trimmed, "", lat, lon, DestinationSuggestionKind.Address)
        }
    }

    private fun fetchMapboxSuggestions(
        query: String,
        proximityLatitude: Double?,
        proximityLongitude: Double?,
        mapboxToken: String,
        openConnection: (URL) -> HttpsURLConnection,
    ): List<DestinationSuggestion> {
        val params = buildList {
            add("q=${encode(query)}")
            add("autocomplete=true")
            add("limit=5")
            if (proximityLatitude != null && proximityLongitude != null &&
                proximityLatitude in -90.0..90.0 && proximityLongitude in -180.0..180.0
            ) {
                add("proximity=$proximityLongitude,$proximityLatitude")
            }
            add("access_token=${encode(mapboxToken)}")
        }.joinToString("&")
        val connection = openConnection(URL("https://api.mapbox.com/search/geocode/v6/forward?$params"))
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) emptyList()
            else parseMapboxGeocodeV6(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseMapboxGeocodeV6(raw: String): List<DestinationSuggestion> = runCatching {
        val features = JSONObject(raw).optJSONArray("features") ?: return@runCatching emptyList()
        (0 until features.length()).mapNotNull { index ->
            val feature = features.optJSONObject(index) ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                return@mapNotNull null
            }
            val props = feature.optJSONObject("properties") ?: return@mapNotNull null
            val name = props.optString("name").ifBlank { props.optString("name_preferred") }.trim()
            val fullAddress = props.optString("full_address")
                .ifBlank { props.optString("place_formatted") }
                .trim()
            val title = name.ifBlank { fullAddress.substringBefore(',').trim() }
            if (title.isBlank()) return@mapNotNull null
            val subtitle = fullAddress.removePrefix(title).trimStart(',', ' ')
            val id = feature.optString("id").ifBlank { props.optString("mapbox_id") }.ifBlank { "$lat,$lon" }
            DestinationSuggestion(
                id = "mb:$id",
                title = title.take(160),
                subtitle = subtitle.take(240),
                latitude = lat,
                longitude = lon,
                kind = DestinationSuggestionKind.Address,
            )
        }
    }.getOrDefault(emptyList())

    private fun cacheKey(query: String, lat: Double?, lon: Double?): String {
        val norm = query.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val latBucket = lat?.let { (it * 10.0).roundToLong() }
        val lonBucket = lon?.let { (it * 10.0).roundToLong() }
        return "$norm|$latBucket|$lonBucket"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    internal fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }
}
