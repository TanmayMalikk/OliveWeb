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

    // Inputs: boundary, middle, leading-zero, and seeded sample PINs without a backup PIN. Expected: none may start calculator entry.
    @Test
    fun deviceLockWithoutBackupPinRejectsRepresentativeCalculatorPins() {
        val enrollment = DeviceEnrollment(
            uid = "uid",
            triggerHash = "trigger",
            entryMethod = SafeEntryMethod.DEVICE_LOCK,
            hasAppPin = false,
        )
        val representativePins = listOf(
            "0000", "0001", "0123", "4999", "5000", "9998", "9999",
            "00000", "50000", "99999", "000000", "500000", "999999",
        )
        for (input in representativePins) {
            assertFalse("Unexpected calculator entry for input=$input", enrollment.allowsCalculatorPinAttempt(input))
        }
        val seed = 0x0BADC0DE
        val random = kotlin.random.Random(seed)
        repeat(250) {
            val length = random.nextInt(4, 7)
            val input = buildString { repeat(length) { append(random.nextInt(10)) } }
            assertFalse(
                "Unexpected calculator entry for seed=$seed input=$input",
                enrollment.allowsCalculatorPinAttempt(input),
            )
        }
    }

    // Inputs: valid, malformed, staff, and backup-PIN enrollment states. Expected: only a user with a backup PIN may attempt verification.
    @Test
    fun calculatorPinAttemptRequiresUserRoleValidShapeAndSavedPin() {
        val ready = DeviceEnrollment(
            uid = "uid",
            triggerHash = "trigger",
            entryMethod = SafeEntryMethod.DEVICE_LOCK,
            hasAppPin = true,
        )
        assertTrue(ready.allowsCalculatorPinAttempt("1234"))
        assertTrue(ready.allowsCalculatorPinAttempt("000000"))
        for (input in listOf("", "123", "1234567", "12a4", "１２３４", " 1234 ")) {
            assertFalse("Malformed input was accepted: $input", ready.allowsCalculatorPinAttempt(input))
        }
        assertFalse(ready.copy(hasAppPin = false).allowsCalculatorPinAttempt("1234"))
        assertFalse(ready.copy(role = com.olive.app.data.AccountRole.STAFF).allowsCalculatorPinAttempt("1234"))
    }
}
