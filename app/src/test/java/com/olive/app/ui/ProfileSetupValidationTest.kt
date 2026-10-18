package com.olive.app.ui

import com.olive.app.data.DeliveryAddress
import org.junit.Assert.*
import org.junit.Test

class ProfileSetupValidationTest {
    private fun validation(safeContact:Boolean=false,trigger:String="moon garden",name:String="",details:String="")=
        ProfileSetupValidation(safeContact,"Test Person","2000-01-02",DeliveryAddress(city="Edmonton"),trigger,name,details)

    @Test fun optionalContactsMustBeCompleteBeforeFinishingUserSetup() {
        assertTrue(validation().isPageValid(2))
        assertFalse(validation(name="Trusted friend").isPageValid(2))
        assertFalse(validation(details="friend@example.ca").isPageValid(2))
        assertTrue(validation(name="Trusted friend",details="friend@example.ca").isPageValid(2))
    }

    @Test fun safeContactProgressDoesNotRequirePrivateEntryCredentials() {
        val contact=validation(safeContact=true,trigger="")
        assertTrue(contact.requiredFields.all { it })
        assertEquals(3,contact.requiredFields.size)
        assertEquals(4,validation().requiredFields.size)
        assertEquals(listOf(true,true),contact.pageCompletion)
        assertFalse(validation(trigger="").requiredFields.all { it })
    }

    @Test fun incompleteIdentityAndLocationCannotAdvance() {
        val incomplete=ProfileSetupValidation(false,"","invalid",DeliveryAddress(),"moon garden","","")
        assertFalse(incomplete.isPageValid(0))
        assertFalse(incomplete.isPageValid(1))
    }
}
