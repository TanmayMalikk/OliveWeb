// Tests required profile fields for users and safe contacts.
package com.olive.app.ui

import com.olive.app.ui.users.shared.onboarding.ProfileSetupValidation

import com.olive.app.data.DeliveryAddress
import org.junit.Assert.*
import org.junit.Test

class ProfileSetupValidationTest {
    private fun validation(
        safeContact: Boolean = false,
        trigger: String = "moon garden",
        name: String = "",
        details: String = ""
    ) =
        ProfileSetupValidation(
            safeContact,
            "Test Person",
            "2000-01-02",
            DeliveryAddress(city = "Edmonton"),
            trigger,
            name,
            details
        )

    // Inputs: the values shown below. Expected: optional contacts must be complete before finishing user setup.
    @Test
    fun optionalContactsMustBeCompleteBeforeFinishingUserSetup() {
        assertTrue(validation().isPageValid(2))
        assertFalse(validation(name = "Trusted friend").isPageValid(2))
        assertFalse(validation(details = "friend@example.ca").isPageValid(2))
        assertTrue(
            validation(
                name = "Trusted friend",
                details = "friend@example.ca"
            ).isPageValid(2)
        )
    }

    // Inputs: the values shown below. Expected: safe contact progress does not require private entry credentials.
    @Test
    fun safeContactProgressDoesNotRequirePrivateEntryCredentials() {
        val contact = validation(safeContact = true, trigger = "")
        assertTrue(contact.requiredFields.all { it })
        assertEquals(3, contact.requiredFields.size)
        assertEquals(4, validation().requiredFields.size)
        assertEquals(listOf(true, true), contact.pageCompletion)
        assertFalse(validation(trigger = "").requiredFields.all { it })
    }

    // Inputs: the values shown below. Expected: incomplete identity and location cannot advance.
    @Test
    fun incompleteIdentityAndLocationCannotAdvance() {
        val incomplete =
            ProfileSetupValidation(false, "", "invalid", DeliveryAddress(), "moon garden", "", "")
        assertFalse(incomplete.isPageValid(0))
        assertFalse(incomplete.isPageValid(1))
    }

    // Inputs: the values shown below. Expected: whitespace overlong fields and impossible dates cannot complete setup.
    @Test
    fun whitespaceOverlongFieldsAndImpossibleDatesCannotCompleteSetup() {
        val whitespace = ProfileSetupValidation(
            false,
            "   ",
            "2000-01-02",
            DeliveryAddress(city = "Edmonton"),
            "moon garden",
            "",
            "",
        )
        assertFalse(whitespace.isPageValid(0))

        val impossibleDate = ProfileSetupValidation(
            false,
            "Test Person",
            "2024-02-30",
            DeliveryAddress(city = "Edmonton"),
            "moon garden",
            "",
            "",
        )
        assertFalse(impossibleDate.isPageValid(0))

        val overlongCity = ProfileSetupValidation(
            false,
            "Test Person",
            "2000-01-02",
            DeliveryAddress(city = "x".repeat(161)),
            "moon garden",
            "",
            "",
        )
        assertFalse(overlongCity.isPageValid(1))
    }
}
