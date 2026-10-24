// Contains the geoapify client code for this part of Olive.
package com.olive.app.data

import com.olive.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

// Groups the data and actions for address suggestion.
data class AddressSuggestion(
    val id: String,
    val primary: String,
    val secondary: String,
    val address: DeliveryAddress
)

// Typed address lookup only; live device coordinates never pass through this provider.
internal object GeoapifyClient {
    val configured get() = BuildConfig.GEOAPIFY_API_KEY.isNotBlank()

    suspend fun autocomplete(query: String, cityOnly: Boolean): List<AddressSuggestion> =
        withContext(Dispatchers.IO) {
            if (!configured || query.trim().length < 3) return@withContext emptyList()
            val parameters = buildList {
                add("text=${encode(query.trim())}")
                add("format=json")
                add("filter=countrycode:ca")
                add(
                    "limit=5"
                )
                if (cityOnly) add("type=city")
                add("apiKey=${encode(BuildConfig.GEOAPIFY_API_KEY)}")
            }.joinToString("&")
            val connection =
                (java.net.URL("https://api.geoapify.com/v1/geocode/autocomplete?$parameters")
                    .openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod =
                        "GET"
                    setRequestProperty("Accept", "application/json")
                }
            try {
                if (connection.responseCode !in 200..299) return@withContext emptyList()
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val rows = root.optJSONArray("results") ?: return@withContext emptyList()
                (0 until rows.length()).mapNotNull { index ->
                    suggestion(
                        rows.optJSONObject(index) ?: return@mapNotNull null
                    )
                }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun coordinates(address: DeliveryAddress): Pair<Double, Double>? {
        address.latitude?.let { lat -> address.longitude?.let { return lat to it } }
        val query = listOf(
            address.line1,
            address.city,
            address.province,
            address.postalCode,
            address.country
        ).filter(String::isNotBlank).joinToString(", ")
        return autocomplete(
            query,
            false
        ).firstOrNull()?.address?.let { value -> value.latitude?.let { lat -> value.longitude?.let { lat to it } } }
    }

    private fun suggestion(row: JSONObject): AddressSuggestion? {
        val lat = row.optDouble("lat", Double.NaN)
        val lon = row.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val city = listOf(
            "city",
            "town",
            "village",
            "municipality",
            "county"
        ).firstNotNullOfOrNull { key -> row.optString(key).takeIf(String::isNotBlank) }.orEmpty()
        val line1 = row.optString("address_line1").ifBlank { row.optString("street") }
        val address = DeliveryAddress(
            line1 = line1.take(160),
            city = city.take(160),
            province = row.optString("state_code").ifBlank { row.optString("state") }.take(160),
            postalCode = row.optString("postcode").take(160),
            country = row.optString("country").ifBlank { "Canada" }.take(160),
            latitude = lat,
            longitude = lon
        )
        val formatted = row.optString("formatted").ifBlank {
            listOf(line1, city, address.province).filter(String::isNotBlank).joinToString(", ")
        }
        val primary = line1.ifBlank { city.ifBlank { formatted.substringBefore(',') } }
        return AddressSuggestion(
            row.optString("place_id", formatted),
            primary,
            formatted.removePrefix(primary).trimStart(',', ' '),
            address
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
