// Tests safe-contact profile storage and older profile defaults.
package com.olive.app.data

import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeContactTest {
    // Builds a complete sample profile for this test.
    private fun profile(contact: SafeContact) = UserProfile(
        username = "trusted_contact_test",
        fullName = "Test User",
        affectedNames = "",
        abuserName = "",
        dob = "2000-01-01",
        language = "English",
        situation = "",
        region = "Alberta",
        triggerHash = "a".repeat(64),
        contacts = listOf(contact)
    )

    // Inputs: the values shown below. Expected: safe to stay survives profile storage round trip.
    @Test
    fun safeToStaySurvivesProfileStorageRoundTrip() {
        val restored = UserProfile.fromJson(
            profile(
                SafeContact(
                    "friend",
                    "Trusted Friend",
                    "555-0100",
                    true,
                    "friend-uid"
                )
            ).toJson()
        )
        assertTrue(restored.contacts.single().safeToStay)
        assertEquals("friend-uid", restored.contacts.single().linkedUid)
    }

    // Inputs: the values shown below. Expected: contacts saved before safe to stay default to false.
    @Test
    fun contactsSavedBeforeSafeToStayDefaultToFalse() {
        val legacy = JSONObject(
            profile(SafeContact("friend", "Trusted Friend", "555-0100")).toJson().toString()
        )
        legacy.getJSONArray("contacts").getJSONObject(0).remove("safeToStay")
        legacy.getJSONArray("contacts").getJSONObject(0).remove("linkedUid")
        assertFalse(UserProfile.fromJson(legacy).contacts.single().safeToStay)
        assertEquals("", UserProfile.fromJson(legacy).contacts.single().linkedUid)
    }
}
