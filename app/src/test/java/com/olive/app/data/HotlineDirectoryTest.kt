// Tests Canadian support numbers and city-based matches for users.
package com.olive.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HotlineDirectoryTest {
    private val assets = listOf(
        File("src/main/assets/hotlines/provinces"),
        File("app/src/main/assets/hotlines/provinces")
    )
        .first { it.isDirectory }

    // Checks the files step with the test's sample data.
    private fun files() =
        assets.listFiles { file -> file.name.matches(Regex("Resources[A-Z]{2}\\.json")) }.orEmpty()
            .associate { it.name.removePrefix("Resources").removeSuffix(".json") to it.readText() }

    // Checks the directory step with the test's sample data.
    private fun directory() = HotlineDirectory.parseProvinceFiles(files())

    // Inputs: the values shown below. Expected: lars datasets cover every canadian region.
    @Test
    fun larsDatasetsCoverEveryCanadianRegion() {
        val catalog = directory()
        assertEquals(
            CanadianRegion.entries.map { it.code }.toSet(),
            catalog.resources.map { it.region }.toSet()
        )
        assertEquals(98, catalog.resources.size)
        assertEquals(55, catalog.resources.count { it.kind == HotlineResourceKind.Hotline })
        assertEquals(43, catalog.resources.count { it.kind == HotlineResourceKind.Shelter })
        assertTrue(catalog.resources.all {
            it.source.startsWith("https://") && it.website.startsWith("https://") &&
                    it.phone.isNotBlank() && it.description.isNotBlank()
        })
        assertEquals(catalog.resources.size, catalog.resources.map { it.id }.distinct().size)
    }

    // Inputs: the values shown below. Expected: city only profiles and localized provinces resolve without gps.
    @Test
    fun cityOnlyProfilesAndLocalizedProvincesResolveWithoutGps() {
        val catalog = directory()
        assertEquals("AB", catalog.regionFor(DeliveryAddress(city = "Edmonton")))
        assertEquals("QC", catalog.regionFor(DeliveryAddress(province = "Québec")))
        assertEquals("BC", catalog.regionFor(DeliveryAddress(province = "Colombie-Britannique")))
        assertEquals("AB", catalog.regionFor(DeliveryAddress(province = "CA-AB")))
        assertEquals(
            "AB",
            catalog.regionFor(DeliveryAddress(province = "ألبرتا", country = "كندا"))
        )
        assertEquals(
            "ON",
            catalog.regionFor(DeliveryAddress(province = "安大略省", country = "加拿大"))
        )
        assertEquals("BC", catalog.regionFor(DeliveryAddress(province = "Columbia Británica")))
        assertNull(catalog.regionFor(DeliveryAddress(city = "Unknown town")))
    }

    // Inputs: the values shown below. Expected: explicit province wins and postal codes distinguish northern territories.
    @Test
    fun explicitProvinceWinsAndPostalCodesDistinguishNorthernTerritories() {
        val catalog = directory()
        assertEquals(
            "ON",
            catalog.regionFor(
                DeliveryAddress(
                    city = "Edmonton",
                    province = "ON",
                    postalCode = "T5J 0N3"
                )
            )
        )
        assertEquals("NU", catalog.regionFor(DeliveryAddress(postalCode = "X0A 0H0")))
        assertEquals("NT", catalog.regionFor(DeliveryAddress(postalCode = "X1A 2P9")))
        assertEquals(
            "AB",
            catalog.regionFor(DeliveryAddress(city = "Unknown", postalCode = "T5J0N3"))
        )
    }

    // Inputs: the values shown below. Expected: foreign addresses never receive canadian resources automatically.
    @Test
    fun foreignAddressesNeverReceiveCanadianResourcesAutomatically() {
        val catalog = directory()
        assertFalse(
            catalog.isCanada(
                DeliveryAddress(
                    city = "Edmonton",
                    province = "AB",
                    country = "United States"
                )
            )
        )
        assertNull(
            catalog.regionFor(
                DeliveryAddress(
                    city = "Edmonton",
                    province = "AB",
                    country = "United States"
                )
            )
        )
    }

    // Inputs: the values shown below. Expected: local province and national results respect service areas.
    @Test
    fun localProvinceAndNationalResultsRespectServiceAreas() {
        val catalog = directory()
        val edmonton = catalog.matching("AB", "Edmonton")
        assertTrue(edmonton.any { it.name == "Family Violence Info Line" })
        assertTrue(edmonton.any { it.kind == HotlineResourceKind.Shelter })
        assertFalse(edmonton.any { it.region == "ON" })
        assertFalse(catalog.matching("AB", "Calgary").any { it.city == "Edmonton" })
        assertTrue(catalog.matching("AB", "Unknown Alberta town").all { it.region == "AB" })
        assertEquals(catalog.resources.size, catalog.matching("CA", "").size)
    }

    // Inputs: the values shown below. Expected: searches include names descriptions categories and phone numbers.
    @Test
    fun searchesIncludeNamesDescriptionsCategoriesAndPhoneNumbers() {
        val catalog = directory()
        assertTrue(catalog.matching("QC", "Montreal", "violence conjugale").isNotEmpty())
        assertTrue(
            catalog.matching("AB", "Edmonton", "7804824357").any { it.phone == "780-482-4357" })
        assertTrue(
            catalog.matching("ON", "Toronto", "shelter")
                .any { it.kind == HotlineResourceKind.Shelter })
        assertTrue(catalog.matching("AB", "Edmonton", "not-a-real-service").isEmpty())
    }

    // Inputs: the values shown below. Expected: dialer targets strip tracking without changing the number.
    @Test
    fun dialerTargetsStripTrackingWithoutChangingTheNumber() {
        assertEquals("988", hotlineDialNumber("tel:988?oai_link_source=model_response_hotline"))
        assertEquals("+18006686868", hotlineDialNumber("tel:+18006686868"))
        assertNull(hotlineDialNumber("tel:*123#"))
        assertNull(hotlineDialNumber("tel:"))
        assertNull(hotlineDialNumber("intent:988"))
    }

    // Inputs: the values shown below. Expected: unsafe links and invalid phone numbers are rejected.
    @Test
    fun unsafeLinksAndInvalidPhoneNumbersAreRejected() {
        val originals = files()
        val alberta = checkNotNull(originals["AB"])
        for (unsafe in listOf(
            alberta.replaceFirst("https://", "javascript:"),
            alberta.replaceFirst("780-482-4357", "CALL-NOW")
        )) {
            try {
                HotlineDirectory.parseProvinceFiles(originals + ("AB" to unsafe))
                fail("Unsafe hotline data must fail validation")
            } catch (_: IllegalArgumentException) { /* Invalid bundled data is never made actionable. */
            }
        }
    }
}
