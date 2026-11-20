package com.olive.app.ui.session

import androidx.work.impl.WorkManagerImpl
import com.olive.app.data.account.*
import com.olive.app.data.messaging.*
import com.olive.app.data.sync.AccountRevocationEvents
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionNavigationPrivacyTest {
    @Test
    fun quickExitInvalidatesPinAuthorizationClearsPrivateDataAndReturnsToTheEnrolledRole() {
        for (role in AccountRole.entries) SessionRuntimeFixture(role).use { f ->
            `when`(f.accounts.authenticatedUid()).thenReturn(null)
            val r = f.runtime
            val epoch = r.epoch
            r.state.message = "Private detail"
            r.state.privateEntries = listOf(PrivateEntry(title = "Private", body = "Synthetic body"))
            r.state.activeConversation = ChatConversation("chat", "pair", listOf("owner"), emptyMap())
            r.state.chatMessages = listOf(ChatMessage("m", "owner", "fixture", "Private", 1))
            r.safePageHistory += SafePage.Notes
            r.screenHistory += SessionScreen.Pin
            r.navigation.quickExit()
            assertEquals(epoch + 1, r.epoch)
            assertNull(r.pinAuthorizedUid)
            assertEquals(-1L, r.pinAuthorizedEpoch)
            assertTrue(r.state.privateEntries.isEmpty())
            assertTrue(r.state.chatMessages.isEmpty())
            assertNull(r.state.activeConversation)
            assertNull(r.state.message)
            assertTrue(r.safePageHistory.isEmpty())
            assertEquals(SafePage.Home, r.state.page)
            assertEquals(when(role) { AccountRole.USER -> SessionScreen.Cover; AccountRole.SAFE_CONTACT -> SessionScreen.Safe; else -> SessionScreen.Portal }, r.state.screen)
        }
    }

    @Test
    fun backClosesAConversationBeforeLeavingMessagesAndThenUsesThePrivateBackStack() {
        SessionRuntimeFixture().use { f ->
            val r = f.runtime
            r.state.activeConversation = ChatConversation("chat", "pair", listOf("owner"), emptyMap())
            r.navigation.back()
            assertNull(r.state.activeConversation)
            assertEquals(SafePage.Messages, r.state.page)
            r.safePageHistory += SafePage.Home
            r.navigation.back()
            assertEquals(SafePage.Home, r.state.page)
            r.state.page = SafePage.Documents
            r.navigation.back()
            assertEquals(SafePage.Home, r.state.page)
        }
    }

    @Test
    fun backgroundClearsUnfinishedEnrollmentAndNeverUnlocksThePrivateArea() {
        SessionRuntimeFixture().use { f ->
            val r = f.runtime
            r.enrollment = null
            r.state.screen = SessionScreen.ProfileSetup
            r.pendingIdentity = AccountIdentity("new", "new_fixture")
            r.state.busy = true
            r.navigation.background()
            assertEquals(SessionScreen.Cover, r.state.screen)
            assertNull(r.pendingIdentity)
            assertFalse(r.state.busy)
            assertEquals(CoverType.FITNESS, r.state.coverType)
            val epoch = r.epoch
            r.navigation.background()
            assertEquals(epoch, r.epoch)
        }
    }

    @Test
    fun openingAndReturningToPublicScreensPreservesTheSelectedCoverAndAvoidsDuplicateHistory() {
        SessionRuntimeFixture().use { f ->
            val r = f.runtime
            r.state.screen = SessionScreen.Cover
            r.state.coverType = CoverType.GAME
            r.navigation.openScreen(SessionScreen.ReturningCredentials)
            r.navigation.openScreen(SessionScreen.ReturningCredentials)
            assertEquals(1, r.screenHistory.size)
            r.navigation.back()
            assertEquals(SessionScreen.Cover, r.state.screen)
            assertEquals(CoverType.GAME, r.state.coverType)
            r.navigation.back()
            assertEquals(SessionScreen.Cover, r.state.screen)
        }
    }

    @Test
    fun revocationOfThisAccountClosesItsPrivateWorkspaceAndIgnoresOtherAccounts() {
        SessionRuntimeFixture(AccountRole.SAFE_CONTACT).use { fixture ->
            val session = fixture.runtime
            val privateEntry = PrivateEntry(title = "Synthetic private entry", body = "Private fixture")
            session.state.profile = UserProfile("fixture_owner", "Synthetic", "", "", "2000-01-01", "English", "", "Edmonton", "")
            session.state.viewedSharedEntry = privateEntry
            session.state.privateEntries = listOf(privateEntry)
            val work = mock(WorkManagerImpl::class.java)
            `when`(fixture.accounts.authenticatedUid()).thenReturn(null)
            WorkManagerImpl.setDelegate(work)
            try {
                AccountRevocationEvents.publish("other")
                assertEquals("owner", session.enrollment?.uid)
                assertEquals(privateEntry, session.state.viewedSharedEntry)
                AccountRevocationEvents.publish("owner")
                assertNull(session.enrollment)
                assertNull(session.state.profile)
                assertNull(session.state.viewedSharedEntry)
                assertTrue(session.state.privateEntries.isEmpty())
                assertEquals(SessionScreen.Cover, session.state.screen)
            } finally {
                WorkManagerImpl.setDelegate(null)
            }
        }
    }
}
