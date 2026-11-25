package com.olive.app.data.location

import android.app.Application
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DestinationSearchAndLocationControllerDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun mockConnection(code: Int, body: String): HttpsURLConnection =
        Mockito.mock(HttpsURLConnection::class.java).apply {
            Mockito.`when`(responseCode).thenReturn(code)
            Mockito.`when`(inputStream).thenReturn(ByteArrayInputStream(body.toByteArray()))
        }

    @Test
    fun destinationSearchSuggestParsesPlacesGeocodingErrorsAndCache(): Unit = runBlocking {
        DestinationAutocompleteClient.clearCache()
        val searchBoxJson = """
            {
              "features": [
                {
                  "id": "poi-1",
                  "geometry": { "type": "Point", "coordinates": [-113.4938, 53.5461] },
                  "properties": {
                    "feature_type": "poi",
                    "name": "Edmonton Library",
                    "full_address": "7 Sir Winston Churchill Sq, Edmonton, AB"
                  }
                },
                {
                  "id": "cat-1",
                  "geometry": { "type": "Point", "coordinates": [-113.5, 53.5] },
                  "properties": { "feature_type": "category", "name": "Ignored Category" }
                }
              ]
            }
        """.trimIndent()
        val geocodeJson = """
            {
              "features": [
                {
                  "id": "addr-1",
                  "geometry": { "type": "Point", "coordinates": [-113.49, 53.54] },
                  "properties": {
                    "feature_type": "address",
                    "name": "100 Main St",
                    "address": "100 Main St",
                    "place_formatted": "Edmonton, AB"
                  }
                }
              ]
            }
        """.trimIndent()

        var calls = 0
        val suggestions = DestinationAutocompleteClient.suggest(
            query = "Edmonton Library",
            proximityLatitude = 53.5461,
            proximityLongitude = -113.4938,
            mapboxToken = "pk.fake-token",
            useGeoapifyFallback = false,
            includePlaces = true,
            openConnection = { _: URL ->
                calls++
                mockConnection(200, searchBoxJson)
            },
        )
        assertEquals(1, suggestions.size)
        assertEquals(DestinationSuggestionKind.BusinessOrLandmark, suggestions.first().kind)

        // Cached hit avoids opening another HTTP connection
        val cached = DestinationAutocompleteClient.suggest(
            query = "Edmonton Library",
            proximityLatitude = 53.5461,
            proximityLongitude = -113.4938,
            mapboxToken = "pk.fake-token",
            useGeoapifyFallback = false,
            includePlaces = true,
            openConnection = { _: URL -> error("should use cache") },
        )
        assertEquals(1, cached.size)
        assertEquals(1, calls)

        // Empty places falls back to geocoding
        val fallbackCalls = mutableListOf<String>()
        val geo = DestinationAutocompleteClient.suggest(
            query = "100 Main St",
            mapboxToken = "pk.fake-token",
            useGeoapifyFallback = false,
            includePlaces = true,
            openConnection = { url: URL ->
                fallbackCalls += url.toString()
                if (fallbackCalls.size == 1) mockConnection(200, """{"features":[]}""")
                else mockConnection(200, geocodeJson)
            },
        )
        assertEquals(1, geo.size)
        assertEquals(DestinationSuggestionKind.Address, geo.first().kind)

        // HTTP error and IOException return empty safely
        val err = DestinationAutocompleteClient.suggest(
            query = "Broken Query",
            mapboxToken = "pk.fake-token",
            useGeoapifyFallback = false,
            openConnection = { mockConnection(500, "{}") },
        )
        assertTrue(err.isEmpty())
        val ioErr = DestinationAutocompleteClient.suggest(
            query = "Network Down",
            mapboxToken = "pk.fake-token",
            useGeoapifyFallback = false,
            openConnection = { throw IOException("offline") },
        )
        assertTrue(ioErr.isEmpty())
        assertTrue(DestinationAutocompleteClient.suggest("a", mapboxToken = "").isEmpty())
        assertTrue(DestinationAutocompleteClient.parseMapboxDestinations("not json").isEmpty())
        DestinationAutocompleteClient.clearCache()
    }

    @Test
    fun destinationSearchResolveFreeTextAndLocationControllerQueueWork(): Unit = runBlocking {
        val places = listOf(
            HotlineResource(
                id = "h-1",
                region = "AB",
                city = "Edmonton",
                name = "Safe Haven Shelter",
                description = "Support",
                kind = HotlineResourceKind.Shelter,
                phone = "555-0100",
                website = "https://example.org",
                source = "Olive",
                phoneUri = "tel:5550100",
                hours = "24/7",
                address = "100 Jasper Ave",
                latitude = 53.5461,
                longitude = -113.4938,
            ),
        )
        assertNull(DestinationAutocompleteClient.resolveFreeText(context, "   ", places, null, null))
        val coord = DestinationAutocompleteClient.resolveFreeText(context, "53.5461, -113.4938", places, null, null)
        assertNotNull(coord)
        assertEquals(DestinationSuggestionKind.Coordinates, coord!!.kind)

        val localMatch = DestinationAutocompleteClient.resolveFreeText(context, "Safe Haven", places, 53.54, -113.49)
        assertNotNull(localMatch)
        assertTrue(localMatch!!.title.contains("Safe Haven"))

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            val ctrl = handle.session.locationMap
            ctrl.queuePrivateDestination("Clinic", 53.55, -113.50, autoStart = true)
            assertFalse(ctrl.consumePendingAutoStart())
            ctrl.setRouteProfile(RouteProfile.WALKING)
            assertEquals(RouteProfile.WALKING, ctrl.state.routeProfile)
            ctrl.selectNavigationTarget("nonexistent")
            ctrl.closeMap()
            ctrl.clear()
        }
    }
}
