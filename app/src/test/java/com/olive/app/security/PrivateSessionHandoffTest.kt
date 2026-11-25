package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateSessionHandoffTest {
    @Test
    fun `every trusted system handoff independently suppresses quick exit`() {
        repeat(32) { mask ->
            val flags = BooleanArray(5) { index -> mask and (1 shl index) != 0 }
            assertEquals(
                "mask=$mask",
                mask != 0,
                trustedSystemHandoffActive(
                    deviceCredential = flags[0],
                    runtimePermission = flags[1],
                    externalFilePicker = flags[2],
                    callPermission = flags[3],
                    safeSystemActivity = flags[4],
                ),
            )
        }
    }

    @Test
    fun `repeated biometric transitions remain protected`() {
        repeat(100) {
            assertEquals(
                true,
                trustedSystemHandoffActive(
                    deviceCredential = true,
                    runtimePermission = false,
                    externalFilePicker = false,
                    callPermission = false,
                    safeSystemActivity = false,
                ),
            )
        }
    }
}
