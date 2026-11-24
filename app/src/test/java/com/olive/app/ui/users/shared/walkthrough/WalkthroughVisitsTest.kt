package com.olive.app.ui.users.shared.walkthrough

import com.olive.app.ui.users.shared.navigation.SafePage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WalkthroughVisitsTest {
    @Test
    fun featureVisitsPersistAndAreScopedToTheAccountAndPage() {
        val context = RuntimeEnvironment.getApplication()
        val firstAccount = WalkthroughVisits(context, "first-account")
        assertFalse(firstAccount.hasSeen(SafePage.Notes))
        firstAccount.markSeen(SafePage.Notes)

        val reopened = WalkthroughVisits(context, "first-account")
        assertTrue(reopened.hasSeen(SafePage.Notes))
        assertFalse(reopened.hasSeen(SafePage.Documents))
        assertFalse(WalkthroughVisits(context, "second-account").hasSeen(SafePage.Notes))
    }
}
