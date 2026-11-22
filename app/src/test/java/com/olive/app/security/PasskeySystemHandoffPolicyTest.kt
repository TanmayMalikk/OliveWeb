package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeySystemHandoffPolicyTest {
    @Test
    fun `every trusted system handoff independently suppresses quick exit`() {
        repeat(64) { mask ->
            val flags = BooleanArray(6) { index -> mask and (1 shl index) != 0 }
            assertEquals(
                "mask=$mask",
                mask != 0,
                trustedSystemHandoffActive(
                    deviceCredential = flags[0],
                    passkeyCredential = flags[1],
                    runtimePermission = flags[2],
                    externalFilePicker = flags[3],
                    callPermission = flags[4],
                    safeSystemActivity = flags[5],
                ),
            )
        }
    }

    @Test
    fun `repeated passkey transitions remain protected`() {
        repeat(100) {
            assertEquals(
                true,
                trustedSystemHandoffActive(
                    deviceCredential = false,
                    passkeyCredential = true,
                    runtimePermission = false,
                    externalFilePicker = false,
                    callPermission = false,
                    safeSystemActivity = false,
                ),
            )
        }
    }
}
