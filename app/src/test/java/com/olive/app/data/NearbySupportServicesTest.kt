package com.olive.app.data

import com.olive.app.data.location.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.HttpsURLConnection

class NearbySupportServicesTest {
    private val service = NearbySupportService("public-place", "Sample crisis support", "Public address", 53.5, -113.5)

    @Test fun coveredAreasUseEachSearchRadiusIncludingEmptySearches() {
        val area = LoadedSupportArea(SupportMapCenter(53.5, -113.5), NearbySupportResult(15, listOf(service)))
        val outside = SupportMapCenter(53.7, -113.5)
        assertTrue(area.covers(SupportMapCenter(53.6, -113.5)))
        assertFalse(area.covers(outside))
        assertTrue(area.copy(result = NearbySupportResult(25, listOf(service))).covers(outside))
        assertTrue(area.copy(result = NearbySupportResult(50, emptyList())).covers(outside))
    }

    @Test fun expandsOnlyWhenEmptyAndNeverSearchesBeyondFiftyKilometres() = runBlocking {
        for (firstResultRadius in listOf(15, 25, 50, 0)) {
            val searched = mutableListOf<Int>()
            val result = searchSupportRadii { radius ->
                searched += radius
                if (radius == firstResultRadius) listOf(service, service) else emptyList()
            }
            assertEquals(if (firstResultRadius == 0) listOf(15, 25, 50) else listOf(15, 25, 50).takeWhile { it <= firstResultRadius }, searched)
            assertEquals(if (firstResultRadius == 0) 50 else firstResultRadius, result.radiusKm)
            assertEquals(if (firstResultRadius == 0) 0 else 1, result.services.size)
        }
    }

    @Test fun distanceUsesMetresAndHandlesIdenticalAndDistantPoints() {
        assertEquals(0.0, supportDistanceMeters(53.5, -113.5, 53.5, -113.5), 0.001)
        assertTrue(supportDistanceMeters(53.5, -113.5, 53.6, -113.5) in 11_000.0..11_200.0)
        assertTrue(supportDistanceMeters(53.5, -113.5, 54.5, -113.5) > 50_000)
    }

    @Test fun readsMapboxLongitudeFirstAndRejectsInvalidOrNonVenueResults() {
        val result = parseSupportPlaces("""{"features":[
            {"geometry":{"type":"Point","coordinates":[-113.5,53.5]},"properties":{"mapbox_id":"support","feature_type":"poi","name":"Public support centre","full_address":"Sample address"}},
            {"geometry":{"type":"Point","coordinates":[-113.5,53.5]},"properties":{"mapbox_id":"missing-name","feature_type":"poi"}},
            {"geometry":{"type":"Point","coordinates":[-113.5,153.5]},"properties":{"mapbox_id":"invalid","feature_type":"poi","name":"Bad point"}},
            {"geometry":{"type":"Point","coordinates":[-113.5,53.5]},"properties":{"mapbox_id":"city","feature_type":"place","name":"City centre"}}
        ]}""")
        assertEquals(listOf("support"), result.map { it.id })
        assertEquals("Sample address", result.single().address)
        assertEquals(53.5, result.single().latitude, 0.0)
        assertEquals(-113.5, result.single().longitude, 0.0)
    }

    @Test
    fun mapboxSearchExpandsAfterFilteringAndSortsDeduplicatedSupportByDistance() = runBlocking<Unit> {
        val calls = ConcurrentLinkedQueue<Pair<URL, HttpsURLConnection>>()
        val shelter = feature("shelter", "Fixture women's shelter", 53.675)
        val crisis = feature("crisis", "Fixture crisis centre", 53.650)
        val duplicate = feature("alias", "Fixture crisis centre", 53.650)
        val noisy = feature("animal", "Fixture animal shelter", 53.51)
        val distant = feature("far", "Distant crisis centre", 54.1)
        val result = NearbySupportServices.find(53.5, -113.5, "fixture token") { url ->
            val category = url.path.endsWith("category/homeless_shelter")
            val items = if (category) listOf(shelter) else listOf(crisis, duplicate, noisy, distant)
            val response = JSONObject().put("features", JSONArray(items)).toString()
            val connection = mock(HttpsURLConnection::class.java)
            `when`(connection.responseCode).thenReturn(200)
            `when`(connection.inputStream).thenReturn(response.byteInputStream())
            calls += url to connection
            connection
        }
        assertEquals(25, result.radiusKm)
        assertEquals(listOf("crisis", "shelter"), result.services.map { it.id })
        assertEquals(16, calls.size)
        val boxes = calls.map { (url, connection) ->
            assertEquals("https", url.protocol)
            assertEquals("api.mapbox.com", url.host)
            val args = url.query.split('&').associate { parameter ->
                parameter.substringBefore('=') to URLDecoder.decode(parameter.substringAfter('='), "UTF-8")
            }
            assertEquals("fixture token", args["access_token"])
            assertEquals("-113.5,53.5", args["proximity"])
            verify(connection).disconnect()
            args.getValue("bbox")
        }.toSet()
        assertEquals(2, boxes.size)
    }

    @Test
    fun missingCredentialsAndInvalidMapCoordinatesNeverSendARequest() = runBlocking<Unit> {
        val request: (URL) -> HttpsURLConnection = { error("A request should never run") }
        assertTrue(runCatching { NearbySupportServices.find(53.5, -113.5, "", request) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { NearbySupportServices.find(91.0, -113.5, "fixture", request) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun feature(id: String, name: String, latitude: Double) = JSONObject()
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(-113.5, latitude))))
        .put("properties", JSONObject().put("feature_type", "poi").put("name", name).put("mapbox_id", id))

}
