package com.olive.app.data

import com.olive.app.data.safety.retainAuthorizedDeviceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessDeviceKeysTest {
    @Test
    fun `revocation removes every device wrapper for only the revoked account`() {
        val keys = mapOf(
            "owner--phone" to "owner-phone-key",
            "owner--browser" to "owner-browser-key",
            "recipient--phone" to "recipient-phone-key",
            "recipient2--phone" to "recipient2-phone-key",
            "malformed" to "unscoped-key",
        )

        assertEquals(
            mapOf(
                "owner--phone" to "owner-phone-key",
                "owner--browser" to "owner-browser-key",
                "recipient2--phone" to "recipient2-phone-key",
            ),
            retainAuthorizedDeviceKeys(keys, setOf("owner", "recipient2")),
        )
    }

    @Test
    fun `empty authorization removes all device wrappers`() {
        assertTrue(retainAuthorizedDeviceKeys(mapOf("owner--phone" to "key"), emptySet()).isEmpty())
    }

    @Test
    fun `repeated maximum recipient sets retain exact device subclasses`() {
        repeat(100) { iteration ->
            val users = (0 until 25).map { "user$it" }
            val keys = buildMap {
                users.forEach { uid ->
                    put("$uid--android-$iteration", "android-key")
                    put("$uid--browser-$iteration", "browser-key")
                    put("$uid--tablet-$iteration", "tablet-key")
                }
            }
            val allowed = users.filterIndexed { index, _ -> index % 2 == iteration % 2 }.toSet()
            val retained = retainAuthorizedDeviceKeys(keys, allowed)

            assertEquals(allowed.size * 3, retained.size)
            assertTrue(retained.keys.all { key -> allowed.any { uid -> key.startsWith("$uid--") } })
        }
    }
}
