package com.olive.app.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeContactTest {
    private fun profile(contact:SafeContact)=UserProfile(
        username="trusted_contact_test",fullName="Test User",affectedNames="",abuserName="",
        dob="2000-01-01",language="English",situation="",region="Alberta",
        triggerHash="a".repeat(64),contacts=listOf(contact))

    @Test fun safeToStaySurvivesProfileStorageRoundTrip() {
        val restored=UserProfile.fromJson(profile(SafeContact("friend","Trusted Friend","555-0100",true)).toJson())
        assertTrue(restored.contacts.single().safeToStay)
    }

    @Test fun contactsSavedBeforeSafeToStayDefaultToFalse() {
        val legacy=JSONObject(profile(SafeContact("friend","Trusted Friend","555-0100")).toJson().toString())
        legacy.getJSONArray("contacts").getJSONObject(0).remove("safeToStay")
        assertFalse(UserProfile.fromJson(legacy).contacts.single().safeToStay)
    }
}
