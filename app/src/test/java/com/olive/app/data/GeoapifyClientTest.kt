// Tests address-result parsing without making real network requests.
package com.olive.app.data

import com.olive.app.data.location.GeoapifyClient
import org.junit.Assert.*
import org.junit.Test

class GeoapifyClientTest {
    // Input: a complete Canadian address result. Expected: every supported address field is mapped.
    @Test
    fun completeResultMapsEveryAddressField() {
        val result = GeoapifyClient.parseGeoapifySuggestions(
            """{"results":[{"place_id":"place-1","lat":53.5461,"lon":-113.4938,"address_line1":"1 Olive Street","city":"Edmonton","state_code":"AB","postcode":"T5J 0N3","country":"Canada","formatted":"1 Olive Street, Edmonton, AB"}]}"""
        ).single()

        assertEquals("place-1", result.id)
        assertEquals("1 Olive Street", result.primary)
        assertEquals("Edmonton, AB", result.secondary)
        assertEquals("Edmonton", result.address.city)
        assertEquals(53.5461, result.address.latitude!!, 0.000001)
        assertEquals(-113.4938, result.address.longitude!!, 0.000001)
    }

    // Input: town, village, municipality, and county variants. Expected: the first available place name is used.
    @Test
    fun alternateCityFieldsUseTheFirstAvailablePlaceName() {
        val keys = listOf("town", "village", "municipality", "county")
        keys.forEach { key ->
            val result = GeoapifyClient.parseGeoapifySuggestions(
                """{"results":[{"lat":50,"lon":-110,"$key":"Olive Place"}]}"""
            ).single()
            assertEquals("Olive Place", result.address.city)
            assertEquals("Olive Place", result.primary)
        }
    }

    // Input: rows with missing, non-finite, and out-of-world coordinates. Expected: none become suggestions.
    @Test
    fun invalidCoordinatesAreRejected() {
        val raw = """{"results":[
            {"lat":53},
            {"lat":"Infinity","lon":10},
            {"lat":91,"lon":10},
            {"lat":10,"lon":181},
            {"lat":"wrong","lon":"types"}
        ]}"""
        assertTrue(GeoapifyClient.parseGeoapifySuggestions(raw).isEmpty())
    }

    // Input: malformed JSON and wrong result shapes. Expected: parsing fails closed with an empty list.
    @Test
    fun malformedPayloadsFailClosed() {
        assertTrue(GeoapifyClient.parseGeoapifySuggestions("").isEmpty())
        assertTrue(GeoapifyClient.parseGeoapifySuggestions("not json").isEmpty())
        assertTrue(GeoapifyClient.parseGeoapifySuggestions("{}").isEmpty())
        assertTrue(GeoapifyClient.parseGeoapifySuggestions("""{"results":"wrong"}""").isEmpty())
        assertTrue(GeoapifyClient.parseGeoapifySuggestions("""{"results":[null,7,"row"]}""").isEmpty())
    }

    // Input: fields beyond the app's storage limit. Expected: persisted address fields stop at 160 characters.
    @Test
    fun oversizedAddressFieldsAreBounded() {
        val long = "x".repeat(300)
        val result = GeoapifyClient.parseGeoapifySuggestions(
            """{"results":[{"lat":53,"lon":-113,"address_line1":"$long","city":"$long","state":"$long","postcode":"$long","country":"$long"}]}"""
        ).single()

        assertEquals(160, result.address.line1.length)
        assertEquals(160, result.address.city.length)
        assertEquals(160, result.address.province.length)
        assertEquals(160, result.address.postalCode.length)
        assertEquals(160, result.address.country.length)
        assertTrue(result.address.locationValid)
    }

    // Input: missing labels and IDs with valid coordinates. Expected: a stable fallback label and ID are produced.
    @Test
    fun missingDisplayFieldsUseStableFallbacks() {
        val result = GeoapifyClient.parseGeoapifySuggestions(
            """{"results":[{"lat":53,"lon":-113,"formatted":"Edmonton, Alberta"}]}"""
        ).single()
        assertEquals("Edmonton", result.primary)
        assertEquals("Alberta", result.secondary)
        assertEquals("Edmonton, Alberta", result.id)
        assertEquals("Canada", result.address.country)
    }
}
