package com.olive.app.data

import com.olive.app.data.location.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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
}
