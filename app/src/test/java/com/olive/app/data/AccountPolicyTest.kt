package com.olive.app.data

import com.olive.app.security.InputPolicy
import org.junit.Assert.*
import org.junit.Test

class AccountPolicyTest {
    private fun profile(role:AccountRole,trigger:String="hash",email:String="")=UserProfile(
        username="olive_test",fullName="Olive Tester",affectedNames="",abuserName="",dob="2000-01-02",
        language="English",situation="",region="Edmonton, Canada",triggerHash=trigger,
        address=DeliveryAddress(city="Edmonton"),role=role,email=email,
    )

    @Test fun everyRoleUsesTheExpectedSignInIdentifier() {
        assertEquals("olive_test@users.olive.invalid",AccountPolicy.signInEmail("Olive_Test",AccountRole.USER))
        assertEquals("olive_test@safe.olive.invalid",AccountPolicy.signInEmail("Olive_Test",AccountRole.SAFE_CONTACT))
        assertEquals("olive_test@staff.olive.invalid",AccountPolicy.signInEmail("Olive_Test",AccountRole.STAFF))
        assertEquals("olive_test@admin.olive.invalid",AccountPolicy.signInEmail("Olive_Test",AccountRole.ADMIN))
        assertEquals("staff@example.ca",AccountPolicy.signInEmail("Staff@Example.ca",AccountRole.STAFF))
        assertEquals("olive_test@users.olive.invalid",AccountPolicy.legacyUserEmail("Olive_Test"))
    }

    @Test fun userSetupRequiresProfileThenPrivateTriggerThenDevicePin() {
        assertEquals(AccountSetupStep.PersonalProfile,AccountPolicy.nextSetup(null,AccountRole.USER))
        assertEquals(AccountSetupStep.PersonalProfile,AccountPolicy.nextSetup(profile(AccountRole.USER).copy(fullName=""),AccountRole.USER))
        assertEquals(AccountSetupStep.PrivateTrigger,AccountPolicy.nextSetup(profile(AccountRole.USER,trigger=""),AccountRole.USER))
        assertEquals(AccountSetupStep.DevicePin,AccountPolicy.nextSetup(profile(AccountRole.USER),AccountRole.USER))
    }

    @Test fun safeContactsNeverNeedATriggerOrPin() {
        assertEquals(AccountSetupStep.PersonalProfile,AccountPolicy.nextSetup(null,AccountRole.SAFE_CONTACT))
        assertEquals(AccountSetupStep.Ready,AccountPolicy.nextSetup(profile(AccountRole.SAFE_CONTACT,trigger=""),AccountRole.SAFE_CONTACT))
    }

    @Test fun staffAndAdminRequireARealEmailButNoPin() {
        for(role in listOf(AccountRole.STAFF,AccountRole.ADMIN)) {
            assertEquals(AccountSetupStep.OrganizationProfile,AccountPolicy.nextSetup(null,role))
            assertEquals(AccountSetupStep.OrganizationProfile,AccountPolicy.nextSetup(profile(role,email="olive_test@${role.name.lowercase()}.olive.invalid"),role))
            assertEquals(AccountSetupStep.Ready,AccountPolicy.nextSetup(profile(role,email="person@example.ca"),role))
        }
    }

    @Test fun profileJsonKeepsOrganizationFieldsAndReadsOlderProfiles() {
        val original=profile(AccountRole.ADMIN,email="admin@example.ca").copy(phone="780-555-0100")
        assertEquals(original,UserProfile.fromJson(original.toJson()))
        val older=original.toJson().apply { remove("email");remove("phone") }
        val restored=UserProfile.fromJson(older)
        assertEquals("",restored.email)
        assertEquals("",restored.phone)
        assertTrue(InputPolicy.validDob(restored.dob))
    }
}
