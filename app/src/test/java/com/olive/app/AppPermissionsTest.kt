// Tests the only automatic permission request after account setup.
package com.olive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {
    @Test
    fun promptsOnlyWhenEnabledUnansweredAndNotGranted() {
        assertTrue(shouldRequestNotifications(enabled = true, asked = false, granted = false))
        assertFalse(shouldRequestNotifications(enabled = false, asked = false, granted = false))
        assertFalse(shouldRequestNotifications(enabled = true, asked = true, granted = false))
        assertFalse(shouldRequestNotifications(enabled = true, asked = false, granted = true))
    }
    @Test
    fun settingsFallbackRequiresAnEarlierRequestAndNoRemainingSystemPrompt() {
        assertFalse(shouldOpenLocationSettings(false, false, false))
        assertFalse(shouldOpenLocationSettings(true, true, false))
        assertFalse(shouldOpenLocationSettings(true, false, true))
        assertTrue(shouldOpenLocationSettings(true, false, false))
    }
}
