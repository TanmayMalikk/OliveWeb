// Tests role sign-in IDs and required setup steps for users and safe contacts.
package com.olive.app.data

import com.olive.app.data.account.AccountPolicy
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AccountSetupStep
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import org.junit.Assert.*
import org.junit.Test

class AccountPolicyTest {
    // Builds a complete sample profile for this test.
    private fun profile(role: AccountRole, trigger: String = "hash", email: String = "") =
        UserProfile(
            username = "olive_test",
            fullName = "Olive Tester",
            affectedNames = "",
            abuserName = "",
            dob = "2000-01-02",
            language = "English",
            situation = "",
            region = "Edmonton, Canada",
            triggerHash = trigger,
            address = DeliveryAddress(city = "Edmonton"),
            role = role,
            email = email,
        )

    // Inputs: the values shown below. Expected: every role uses the expected sign in identifier.
    @Test
    fun everyRoleUsesTheExpectedSignInIdentifier() {
        assertEquals(
            "olive_test@users.olive.invalid",
            AccountPolicy.signInEmail("Olive_Test", AccountRole.USER)
        )
        assertEquals(
            "olive_test@safe.olive.invalid",
            AccountPolicy.signInEmail("Olive_Test", AccountRole.SAFE_CONTACT)
        )
        assertEquals(
            "olive_test@yourolivebranch.ca",
            AccountPolicy.signInEmail("Olive_Test", AccountRole.STAFF)
        )
        assertEquals(
            "olive_test@yourolivebranch.ca",
            AccountPolicy.signInEmail("Olive_Test", AccountRole.ADMIN)
        )
        assertEquals(
            "staff@example.ca",
            AccountPolicy.signInEmail("Staff@Example.ca", AccountRole.STAFF)
        )
        assertEquals("olive_test@users.olive.invalid", AccountPolicy.legacyUserEmail("Olive_Test"))
    }

    // Inputs: the values shown below. Expected: user setup requires profile then private trigger then device pin.
    @Test
    fun userSetupRequiresProfileThenPrivateTriggerThenDevicePin() {
        assertEquals(
            AccountSetupStep.PersonalProfile,
            AccountPolicy.nextSetup(null, AccountRole.USER)
        )
        assertEquals(
            AccountSetupStep.PersonalProfile,
            AccountPolicy.nextSetup(profile(AccountRole.USER).copy(fullName = ""), AccountRole.USER)
        )
        assertEquals(
            AccountSetupStep.PrivateTrigger,
            AccountPolicy.nextSetup(profile(AccountRole.USER, trigger = ""), AccountRole.USER)
        )
        assertEquals(
            AccountSetupStep.DevicePin,
            AccountPolicy.nextSetup(profile(AccountRole.USER), AccountRole.USER)
        )
    }

    // Inputs: the values shown below. Expected: safe contacts never need atrigger or pin.
    @Test
    fun safeContactsNeverNeedATriggerOrPin() {
        assertEquals(
            AccountSetupStep.PersonalProfile,
            AccountPolicy.nextSetup(null, AccountRole.SAFE_CONTACT)
        )
        assertEquals(
            AccountSetupStep.Ready,
            AccountPolicy.nextSetup(
                profile(AccountRole.SAFE_CONTACT, trigger = ""),
                AccountRole.SAFE_CONTACT
            )
        )
    }

    // Inputs: the values shown below. Expected: staff and admin require areal email but no pin.
    @Test
    fun staffAndAdminRequireARealEmailButNoPin() {
        for (role in listOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            assertEquals(AccountSetupStep.OrganizationProfile, AccountPolicy.nextSetup(null, role))
            assertEquals(
                AccountSetupStep.OrganizationProfile,
                AccountPolicy.nextSetup(
                    profile(
                        role,
                        email = "olive_test@${role.name.lowercase()}.olive.invalid"
                    ), role
                )
            )
            assertEquals(
                AccountSetupStep.Ready,
                AccountPolicy.nextSetup(profile(role, email = "person@example.ca"), role)
            )
        }
    }

    // Inputs: the values shown below. Expected: profile json keeps organization fields and reads older profiles.
    @Test
    fun profileJsonKeepsOrganizationFieldsAndReadsOlderProfiles() {
        val original =
            profile(AccountRole.ADMIN, email = "admin@example.ca").copy(phone = "780-555-0100")
        assertEquals(original, UserProfile.fromJson(original.toJson()))
        val older = original.toJson().apply {
            remove("email")
            remove("phone")
        }
        val restored = UserProfile.fromJson(older)
        assertEquals("", restored.email)
        assertEquals("", restored.phone)
        assertTrue(InputPolicy.validDob(restored.dob))
    }

    // Inputs: the values shown below. Expected: sign in identifiers trim case and keep role namespaces separate.
    @Test
    fun signInIdentifiersTrimCaseAndKeepRoleNamespacesSeparate() {
        assertEquals(
            "staff@example.ca",
            AccountPolicy.signInEmail("  Staff@Example.CA  ", AccountRole.STAFF),
        )
        assertEquals(
            "same_name@users.olive.invalid",
            AccountPolicy.signInEmail(" Same_Name ", AccountRole.USER),
        )
        assertEquals(
            "same_name@safe.olive.invalid",
            AccountPolicy.signInEmail(" Same_Name ", AccountRole.SAFE_CONTACT),
        )
    }

    // Inputs: the values shown below. Expected: invalid dates locations and placeholder organization emails require setup.
    @Test
    fun invalidDatesLocationsAndPlaceholderOrganizationEmailsRequireSetup() {
        assertTrue(AccountPolicy.personalProfileNeedsSetup(profile(AccountRole.USER).copy(dob = "not-a-date")))
        assertTrue(
            AccountPolicy.personalProfileNeedsSetup(
            profile(AccountRole.USER).copy(address = DeliveryAddress(city = "x".repeat(161)))
        ))
        assertTrue(
            AccountPolicy.organizationProfileNeedsSetup(
            profile(AccountRole.ADMIN, email = "ADMIN@ADMIN.OLIVE.INVALID")
        ))
        assertTrue(
            AccountPolicy.organizationProfileNeedsSetup(
            profile(AccountRole.STAFF, email = "")
        ))
    }
}
