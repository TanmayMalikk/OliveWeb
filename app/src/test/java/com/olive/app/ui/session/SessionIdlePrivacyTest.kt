package com.olive.app.ui.session

import android.os.Looper
import com.olive.app.data.account.AccountRole
import com.olive.app.security.IdlePolicy
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionIdlePrivacyTest {
    @Test
    fun interactionResetsTheCountdownAndAnIncomingCallPausesItUntilTheOverlayCloses() {
        SessionRuntimeFixture(dispatcher = Dispatchers.Main.immediate).use { fixture ->
            val runtime = fixture.runtime
            val state = runtime.state
            `when`(fixture.accounts.authenticatedUid()).thenReturn(null)
            runtime.privacy.setIdleTimeoutSeconds(60)
            runtime.privacy.startIdleTimer()
            advance(46)
            assertEquals(14, state.idleCountdownSeconds)
            runtime.privacy.noteInteraction()
            assertNull(state.idleCountdownSeconds)
            advance(46)
            assertEquals(14, state.idleCountdownSeconds)
            runtime.privacy.pauseIdleTimerForCall()
            advance(90)
            assertEquals(SessionScreen.Safe, state.screen)
            assertEquals(SafePage.Messages, state.page)
            assertNull(state.idleCountdownSeconds)

            runtime.privacy.resumeIdleTimerAfterCall()
            advance(59)
            assertEquals(1, state.idleCountdownSeconds)
            advance(1)
            assertEquals(SessionScreen.Cover, state.screen)
            assertNull(runtime.pinAuthorizedUid)
            assertNull(state.idleCountdownSeconds)
        }
    }

    @Test
    fun screenshotBlackoutExpiresWhileRecordingsKeepItUntilTheyEnd() {
        SessionRuntimeFixture(AccountRole.STAFF, dispatcher = Dispatchers.Main.immediate).use { fixture ->
            val runtime = fixture.runtime
            runtime.privacy.startIdleTimer()
            advance(90)
            assertEquals(SessionScreen.Portal, runtime.state.screen)
            assertNull(runtime.state.idleCountdownSeconds)
            runtime.enrollment = null
            runtime.privacy.captureDetected("Screenshot")
            assertTrue(runtime.state.privacyBlackout)
            advance(2)
            assertFalse(runtime.state.privacyBlackout)
            runtime.privacy.captureDetected("Screen recording")
            advance(2)
            assertTrue(runtime.state.privacyBlackout)
            runtime.privacy.recordingStopped()
            assertFalse(runtime.state.privacyBlackout)
        }
    }

    @Test
    fun extendOptionsSuggestLargerDurationsAndExtendingResetsActiveCountdown() {
        assertEquals(listOf(120, 300, 420), IdlePolicy.extendOptions(60))
        assertEquals(listOf(180, 300, 420), IdlePolicy.extendOptions(120))
        assertEquals(listOf(360, 420), IdlePolicy.extendOptions(300))
        assertEquals(emptyList<Int>(), IdlePolicy.extendOptions(420))

        SessionRuntimeFixture(dispatcher = Dispatchers.Main.immediate).use { fixture ->
            val runtime = fixture.runtime
            val state = runtime.state
            `when`(fixture.accounts.authenticatedUid()).thenReturn(null)
            runtime.privacy.setIdleTimeoutSeconds(60)
            runtime.privacy.startIdleTimer()
            advance(50)
            assertEquals(10, state.idleCountdownSeconds)

            runtime.privacy.setIdleTimeoutSeconds(180)
            assertEquals(180, runtime.privacy.idleTimeoutSeconds())
            assertNull(state.idleCountdownSeconds)

            advance(65)
            assertEquals(SessionScreen.Safe, state.screen)
            assertNull(state.idleCountdownSeconds)
        }
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
}
