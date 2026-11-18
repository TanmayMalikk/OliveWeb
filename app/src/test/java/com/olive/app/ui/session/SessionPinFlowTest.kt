package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionPinFlowTest {
    @Test
    fun matchingCoverSearchRequiresTheCurrentPinChallengeAndRestoresOnlyTheEnrolledProfile() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            val state = runtime.state
            state.coverType = CoverType.NEWS
            assertFalse(runtime.access.interceptSearchAsync("ordinary search"))
            assertEquals(SessionScreen.Cover, state.screen)
            assertTrue(runtime.access.interceptSearchAsync("moon garden"))
            assertEquals(SessionScreen.Pin, state.screen)
            val challenge = state.pinChallengeVersion
            runtime.access.submitPin("123456", challenge - 1)
            assertFalse(state.busy)
            assertNull(runtime.pinAuthorizedUid)
            runtime.access.submitPin("invalid", challenge)
            assertEquals("enter_your_4_6_digit_pin", state.message)
            runtime.access.submitPin("999999", challenge)
            fixture.await { !state.busy }
            assertEquals("Incorrect PIN", state.message)
            assertEquals(SessionScreen.Pin, state.screen)
            runtime.access.submitPin("123456", challenge)
            fixture.await { state.screen == SessionScreen.Safe && !state.busy }
            assertEquals("owner", runtime.pinAuthorizedUid)
            assertEquals("Synthetic enrolled user", state.profile?.fullName)
            assertEquals(SafePage.Home, state.page)
            assertEquals(1, state.unlockAnimationVersion)
            val event = runtime.store.triggerHistory("owner").single()
            assertEquals("Daily Digest search", event.source)
            assertEquals(2, event.pinAttempts)
            assertEquals("Accepted", event.lastResult)
        }
    }

    @Test
    fun fifthFailedCoverPinReturnsToTheCoverAndCannotBeBypassedWithTheCorrectPin() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            runtime.access.beginDiscreetEntry()
            val challenge = runtime.state.pinChallengeVersion
            repeat(5) {
                runtime.access.submitPin("999999", challenge)
                fixture.await { !runtime.state.busy }
            }
            assertEquals(SessionScreen.Cover, runtime.state.screen)
            assertNull(runtime.pinAuthorizedUid)
            runtime.access.submitPin("123456", challenge)
            assertEquals(SessionScreen.Cover, runtime.state.screen)
            assertNull(runtime.state.profile)
        }
    }

    private fun fixture() = SessionRuntimeFixture().also { fixture ->
        val runtime = fixture.runtime
        val identity = AccountIdentity("owner", "fixture_owner")
        val profile = UserProfile("fixture_owner", "Synthetic enrolled user", "", "", "2000-01-01",
            "English", "", "", InputPolicy.triggerHash("moon garden"))
        runtime.enrollment = runtime.store.enroll(identity, profile, "123456")
        runtime.state.screen = SessionScreen.Cover
        runtime.pinAuthorizedUid = null
        `when`(fixture.accounts.authenticatedUid()).thenReturn(null)
        val resources = fixture.application.resources
        for (remaining in 1..4) {
            `when`(resources.getQuantityString(R.plurals.ui_incorrect_pin_value_attempts_remaining, remaining, remaining))
                .thenReturn("Incorrect PIN")
        }
    }
}
