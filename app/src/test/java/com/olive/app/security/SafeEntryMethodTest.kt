package com.olive.app.security

import org.junit.Assert.*
import org.junit.Test

class SafeEntryMethodTest {
    @Test fun recommendsSeparatePinWhenPhonePinIsKnown() {
        assertEquals(SafeEntryMethod.APP_PIN,SafeEntryMethod.recommended(true))
        assertEquals(SafeEntryMethod.DEVICE_LOCK,SafeEntryMethod.recommended(false))
    }
    @Test fun existingEnrollmentsKeepTheirPin() {
        assertEquals(SafeEntryMethod.APP_PIN,SafeEntryMethod.fromStored(""))
        assertEquals(SafeEntryMethod.APP_PIN,DeviceEnrollment("uid","trigger").entryMethod)
        assertEquals(SafeEntryMethod.DEVICE_LOCK,SafeEntryMethod.fromStored("DEVICE_LOCK"))
    }
}
