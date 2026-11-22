// Tests Canadian support numbers, all five categories, mapped addresses, and city/region matching.
package com.olive.app.data

import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.safety.CanadianRegion
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.data.safety.hotlineDialNumber
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HotlineDirectoryTest {
    private val assets = listOf(
        File("src/main/assets/hotlines/provinces"),
        File("app/src/main/assets/hotlines/provinces")
    )
        .first { it.isDirectory }

    private fun files() =
        assets.listFiles { file -> file.name.matches(Regex("Resources[A-Z]{2}\\.json")) }.orEmpty()
            .associate { it.name.removePrefix("Resources").removeSuffix(".json") to it.readText() }

    private fun directory() = HotlineDirectory.parseProvinceFiles(files())

    @Test
    fun datasetsCoverEveryCanadianRegionAndAllFiveCategories() {
        val catalog = directory()
        assertEquals(
            CanadianRegion.entries.map { it.code }.toSet(),
            catalog.resources.map { it.region }.toSet()
        )
        CanadianRegion.entries.forEach { region ->
            val regionKinds = catalog.resources.filter { it.region == region.code }.map { it.kind }.toSet()
            assertEquals(
                "Region ${region.code} must include all five service categories",
                HotlineResourceKind.entries.toSet(),
                regionKinds,
            )
        }
        assertTrue(catalog.resources.size >= 110)
        assertTrue(catalog.mapLocations().size >= 35)
        assertTrue(catalog.resources.all {
            it.source.startsWith("https://") &&
                it.website.startsWith("https://") &&
                it.phone.isNotBlank() &&
                it.description.isNotBlank() &&
                it.hours.isNotBlank() &&
                (it.address.isBlank() || it.hasMapLocation)
        })
        assertTrue(catalog.resources.any { it.is247 })
        assertEquals(catalog.resources.size, catalog.resources.map { it.id }.distinct().size)
    }

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

    @Test
    fun localProvinceKindSavedAnd247FiltersWork() {
        val catalog = directory()
        val edmonton = catalog.matching("AB", "Edmonton")
        assertTrue(edmonton.any { it.name == "Family Violence Info Line" })
        assertTrue(edmonton.any { it.kind == HotlineResourceKind.Shelter })
        assertTrue(edmonton.any { it.kind == HotlineResourceKind.LegalAid && it.hasMapLocation })
        assertTrue(edmonton.any { it.kind == HotlineResourceKind.Counseling && it.hasMapLocation })
        assertTrue(edmonton.any { it.kind == HotlineResourceKind.CrisisService && it.hasMapLocation })
        assertFalse(edmonton.any { it.region == "ON" })
        assertFalse(catalog.matching("AB", "Calgary").any { it.city == "Edmonton" })
        assertTrue(catalog.matching("AB", "Unknown Alberta town").all { it.region == "AB" })
        assertEquals(catalog.resources.size, catalog.matching("CA", "").size)

        val legalOnly = catalog.matching("AB", "Edmonton", kind = HotlineResourceKind.LegalAid)
        assertTrue(legalOnly.isNotEmpty() && legalOnly.all { it.kind == HotlineResourceKind.LegalAid })

        val sampleSaved = setOf(edmonton.first().id, catalog.matching("ON", "Toronto").first().id)
        assertEquals(sampleSaved, catalog.matching("CA", "", savedIds = sampleSaved).map { it.id }.toSet())
        assertTrue(catalog.matching("CA", "", onlyAlwaysOpen = true).all { it.is247 })
    }

    @Test
    fun searchesIncludeNamesDescriptionsCategoriesAddressesAndPhoneNumbers() {
        val catalog = directory()
        assertTrue(catalog.matching("QC", "Montreal", "violence conjugale").isNotEmpty())
        assertTrue(
            catalog.matching("AB", "Edmonton", "7804824357").any { it.phone == "780-482-4357" }
        )
        assertTrue(
            catalog.matching("ON", "Toronto", "shelter")
                .any { it.kind == HotlineResourceKind.Shelter }
        )
        assertTrue(
            catalog.matching("AB", "Edmonton", "Gateway Blvd").any { it.name.contains("Momentum") }
        )
        assertTrue(catalog.matching("AB", "Edmonton", "not-a-real-service").isEmpty())
    }

    @Test
    fun dialerTargetsStripTrackingWithoutChangingTheNumber() {
        assertEquals("988", hotlineDialNumber("tel:988?oai_link_source=model_response_hotline"))
        assertEquals("+18006686868", hotlineDialNumber("tel:+18006686868"))
        assertNull(hotlineDialNumber("tel:*123#"))
        assertNull(hotlineDialNumber("tel:"))
        assertNull(hotlineDialNumber("intent:988"))
    }

    @Test
    fun unsafeLinksInvalidPhonesAndIncompleteCoordinatesAreRejected() {
        val originals = files()
        val alberta = checkNotNull(originals["AB"])
        for (unsafe in listOf(
            alberta.replaceFirst("https://", "javascript:"),
            alberta.replaceFirst("780-482-4357", "CALL-NOW"),
            alberta.replaceFirst("\"lat\": 53.5416,", "\"lat\": 120.0,"),
            alberta.replaceFirst("\"lng\": -113.4892,", ""),
        )) {
            try {
                HotlineDirectory.parseProvinceFiles(originals + ("AB" to unsafe))
                fail("Invalid hotline data must fail validation")
            } catch (_: IllegalArgumentException) {
                // Invalid bundled data is never made actionable.
            }
        }
    }
}
