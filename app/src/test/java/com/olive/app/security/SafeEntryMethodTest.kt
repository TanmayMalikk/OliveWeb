// Tests the safe entry method test behavior.
package com.olive.app.security

import com.olive.app.ui.shared.*

import org.junit.Assert.*
import org.junit.Test

// Groups the data and actions for safe entry method test.
class SafeEntryMethodTest {
    @Test
    fun recommendsSeparatePinWhenPhonePinIsKnown() {
        assertEquals(SafeEntryMethod.APP_PIN, SafeEntryMethod.recommended(true))
        assertEquals(SafeEntryMethod.DEVICE_LOCK, SafeEntryMethod.recommended(false))
    }

    @Test
    fun existingEnrollmentsKeepTheirPin() {
        assertEquals(SafeEntryMethod.APP_PIN, SafeEntryMethod.fromStored(""))
        assertEquals(SafeEntryMethod.APP_PIN, DeviceEnrollment("uid", "trigger").entryMethod)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, SafeEntryMethod.fromStored("DEVICE_LOCK"))
    }

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
