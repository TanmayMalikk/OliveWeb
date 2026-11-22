package com.olive.app.data

import com.olive.app.data.location.DestinationAutocompleteClient
import com.olive.app.data.location.DestinationSuggestionKind
import com.olive.app.data.location.localDestinationSuggestions
import com.olive.app.data.location.parseCoordinates
import com.olive.app.data.safety.HotlineDirectory
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DestinationSearchTest {
    private fun loadDirectory(): HotlineDirectory {
        val assets = listOf(
            File("src/main/assets/hotlines/provinces"),
            File("app/src/main/assets/hotlines/provinces"),
        ).first { it.isDirectory }
        val files = assets.listFiles { file -> file.name.matches(Regex("Resources[A-Z]{2}\\.json")) }
            .orEmpty()
            .associate { it.name.removePrefix("Resources").removeSuffix(".json") to it.readText() }
        return HotlineDirectory.parseProvinceFiles(files)
    }

    @Test
    fun parsesCoordinateFormatsAndRejectsInvalidOrAmbiguousInputs() {
        val c1 = parseCoordinates("53.5461, -113.4938")!!
        assertEquals(53.5461, c1.first, 0.00001)
        assertEquals(-113.4938, c1.second, 0.00001)

        val c2 = parseCoordinates("43.6561 -79.3818")!!
        assertEquals(43.6561, c2.first, 0.00001)
        assertEquals(-79.3818, c2.second, 0.00001)

        val c3 = parseCoordinates("(45.5245, -73.5535)")!!
        assertEquals(45.5245, c3.first, 0.00001)
        assertEquals(-73.5535, c3.second, 0.00001)

        val c4 = parseCoordinates("49.2862° N, 123.1169° W")!!
        assertEquals(49.2862, c4.first, 0.00001)
        assertEquals(-123.1169, c4.second, 0.00001)

        val c5 = parseCoordinates("S 33.8688 E 151.2093")!!
        assertEquals(-33.8688, c5.first, 0.00001)
        assertEquals(151.2093, c5.second, 0.00001)

        assertNull(parseCoordinates("95.0, -113.5"))
        assertNull(parseCoordinates("53.5, 190.0"))
        assertNull(parseCoordinates("10123 99"))
        assertNull(parseCoordinates("10123 99 St NW Edmonton"))
    }

    @Test
    fun localDestinationSuggestionsIncludeCoordinatesAndMatchingDirectoryPlaces() {
        val mapped = loadDirectory().mapLocations()
        val coordsOnly = localDestinationSuggestions(
            query = "53.5416, -113.4892",
            directoryPlaces = mapped,
            coordinateTitle = { "Coords: $it" },
            coordinateSubtitle = "Direct route",
        )
        assertEquals(DestinationSuggestionKind.Coordinates, coordsOnly.first().kind)
        assertEquals(53.5416, coordsOnly.first().latitude, 0.00001)

        val places = localDestinationSuggestions(
            query = "Momentum Edmonton",
            directoryPlaces = mapped,
            coordinateTitle = { it },
            coordinateSubtitle = "Direct route",
        )
        assertTrue(places.any {
            it.kind == DestinationSuggestionKind.DirectoryPlace && it.title.contains("Momentum")
        })
    }

    @Test
    fun parsesMapboxGeocodeV6FeaturesSafely() {
        val raw = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "id": "address.123",
                  "geometry": {"type": "Point", "coordinates": [-113.4938, 53.5461]},
                  "properties": {
                    "name": "10123 99 St NW",
                    "full_address": "10123 99 St NW, Edmonton, Alberta T5J 3H1, Canada"
                  }
                },
                {
                  "id": "bad.coords",
                  "geometry": {"type": "Point", "coordinates": [220.0, 99.0]},
                  "properties": {"name": "Invalid Place"}
                }
              ]
            }
        """.trimIndent()
        val parsed = DestinationAutocompleteClient.parseMapboxGeocodeV6(raw)
        assertEquals(1, parsed.size)
        assertEquals("10123 99 St NW", parsed.single().title)
        assertEquals("Edmonton, Alberta T5J 3H1, Canada", parsed.single().subtitle)
        assertEquals(53.5461, parsed.single().latitude, 0.00001)
        assertEquals(-113.4938, parsed.single().longitude, 0.00001)
    }

    @Test
    fun autocompleteCachesValidEmptyResponsesAndRetriesAfterErrors() = kotlinx.coroutines.runBlocking {
        DestinationAutocompleteClient.clearCache()
        var calls = 0
        fun fakeConnection(code: Int, body: String): javax.net.ssl.HttpsURLConnection {
            val stream = java.io.ByteArrayInputStream(body.toByteArray())
            return object : javax.net.ssl.HttpsURLConnection(java.net.URL("https://api.mapbox.com/test")) {
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
                override fun getCipherSuite() = ""
                override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
                override fun getServerCertificates(): Array<java.security.cert.Certificate>? = null
                override fun getResponseCode() = code
                override fun getInputStream() = stream
            }
        }

        // Transient 500 error must not be cached so the next request retries.
        val first = DestinationAutocompleteClient.suggest("Jasper Ave", 53.5, -113.5, "pk.test") {
            calls++
            fakeConnection(500, "{}")
        }
        assertTrue(first.isEmpty())
        assertEquals(1, calls)

        // Valid 200 empty response should be cached for repeat normalized queries.
        val second = DestinationAutocompleteClient.suggest("  jasper   ave ", 53.5, -113.5, "pk.test") {
            calls++
            fakeConnection(200, """{"type":"FeatureCollection","features":[]}""")
        }
        assertTrue(second.isEmpty())
        assertEquals(2, calls)

        val third = DestinationAutocompleteClient.suggest("Jasper Ave", 53.5, -113.5, "pk.test") {
            calls++
            fakeConnection(200, """{"type":"FeatureCollection","features":[]}""")
        }
        assertTrue(third.isEmpty())
        assertEquals(2, calls)
    }
}
