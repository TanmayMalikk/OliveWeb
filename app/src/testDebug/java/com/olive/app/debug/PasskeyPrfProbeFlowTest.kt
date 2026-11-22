package com.olive.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyPrfProbeFlowTest {
    @Test
    fun secondUserGestureIsReservedForPrfAssertionAfterRegistration() {
        assertEquals(
            PasskeyPrfProbeAction.CREATE_PASSKEY,
            PasskeyPrfProbeFlow.nextAction(hasPendingAssertion = false),
        )
        assertEquals(
            PasskeyPrfProbeAction.REQUEST_PRF_ASSERTION,
            PasskeyPrfProbeFlow.nextAction(hasPendingAssertion = true),
        )
    }
}
