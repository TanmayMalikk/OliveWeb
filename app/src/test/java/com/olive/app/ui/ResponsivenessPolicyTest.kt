// Tests startup, TalkBack order, chat registration, and calculator responsiveness.
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

class ResponsivenessPolicyTest {
    // Inputs: the values shown below. Expected: talk back traversal policy keeps content before floating actions.
    @Test
    fun talkBackTraversalPolicyKeepsContentBeforeFloatingActions() {
        val expected = listOf(
            AccessibilityTraversalOrder.ScreenContent,
            AccessibilityTraversalOrder.OverlayContent,
            AccessibilityTraversalOrder.PersistentActions,
        )

        assertEquals(expected, AccessibilityTraversalOrder.entries.sortedBy { it.index })
        assertEquals(expected.size, expected.map { it.index }.distinct().size)
    }

    // Inputs: the values shown below. Expected: cover is constructed for the default and configured user routes.
    @Test
    fun coverIsConstructedForTheDefaultAndConfiguredUserRoutes() {
        assertFalse(shouldComposeCover(usesCover = false))
        assertTrue(shouldComposeCover(usesCover = true))
    }

    // Inputs: the values shown below. Expected: messaging directory registration is deduplicated per identity.
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

    // Inputs: the values shown below. Expected: calculator history can arrive after the input is already interactive.
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
