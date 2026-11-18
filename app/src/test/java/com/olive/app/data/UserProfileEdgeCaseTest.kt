// Tests profile defaults, invalid stored values, addresses, and contact compatibility for users.
package com.olive.app.data

import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserProfileEdgeCaseTest {
    // Inputs: the values shown below. Expected: unknown stored role and cover use safe personal account defaults.
    @Test
    fun unknownStoredRoleAndCoverUseSafePersonalAccountDefaults() {
        assertEquals(AccountRole.USER, AccountRole.fromStored("SUPER_ADMIN"))
        assertEquals(AccountRole.USER, AccountRole.fromStored(""))
        assertEquals(CoverType.FITNESS, CoverType.fromStored("VIDEO"))
        assertEquals(CoverType.FITNESS, CoverType.fromStored("REMOVED_COVER"))
    }

    // Inputs: the values shown below. Expected: incomplete and overlong addresses are not accepted.
    @Test
    fun incompleteAndOverlongAddressesAreNotAccepted() {
        assertFalse(DeliveryAddress().locationValid)
        assertFalse(DeliveryAddress(city = "Edmonton").valid)
        assertTrue(DeliveryAddress(city = "Edmonton").locationValid)

        val complete = DeliveryAddress(
            line1 = "1 Olive Street",
            city = "Edmonton",
            province = "Alberta",
            postalCode = "T5J 0N3",
            country = "Canada",
        )
        assertTrue(complete.valid)
        assertEquals("1 Olive Street, Edmonton", complete.label)

        val overlong = complete.copy(line1 = "x".repeat(161))
        assertFalse(overlong.valid)
        assertEquals("", overlong.label)
    }

    // Reads missing and wrongly typed coordinates; invalid values should safely become null.
    @Test
    fun addressJsonHandlesMissingAndIncorrectCoordinateTypes() {
        assertEquals(DeliveryAddress(), DeliveryAddress.fromJson(null))

        val restored = DeliveryAddress.fromJson(
            JSONObject()
                .put("city", "Edmonton")
                .put("latitude", "not a number")
                .put("longitude", JSONObject.NULL)
        )
        assertEquals("Edmonton", restored.city)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
    }

    // Inputs: the values shown below. Expected: older profiles without contact arrays or enums still load.
    @Test
    fun olderProfilesWithoutContactArraysOrEnumsStillLoad() {
        val restored = UserProfile.fromJson(
            JSONObject()
                .put("username", "old_user")
                .put("contacts", "wrong type")
                .put("coverType", "REMOVED_COVER")
                .put("role", "REMOVED_ROLE")
        )

        assertEquals("old_user", restored.username)
        assertTrue(restored.contacts.isEmpty())
        assertEquals(CoverType.FITNESS, restored.coverType)
        assertEquals(AccountRole.USER, restored.role)
    }
    @Test
    fun relationshipsRoundTripAndOlderContactsDefaultToEmpty() {
        val oldContact = JSONObject().put("id", "friend").put("name", "Friend")
            .put("details", "Any contact instructions").put("safeToStay", true).put("linkedUid", "friend-uid")
        val old = UserProfile.fromJson(JSONObject().put("username", "owner")
            .put("contacts", org.json.JSONArray().put(oldContact)))
        assertEquals("", old.contacts.single().relationship)
        val updated = old.copy(contacts = old.contacts.map { it.copy(relationship = "Sibling") })
        assertEquals(updated, UserProfile.fromJson(updated.toJson()))
        assertTrue(updated.contacts.single().safeToStay)
        assertEquals("friend-uid", updated.contacts.single().linkedUid)
    }
}
