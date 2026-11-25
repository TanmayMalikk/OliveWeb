package com.olive.app.data.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import org.robolectric.Shadows.shadowOf
import androidx.test.core.app.ApplicationProvider
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.ui.users.user.location.bundledMapAsset
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DestinationSearchDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun place(id: String, name: String, lat: Double?, lon: Double?) = HotlineResource(
        id = id,
        region = "AB",
        city = "Edmonton",
        name = name,
        description = "Support shelter",
        kind = HotlineResourceKind.Shelter,
        phone = "780-555-0100",
        website = "https://example.org",
        source = "dir",
        phoneUri = "tel:7805550100",
        address = "123 Jasper Ave",
        latitude = lat,
        longitude = lon,
    )

    @Test
    fun parseCoordinatesHandlesAllValidAndInvalidInputs() {
        val c1 = parseCoordinates("53.5461, -113.4938")!!
        assertEquals(53.5461, c1.first, 1e-4)
        assertEquals(-113.4938, c1.second, 1e-4)

        val c2 = parseCoordinates("(53.5461°N, 113.4938°W)")!!
        assertEquals(53.5461, c2.first, 1e-4)
        assertEquals(-113.4938, c2.second, 1e-4)

        val c3 = parseCoordinates("S 33.8688 E 151.2093")!!
        assertEquals(-33.8688, c3.first, 1e-4)
        assertEquals(151.2093, c3.second, 1e-4)

        assertNull(parseCoordinates(""))
        assertNull(parseCoordinates("10 20"))
        assertNull(parseCoordinates("N 10 N 20 E"))
        assertNull(parseCoordinates("95.0, -113.4"))
        assertNull(parseCoordinates("53.5, -181.0"))
        assertNull(parseCoordinates("53.5461°S W 113.4938°E"))
    }

    @Test
    fun localDestinationSuggestionsAndAutocompleteParseCacheAndErrorsWork() = runBlocking {
        DestinationAutocompleteClient.clearCache()
        val places = listOf(
            place("p1", "Haven Shelter", 53.5461, -113.4938),
            place("p2", "Unmapped Clinic", null, null),
        )
        val local = localDestinationSuggestions("53.5461, -113.4938", places, { "Pin: $it" }, "Coordinates")
        assertEquals(DestinationSuggestionKind.Coordinates, local.first().kind)
        val byName = localDestinationSuggestions("haven edmonton", places, { it }, "Coordinates")
        assertEquals("dir:p1", byName.first().id)

        val validJson = """
            {
              "features": [
                {
                  "id": "poi-1",
                  "geometry": {"type": "Point", "coordinates": [-113.4938, 53.5461]},
                  "properties": {"feature_type": "poi", "name": "City Hall", "full_address": "1 Sir Winston Churchill Sq"}
                },
                {
                  "id": "addr-1",
                  "geometry": {"type": "Point", "coordinates": [-113.5, 53.55]},
                  "properties": {"feature_type": "address", "name": "", "address": "101 Jasper Ave", "place_formatted": "Edmonton, AB"}
                },
                {
                  "id": "cat-1",
                  "geometry": {"type": "Point", "coordinates": [-113.5, 53.55]},
                  "properties": {"feature_type": "category", "name": "Coffee"}
                },
                {
                  "id": "bad-geom",
                  "geometry": {"type": "Polygon", "coordinates": []},
                  "properties": {"name": "Skip"}
                },
                {
                  "id": "bad-coord",
                  "geometry": {"type": "Point", "coordinates": [-200.0, 99.0]},
                  "properties": {"name": "Invalid"}
                }
              ]
            }
        """.trimIndent()
        val parsed = DestinationAutocompleteClient.parseMapboxDestinations(validJson)
        assertEquals(2, parsed.size)
        assertEquals(DestinationSuggestionKind.BusinessOrLandmark, parsed[0].kind)
        assertEquals(DestinationSuggestionKind.Address, parsed[1].kind)
        assertTrue(DestinationAutocompleteClient.parseMapboxDestinations("not-json").isEmpty())

        var calls = 0
        val suggestions = DestinationAutocompleteClient.suggest(
            query = "City Hall",
            proximityLatitude = 53.5444,
            proximityLongitude = -113.4909,
            mapboxToken = "pk.test",
            useGeoapifyFallback = false,
            includePlaces = true,
            openConnection = {
                calls++
                mock(HttpsURLConnection::class.java).apply {
                    `when`(responseCode).thenReturn(200)
                    `when`(inputStream).thenReturn(ByteArrayInputStream(validJson.toByteArray()))
                }
            },
        )
        assertEquals(2, suggestions.size)
        // Second call hits LRU cache
        val cached = DestinationAutocompleteClient.suggest(
            query = "  city   hall ",
            proximityLatitude = 53.5444,
            proximityLongitude = -113.4909,
            mapboxToken = "pk.test",
            useGeoapifyFallback = false,
            includePlaces = true,
            openConnection = { error("Should not hit network on cached key") },
        )
        assertEquals(2, cached.size)
        assertEquals(1, calls)

        // Error & non-200 paths
        DestinationAutocompleteClient.clearCache()
        val failed = DestinationAutocompleteClient.suggest(
            query = "Missing Query",
            mapboxToken = "pk.test",
            useGeoapifyFallback = false,
            includePlaces = false,
            openConnection = {
                mock(HttpsURLConnection::class.java).apply {
                    `when`(responseCode).thenReturn(500)
                }
            },
        )
        assertTrue(failed.isEmpty())

        val threw = DestinationAutocompleteClient.suggest(
            query = "Throw Query",
            mapboxToken = "pk.test",
            useGeoapifyFallback = false,
            includePlaces = false,
            openConnection = { throw IOException("timeout") },
        )
        assertTrue(threw.isEmpty())

        val resolvedCoord = DestinationAutocompleteClient.resolveFreeText(context, "53.54, -113.49", places, null, null)
        assertNotNull(resolvedCoord)
        val resolvedPlace = DestinationAutocompleteClient.resolveFreeText(context, "haven", places, null, null)
        assertEquals("Haven Shelter", resolvedPlace?.title)
        assertNull(DestinationAutocompleteClient.resolveFreeText(context, "   ", places, null, null))
    }

    @Test
    fun headingAndBundledMapAssetHelpersCoverAllBranches() {
        assertEquals(0f, normalizeHeadingDegrees(Float.NaN), 0.01f)
        assertEquals(350f, normalizeHeadingDegrees(-10f), 0.01f)
        assertEquals(-20f, shortestAngleDelta(10f, 350f), 0.01f)
        assertEquals(20f, shortestAngleDelta(350f, 10f), 0.01f)
        assertEquals(90f, adjustAzimuthForDisplayRotation(0f, android.view.Surface.ROTATION_90), 0.01f)
        assertEquals(180f, adjustAzimuthForDisplayRotation(0f, android.view.Surface.ROTATION_180), 0.01f)
        assertEquals(270f, adjustAzimuthForDisplayRotation(0f, android.view.Surface.ROTATION_270), 0.01f)
        assertEquals(90f, selectPreferredHeading(90f, 2f, 180f)!!, 0.01f)
        assertEquals(180f, selectPreferredHeading(90f, 0.1f, 180f)!!, 0.01f)
        assertEquals(90f, selectPreferredHeading(90f, 0.1f, null)!!, 0.01f)
        assertNull(selectPreferredHeading(null, null, null))
        assertEquals(10f, applyMagneticDeclination(5f, 5f), 0.01f)
        assertEquals(5f, applyMagneticDeclination(5f, Float.NaN), 0.01f)
        assertNull(computeTiltInvariantAzimuthDegrees(FloatArray(3)))
        assertNotNull(computeTiltInvariantAzimuthDegrees(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))

        assertNull(bundledMapAsset(context, Uri.parse("http://appassets.androidplatform.net/map/map.html")))
        val eventRes = bundledMapAsset(context, Uri.parse("https://events.mapbox.com/events/v2"))
        assertEquals(204, eventRes?.statusCode)
        val notFoundRes = bundledMapAsset(context, Uri.parse("https://appassets.androidplatform.net/map/unknown.bin"))
        assertEquals(404, notFoundRes?.statusCode)
        bundledMapAsset(context, Uri.parse("https://appassets.androidplatform.net/map/map.html"))
        bundledMapAsset(context, Uri.parse("https://appassets.androidplatform.net/map/mapbox-gl.css"))
        bundledMapAsset(context, Uri.parse("https://appassets.androidplatform.net/map/map.js"))
        bundledMapAsset(context, Uri.parse("http://127.0.0.1:1/tiles/style.json"))
        bundledMapAsset(context, Uri.parse("https://localhost/not-map"))
    }

    @Test
    fun coverLocationAndParseMapboxDestinationsCoverFallbackAndMalformedInputs() = runBlocking {
        // Without permission, CoverLocation.city returns fallback
        assertEquals("Edmonton", CoverLocation.city(context, "Edmonton"))

        // With COARSE_LOCATION + cached GPS location, returns fallback when Geocoder absent/empty
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(LocationManager::class.java)
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 53.5461
            longitude = -113.4938
            time = System.currentTimeMillis()
        })
        assertEquals("FallbackCity", CoverLocation.city(context, "FallbackCity"))

        // Malformed Mapbox GeoJSON inputs
        assertTrue(DestinationAutocompleteClient.parseMapboxDestinations("not-json").isEmpty())
        assertTrue(DestinationAutocompleteClient.parseMapboxDestinations("{}").isEmpty())
        val weirdJson = """
            {
              "features": [
                {"geometry": {"type": "Point", "coordinates": [1.0]}, "properties": {"name": "BadCoords"}},
                {"geometry": {"type": "Point", "coordinates": [999.0, 53.5]}, "properties": {"name": "OutOfBounds"}},
                {"geometry": {"type": "Point", "coordinates": [-113.5, 53.5]}, "properties": {"name": ""}},
                {"geometry": {"type": "Point", "coordinates": [-113.5, 53.5]}, "properties": {"feature_type": "category", "name": "Cat"}},
                {"geometry": {"type": "Point", "coordinates": [-113.5, 53.5]}, "properties": {"address": "100 Main", "place_formatted": "Edmonton, AB", "feature_type": "address"}}
              ]
            }
        """.trimIndent()
        val parsed = DestinationAutocompleteClient.parseMapboxDestinations(weirdJson)
        assertEquals(1, parsed.size)
        assertEquals("100 Main", parsed[0].title)
    }
}
