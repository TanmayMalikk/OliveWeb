package com.olive.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HotlineDirectoryTest {
    private val assets=listOf(File("src/main/assets/hotlines"),File("app/src/main/assets/hotlines")).first { it.isDirectory }
    private fun directory()=HotlineDirectory.parse(File(assets,"resources.json").readText(),File(assets,"cities.json").readText())
    @Test fun allCanadianRegionsHaveSourceAttributedResources() {
        val catalog=directory()
        assertEquals(CanadianRegion.entries.map { it.code }.toSet(),catalog.resources.map { it.region }.filter { it!="CA" }.toSet())
        assertTrue(catalog.resources.all { it.source.startsWith("https://") && it.website.startsWith("https://") && it.phone.isNotBlank() })
        assertEquals(catalog.resources.size,catalog.resources.map { it.id }.distinct().size)
    }
    @Test fun cityOnlyProfilesAndLocalizedProvincesResolveWithoutGps() {
        val catalog=directory()
        assertEquals("AB",catalog.regionFor(DeliveryAddress(city="Edmonton")))
        assertEquals("QC",catalog.regionFor(DeliveryAddress(province="Québec")))
        assertEquals("BC",catalog.regionFor(DeliveryAddress(province="Colombie-Britannique")))
        assertEquals("AB",catalog.regionFor(DeliveryAddress(province="CA-AB")))
        assertEquals("AB",catalog.regionFor(DeliveryAddress(province="ألبرتا",country="كندا")))
        assertEquals("ON",catalog.regionFor(DeliveryAddress(province="安大略省",country="加拿大")))
        assertEquals("BC",catalog.regionFor(DeliveryAddress(province="Columbia Británica")))
        assertNull(catalog.regionFor(DeliveryAddress(city="Unknown town")))
    }
    @Test fun explicitProvinceWinsAndPostalCodesDistinguishNorthernTerritories() {
        val catalog=directory()
        assertEquals("ON",catalog.regionFor(DeliveryAddress(city="Edmonton",province="ON",postalCode="T5J 0N3")))
        assertEquals("NU",catalog.regionFor(DeliveryAddress(postalCode="X0A 0H0")))
        assertEquals("NT",catalog.regionFor(DeliveryAddress(postalCode="X1A 2P9")))
        assertEquals("AB",catalog.regionFor(DeliveryAddress(city="Unknown",postalCode="T5J0N3")))
    }
    @Test fun foreignAddressesNeverReceiveCanadianResourcesAutomatically() {
        val catalog=directory()
        assertFalse(catalog.isCanada(DeliveryAddress(city="Edmonton",province="AB",country="United States")))
        assertNull(catalog.regionFor(DeliveryAddress(city="Edmonton",province="AB",country="United States")))
    }
    @Test fun localProvinceAndNationalResultsRespectServiceAreas() {
        val catalog=directory()
        val edmonton=catalog.matching("AB","Edmonton")
        assertTrue(edmonton.any { it.city=="Edmonton" })
        assertTrue(edmonton.any { it.region=="CA" })
        assertTrue(edmonton.any { it.name=="Family Violence Info Line" })
        assertFalse(edmonton.any { it.region=="ON" })
        assertFalse(catalog.matching("AB","Calgary").any { it.city=="Edmonton" })
        assertTrue(catalog.matching("CA","").all { it.region=="CA" })
    }
    @Test fun searchesMatchAccentsNamesAndUnformattedPhoneNumbers() {
        val catalog=directory()
        assertTrue(catalog.matching("QC","","violence conjugale").any { it.name=="SOS violence conjugale" })
        assertTrue(catalog.matching("AB","Edmonton","7804824357").any { it.phone=="780-482-4357" })
        assertTrue(catalog.matching("ON","Toronto","1 866 863 0511").any { it.name=="Assaulted Women’s Helpline" })
        assertTrue(catalog.matching("AB","Edmonton","not-a-real-service").isEmpty())
    }
    @Test fun dialerTargetsStripTrackingWithoutChangingTheNumber() {
        assertEquals("988",hotlineDialNumber("tel:988?oai_link_source=model_response_hotline"))
        assertEquals("+18006686868",hotlineDialNumber("tel:+18006686868"))
        assertNull(hotlineDialNumber("tel:*123#"))
        assertNull(hotlineDialNumber("tel:"))
        assertNull(hotlineDialNumber("intent:988"))
    }
    @Test fun unsafeLinksAndMismatchedDialTargetsAreRejected() {
        val text=File(assets,"resources.json").readText()
        val cities=File(assets,"cities.json").readText()
        for(unsafe in listOf(text.replaceFirst("https://","javascript:"),text.replace("tel:988?","tel:911?"))) {
            try { HotlineDirectory.parse(unsafe,cities);fail("Unsafe hotline data must fail validation") }
            catch(_:IllegalArgumentException) { /* Invalid bundled data is never made actionable. */ }
        }
    }
}
