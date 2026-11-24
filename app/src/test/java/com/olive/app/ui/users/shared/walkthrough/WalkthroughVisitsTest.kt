package com.olive.app.ui.users.shared.walkthrough

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WalkthroughVisitsTest {
    @Test
    fun initialTourFlagPersistsPerAccountAfterFirstLogin() {
        val context = RuntimeEnvironment.getApplication()
        val firstAccount = WalkthroughVisits(context, "first-account")
        assertFalse(firstAccount.hasSeenInitialTour())
        firstAccount.markInitialTourSeen()

        val reopened = WalkthroughVisits(context, "first-account")
        assertTrue(reopened.hasSeenInitialTour())
        assertFalse(WalkthroughVisits(context, "second-account").hasSeenInitialTour())
    }
}
