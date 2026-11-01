// Tests PIN and device-lock entry choices for safe-app users.
package com.olive.app.security

import org.junit.Assert.*
import org.junit.Test

class SafeEntryMethodTest {
    // Inputs: the values shown below. Expected: recommends separate pin when phone pin is known.
    @Test
    fun recommendsSeparatePinWhenPhonePinIsKnown() {
        assertEquals(SafeEntryMethod.APP_PIN, SafeEntryMethod.recommended(true))
        assertEquals(SafeEntryMethod.DEVICE_LOCK, SafeEntryMethod.recommended(false))
    }

    // Inputs: the values shown below. Expected: existing enrollments keep their pin.
    @Test
    fun existingEnrollmentsKeepTheirPin() {
        assertEquals(SafeEntryMethod.APP_PIN, SafeEntryMethod.fromStored(""))
        assertEquals(SafeEntryMethod.APP_PIN, DeviceEnrollment("uid", "trigger").entryMethod)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, SafeEntryMethod.fromStored("DEVICE_LOCK"))
    }

    // Inputs: the values shown below. Expected: backup pin is always an optional second entry route.
    @Test
    fun backupPinIsAlwaysAnOptionalSecondEntryRoute() {
        val device = DeviceEnrollment(
            "uid",
            "trigger",
            entryMethod = SafeEntryMethod.DEVICE_LOCK,
            hasAppPin = true
        )
        assertTrue(device.allowsAppPin())
        assertFalse(device.copy(hasAppPin = false).allowsAppPin())
        val selectedPin = device.copy(entryMethod = SafeEntryMethod.APP_PIN)
        assertTrue(selectedPin.allowsAppPin())
    }
}
