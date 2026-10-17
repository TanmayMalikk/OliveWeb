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
    @Test fun backupPinIsAllowedOnlyAfterPhoneLockIsRemoved() {
        val device=DeviceEnrollment("uid","trigger",entryMethod=SafeEntryMethod.DEVICE_LOCK,hasAppPin=true)
        assertFalse(device.allowsAppPin(true))
        assertTrue(device.allowsAppPin(false))
        assertFalse(device.copy(hasAppPin=false).allowsAppPin(false))
        assertFalse(device.copy(hasAppPin=false).allowsAppPin(true))
        val selectedPin=device.copy(entryMethod=SafeEntryMethod.APP_PIN)
        assertTrue(selectedPin.allowsAppPin(true))
        assertTrue(selectedPin.allowsAppPin(false))
    }
}
