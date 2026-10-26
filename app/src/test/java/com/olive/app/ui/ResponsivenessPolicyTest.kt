// Tests the responsiveness policy test behavior.
package com.olive.app.ui

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.messagingIdentityMarker
import com.olive.app.ui.users.shared.navigation.shouldRegisterMessagingIdentity
import com.olive.app.ui.users.shared.navigation.shouldComposeCover

import com.olive.app.ui.covers.calculator.CalculatorHistoryEntry
import com.olive.app.ui.covers.calculator.CalculatorInput

import com.olive.app.data.AccountIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Groups the data and actions for responsiveness policy test.
class ResponsivenessPolicyTest {
    @Test
    fun coverIsConstructedForTheDefaultAndConfiguredUserRoutes() {
        assertFalse(shouldComposeCover(usesCover = false))
        assertTrue(shouldComposeCover(usesCover = true))
    }

    @Test
    fun messagingDirectoryRegistrationIsDeduplicatedPerIdentity() {
        val identity = AccountIdentity("uid-1", "Olive_User")
        val publicKey = byteArrayOf(1, 2, 3, 4)
        val marker = messagingIdentityMarker(identity, publicKey)
        assertTrue(marker.startsWith("secure-v2:uid-1:olive_user:"))
        assertTrue(shouldRegisterMessagingIdentity(null, identity, publicKey))
        assertFalse(shouldRegisterMessagingIdentity(marker, identity, publicKey))
        assertTrue(shouldRegisterMessagingIdentity(marker, identity, byteArrayOf(4, 3, 2, 1)))
        assertTrue(
            shouldRegisterMessagingIdentity(
                "secure-v1:uid-1:olive_user",
                identity,
                publicKey
            )
        )
    }

    @Test
    fun calculatorHistoryCanArriveAfterTheInputIsAlreadyInteractive() {
        val input = CalculatorInput()
        input.press("7")
        input.press("+")
        input.press("2")
        input.press("=")
        val current = input.history.first()

        val saved = CalculatorHistoryEntry("3+4", "7")
        input.restoreHistory(listOf(saved, current))

        assertEquals(current, input.history.first())
        assertEquals(2, input.history.size)
        assertTrue(saved in input.history)
    }
}
