package com.olive.app.ui

import com.olive.app.data.DeliveryAddress
import org.junit.Assert.*
import org.junit.Test

class ProfileSetupValidationTest {
    private fun validation(safeContact:Boolean=false,trigger:String="moon garden",name:String="",details:String="")=
        ProfileSetupValidation(safeContact,"Test Person","2000-01-02","English",DeliveryAddress(city="Edmonton"),trigger,name,details)

    @Test fun optionalContactsMustBeCompleteBeforeFinishingUserSetup() {
        assertTrue(validation().isPageValid("Safe entry & cover"))
        assertFalse(validation(name="Trusted friend").isPageValid("Safe entry & cover"))
        assertFalse(validation(details="friend@example.ca").isPageValid("Safe entry & cover"))
        assertTrue(validation(name="Trusted friend",details="friend@example.ca").isPageValid("Safe entry & cover"))
    }

    @Test fun safeContactProgressDoesNotRequirePrivateEntryCredentials() {
        val contact=validation(safeContact=true,trigger="")
        assertTrue(contact.requiredFields.all { it })
        assertEquals(listOf(true,true),contact.pageCompletion)
        assertFalse(validation(trigger="").requiredFields.all { it })
    }

    @Test fun incompleteIdentityAndLocationCannotAdvance() {
        val incomplete=ProfileSetupValidation(false,"","invalid","English",DeliveryAddress(),"moon garden","","")
        assertFalse(incomplete.isPageValid("Personal details"))
        assertFalse(incomplete.isPageValid("Location"))
    }
}
