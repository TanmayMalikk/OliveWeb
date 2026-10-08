package com.olive.app.ui

import com.olive.app.data.AccountIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsivenessPolicyTest {
    @Test fun coverIsOnlyConstructedAfterAUserSessionIsConfigured() {
        assertFalse(shouldComposeCover(configured=false,usesCover=true))
        assertFalse(shouldComposeCover(configured=true,usesCover=false))
        assertTrue(shouldComposeCover(configured=true,usesCover=true))
    }

    @Test fun messagingDirectoryRegistrationIsDeduplicatedPerIdentity() {
        val identity=AccountIdentity("uid-1","Olive_User")
        assertEquals("uid-1:olive_user",messagingIdentityMarker(identity))
        assertTrue(shouldRegisterMessagingIdentity(null,identity))
        assertFalse(shouldRegisterMessagingIdentity("uid-1:olive_user",identity))
        assertTrue(shouldRegisterMessagingIdentity("uid-2:olive_user",identity))
    }

    @Test fun calculatorHistoryCanArriveAfterTheInputIsAlreadyInteractive() {
        val input=CalculatorInput()
        input.press("7")
        input.press("+")
        input.press("2")
        input.press("=")
        val current=input.history.first()

        val saved=CalculatorHistoryEntry("3+4","7")
        input.restoreHistory(listOf(saved,current))

        assertEquals(current,input.history.first())
        assertEquals(2,input.history.size)
        assertTrue(saved in input.history)
    }
}
