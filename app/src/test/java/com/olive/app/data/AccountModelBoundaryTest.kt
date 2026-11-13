// Tests account profile serialization, enum compatibility, and malformed stored values.
package com.olive.app.data

import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountModelBoundaryTest {
    // Input: every current account role. Expected: storage names restore the exact role.
    @Test
    fun everyCurrentRoleRoundTripsFromStorage() {
        AccountRole.entries.forEach { role ->
            assertEquals(role, AccountRole.fromStored(role.name))
        }
    }

    // Input: every current cover. Expected: storage names restore the exact cover.
    @Test
    fun everyCurrentCoverRoundTripsFromStorage() {
        CoverType.entries.forEach { cover ->
            assertEquals(cover, CoverType.fromStored(cover.name))
        }
    }

    // Input: a complete Unicode profile with contacts and coordinates. Expected: encrypted JSON loses no fields.
    @Test
    fun completeProfileRoundTripPreservesEveryField() {
        val original = UserProfile(
            username = "olive_user",
            fullName = "Renée 橄榄",
            affectedNames = "Child",
            abuserName = "Private",
            dob = "2000-02-29",
            language = "French",
            situation = "Needs support",
            region = "Edmonton",
            triggerHash = "hash",
            contacts = listOf(SafeContact("c1", "Amina", "555-0100", true, "uid-2")),
            address = DeliveryAddress(
                "1 Olive St",
                "2",
                "Edmonton",
                "AB",
                "T5J 0N3",
                "Canada",
                53.5,
                -113.5
            ),
            coverType = CoverType.NEWS,
            role = AccountRole.USER,
            email = "olive@example.ca",
            phone = "555-0101",
        )
        assertEquals(original, UserProfile.fromJson(original.toJson()))
    }

    // Input: null coordinates. Expected: the serialized map does not invent latitude or longitude values.
    @Test
    fun absentCoordinatesAreOmittedFromStoredMap() {
        val map = DeliveryAddress(city = "Edmonton").toMap()
        assertFalse(map.containsKey("latitude"))
        assertFalse(map.containsKey("longitude"))
    }

    // Input: NaN, infinity, and coordinates outside Earth. Expected: restored coordinates are rejected.
    @Test
    fun unsafeStoredCoordinatesBecomeNull() {
        listOf(
            JSONObject().put("latitude", "NaN").put("longitude", "Infinity"),
            JSONObject().put("latitude", 91).put("longitude", 181),
            JSONObject().put("latitude", -91).put("longitude", -181),
        ).forEach { stored ->
            val address = DeliveryAddress.fromJson(stored)
            assertNull(address.latitude)
            assertNull(address.longitude)
        }
    }

    // Input: non-object contact entries mixed with one valid contact. Expected: bad rows are skipped, not crashed on.
    @Test
    fun malformedContactRowsAreSkipped() {
        val contacts = JSONArray()
            .put("wrong")
            .put(42)
            .put(JSONObject().put("id", "safe-1").put("name", "Trusted").put("details", "555"))
        val restored = UserProfile.fromJson(
            JSONObject().put("username", "olive_user").put("contacts", contacts)
        )
        assertEquals(1, restored.contacts.size)
        assertEquals("safe-1", restored.contacts.single().id)
    }

    // Input: city, province, and country combinations. Expected: general location has no empty separators.
    @Test
    fun generalLocationUsesOnlyAvailableParts() {
        assertEquals("Edmonton, AB, Canada", DeliveryAddress(city = "Edmonton", province = "AB").generalLocation)
        assertEquals("Edmonton, Canada", DeliveryAddress(city = "Edmonton").generalLocation)
        assertEquals("Canada", DeliveryAddress().generalLocation)
        assertEquals("", DeliveryAddress(country = "").generalLocation)
    }

    // Input: exactly 160 and 161 character city names. Expected: the published field boundary is enforced precisely.
    @Test
    fun locationLengthBoundaryIsExact() {
        assertTrue(DeliveryAddress(city = "x".repeat(160)).locationValid)
        assertFalse(DeliveryAddress(city = "x".repeat(161)).locationValid)
    }
}
