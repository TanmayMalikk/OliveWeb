package com.olive.app.ui

import com.olive.app.ui.shared.pinSafeBackgroundAlpha
import org.junit.Assert.assertEquals
import org.junit.Test

class PinSafeOliveTransitionTest {
    @Test
    fun backgroundRevealsSafeAreaOnlyWhileOliveFalls() {
        assertEquals(1f, pinSafeBackgroundAlpha(0f), 0.001f)
        assertEquals(1f, pinSafeBackgroundAlpha(0.52f), 0.001f)
        assertEquals(0.5f, pinSafeBackgroundAlpha(0.70f), 0.001f)
        assertEquals(0f, pinSafeBackgroundAlpha(0.88f), 0.001f)
        assertEquals(0f, pinSafeBackgroundAlpha(1f), 0.001f)
    }
}
