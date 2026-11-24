// Tests the olive animation between user PIN entry and the safe app.
package com.olive.app.ui

import com.olive.app.ui.session.SessionState
import com.olive.app.ui.shared.pinSafeBackgroundAlpha
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.shouldShowPinSafeAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSafeOliveTransitionTest {
    // Inputs: the values shown below. Expected: background reveals safe area only while olive falls.
    @Test
    fun backgroundRevealsSafeAreaOnlyWhileOliveFalls() {
        assertEquals(1f, pinSafeBackgroundAlpha(0f), 0.001f)
        assertEquals(1f, pinSafeBackgroundAlpha(0.52f), 0.001f)
        assertEquals(0.5f, pinSafeBackgroundAlpha(0.70f), 0.001f)
        assertEquals(0f, pinSafeBackgroundAlpha(0.88f), 0.001f)
        assertEquals(0f, pinSafeBackgroundAlpha(1f), 0.001f)
    }

    @Test
    fun pinSafeAnimationShowsSynchronouslyOnUnlockAndHidesAfterCompletionOrExit() {
        assertFalse(shouldShowPinSafeAnimation(SessionScreen.Pin, unlockAnimationVersion = 0, completedUnlockAnimation = 0))
        assertTrue(shouldShowPinSafeAnimation(SessionScreen.Safe, unlockAnimationVersion = 1, completedUnlockAnimation = 0))
        assertFalse(shouldShowPinSafeAnimation(SessionScreen.Safe, unlockAnimationVersion = 1, completedUnlockAnimation = 1))
        assertFalse(shouldShowPinSafeAnimation(SessionScreen.Cover, unlockAnimationVersion = 2, completedUnlockAnimation = 1))
    }

    @Test
    fun enterSafeWithAnimationUpdatesScreenPageAndUnlockVersionAtomically() {
        val state = SessionState { true }
        state.screen = SessionScreen.Pin
        state.page = SafePage.Settings

        state.enterSafeWithAnimation()

        val snapshot = state.navigationState.value
        assertEquals(SessionScreen.Safe, snapshot.screen)
        assertEquals(SafePage.Home, snapshot.page)
        assertEquals(1, snapshot.unlockAnimationVersion)
    }

    @Test
    fun enterSafeWithAnimationFallsBackToCoverWithoutAnimationWhenSafeEntryIsDisallowed() {
        val state = SessionState { false }
        state.screen = SessionScreen.Pin

        state.enterSafeWithAnimation()

        val snapshot = state.navigationState.value
        assertEquals(SessionScreen.Cover, snapshot.screen)
        assertEquals(SafePage.Home, snapshot.page)
        assertEquals(0, snapshot.unlockAnimationVersion)
    }
}
