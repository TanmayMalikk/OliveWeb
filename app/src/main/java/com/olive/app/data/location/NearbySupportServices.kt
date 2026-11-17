package com.olive.app.data.location

import com.olive.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*

internal data class NearbySupportService(
    val id: String, val name: String, val address: String, val latitude: Double, val longitude: Double,
)
internal data class NearbySupportResult(val radiusKm: Int, val services: List<NearbySupportService>)

internal data class SupportMapCenter(val latitude: Double, val longitude: Double)

internal data class LoadedSupportArea(val center: SupportMapCenter, val result: NearbySupportResult) {
    fun covers(point: SupportMapCenter): Boolean =
        supportDistanceMeters(center.latitude, center.longitude, point.latitude, point.longitude) <= result.radiusKm * 1_000
}

internal fun supportDistanceMeters(lat: Double, lon: Double, targetLat: Double, targetLon: Double): Double {
    val dLat = Math.toRadians(targetLat - lat)
    val dLon = Math.toRadians(targetLon - lon)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(targetLat)) * sin(dLon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal suspend fun searchSupportRadii(fetch: suspend (Int) -> List<NearbySupportService>): NearbySupportResult {
    for (radius in listOf(15, 25, 50)) {
        val services = fetch(radius).distinctBy { it.id }
        if (services.isNotEmpty() || radius == 50) return NearbySupportResult(radius, services)
    }
    error("No search radius")
}

internal object NearbySupportServices {
    suspend fun find(
        latitude: Double,
        longitude: Double,
        accessToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
        openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    ): NearbySupportResult = withContext(Dispatchers.IO) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        check(accessToken.isNotBlank()) { "Nearby services are not configured." }

        searchSupportRadii { radius ->
            val parameters = searchParameters(latitude, longitude, radius) + ("access_token" to accessToken)
            val shelters = async { search("category/homeless_shelter", parameters + ("limit" to "25"), openConnection) }
            val support = listOf(
                "family violence", "domestic violence", "sexual assault centre",
                "crisis centre", "distress centre", "crisis hotline", "women's shelter",
            ).map { query ->
                async {
                    search("forward", parameters + mapOf("q" to query, "limit" to "10", "types" to "poi"), openConnection)
                        .filter { isNamedSupportService(it.name) }
                }
            }
            (shelters.await() + support.awaitAll().flatten())
                .distinctBy { Triple(it.name.lowercase(), it.latitude, it.longitude) }
                .map { it to supportDistanceMeters(latitude, longitude, it.latitude, it.longitude) }
                .filter { (_, distance) -> distance <= radius * 1_000 }
                .sortedBy { (_, distance) -> distance }
                .map { (service, _) -> service }
        }
    }

    private fun searchParameters(latitude: Double, longitude: Double, radiusKm: Int): Map<String, String> {
        val angularRadius = radiusKm / 6_371.0
        val latitudeDelta = Math.toDegrees(angularRadius)
        val longitudeDelta = Math.toDegrees(asin((sin(angularRadius) / cos(Math.toRadians(latitude))).coerceIn(0.0, 1.0)))
        val west = longitude - longitudeDelta
        val east = longitude + longitudeDelta
        val south = latitude - latitudeDelta
        val north = latitude + latitudeDelta
        return buildMap {
            put("proximity", "$longitude,$latitude")
            if (west >= -180 && east <= 180 && south > -90 && north < 90) {
                put("bbox", "$west,$south,$east,$north")
            }
        }
    }

    private suspend fun search(
        endpoint: String,
        parameters: Map<String, String>,
        openConnection: (URL) -> HttpsURLConnection,
    ): List<NearbySupportService> {
        currentCoroutineContext().ensureActive()
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val connection = openConnection(URL("https://api.mapbox.com/search/searchbox/v1/$endpoint?$query"))
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            check(connection.responseCode in 200..299) { "Nearby services could not be loaded from Mapbox." }
            parseSupportPlaces(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

// Mapbox may return a broad POI match for a support-related query. Only show recognizable services.
private fun isNamedSupportService(name: String): Boolean =
    listOf("family violence", "domestic violence", "sexual assault", "crisis", "distress", "hotline", "shelter")
        .any { name.contains(it, ignoreCase = true) } &&
        listOf("animal shelter", "picnic shelter", "bus shelter").none { name.contains(it, ignoreCase = true) }

internal fun parseSupportPlaces(raw: String): List<NearbySupportService> {
    val features = checkNotNull(JSONObject(raw).optJSONArray("features")) { "Mapbox returned no feature collection." }
    return (0 until features.length()).mapNotNull { index ->
        val feature = features.optJSONObject(index) ?: return@mapNotNull null
        val properties = feature.optJSONObject("properties") ?: return@mapNotNull null
        if (properties.optString("feature_type") != "poi") return@mapNotNull null
        val name = properties.optString("name").trim()
        val id = properties.optString("mapbox_id").trim()
        val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
        if (geometry.optString("type") != "Point") return@mapNotNull null
        val coordinates = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
        val lat = coordinates.optDouble(1)
        val lon = coordinates.optDouble(0)
        if (name.isBlank() || id.isBlank() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        val address = properties.optString("full_address").ifBlank { properties.optString("address") }
        NearbySupportService(id.take(512), name.take(240), address.take(500), lat, lon)
    }.distinctBy { it.id }
}
