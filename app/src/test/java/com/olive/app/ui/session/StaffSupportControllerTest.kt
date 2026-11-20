package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.*
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaffSupportControllerTest {
    @Test
    fun requestingAndCancellingHelpKeepsTheMatchingTicketIdentity() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }
            `when`(repo.requestHelp()).thenReturn(StaffSupportTicket(true, "staff", "ticket"))
            `when`(repo.cancelRequest("ticket")).thenReturn(StaffSupportTicket(false, "", "ticket"))
            controller.requestHelp()
            f.await { controller.saving == 0 }
            assertTrue(controller.requested)
            assertEquals("staff", controller.staffUid)
            assertEquals("ticket", controller.conversationId)
            controller.cancelRequest()
            f.await { controller.saving == 0 }
            assertFalse(controller.requested)
            verify(repo).cancelRequest("ticket")
            `when`(repo.requestHelp()).thenAnswer { error("Synthetic transport failure") }
            controller.requestHelp()
            f.await { controller.saving == 0 }
            assertEquals(R.string.support_request_error, controller.error)
            assertEquals("", controller.conversationId)
        }
    }

    @Test
    fun membershipErrorsAreShownAndStaffRequestsReturnOnlyTheNewTicket() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }
            `when`(repo.addParticipant("chat", "friend")).thenAnswer { error("Friend required") }
            controller.addParticipant("chat", "friend")
            f.await { controller.saving == 0 }
            assertEquals("Friend required", controller.addParticipantError)
            controller.clearChatInfoErrors()
            assertNull(controller.addParticipantError)
            controller.removeParticipant("chat", "friend")
            f.await { controller.saving == 0 }
            verify(repo).removeParticipant("chat", "friend")
            `when`(repo.requestStaffForConversation("chat")).thenReturn("new-ticket")
            var opened = ""
            controller.requestStaffForConversation("chat") { opened = it }
            f.await { controller.saving == 0 }
            assertEquals("new-ticket", opened)
            `when`(repo.requestStaffForConversation("chat")).thenAnswer { error("No available staff") }
            controller.requestStaffForConversation("chat") { fail("Must not open on failure") }
            f.await { controller.saving == 0 }
            assertEquals("No available staff", controller.requestStaffError)
        }
    }

    @Test
    fun staffCanEscalateAndCloseATicketAndClosingUpdatesTheActiveList() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }
            val ticket = ChatConversation("ticket", "SUPPORT", listOf("owner", "user"), emptyMap())
            f.runtime.state.conversations = listOf(ticket)
            f.runtime.state.activeConversation = ticket
            f.runtime.state.restoredClosedConversationIds = setOf("ticket")
            controller.requestAdmin("ticket")
            f.await { controller.saving == 0 }
            verify(repo).requestAdmin("ticket")
            var closed = false
            controller.close("ticket") { closed = true }
            f.await { controller.saving == 0 }
            assertTrue(closed)
            assertTrue(f.runtime.state.conversations.single().closedAt > 0)
            assertNull(f.runtime.state.activeConversation)
            assertTrue(f.runtime.state.restoredClosedConversationIds.isEmpty())
            `when`(repo.close("ticket", false)).thenAnswer { error("Denied") }
            controller.close("ticket")
            f.await { controller.saving == 0 }
            assertEquals(R.string.support_close_error, controller.error)
            `when`(repo.requestAdmin("ticket")).thenAnswer { error("Denied") }
            controller.requestAdmin("ticket")
            f.await { controller.saving == 0 }
            assertEquals(R.string.support_admin_request_error, controller.error)
        }
    }

    @Test
    fun userCannotCloseOrEscalateTicketsAndEmptyMemberActionsDoNothing() {
        SessionRuntimeFixture().use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }
            controller.close("ticket")
            controller.requestAdmin("ticket")
            controller.addParticipant("", "friend")
            controller.removeParticipant("ticket", "")
            controller.requestStaffForConversation("") { fail() }
            verifyNoInteractions(repo)
        }
    }

    @Test
    fun planCompletedCloseAndRemovalFailuresPreserveSafeState() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }
            controller.close("ticket", planCompleted = true)
            f.await { controller.saving == 0 }
            verify(repo).close("ticket", true)

            `when`(repo.removeParticipant("chat", "friend")).thenAnswer { error("Minimum two members") }
            controller.removeParticipant("chat", "friend")
            f.await { controller.saving == 0 }
            assertEquals("Minimum two members", controller.addParticipantError)
        }

        SessionRuntimeFixture(localMode = true).use { local ->
            val controller = StaffSupportController(local.runtime)
            controller.requestHelp()
            assertEquals(R.string.safety_account_required, controller.error)
        }
    }

    @Test
    fun staffSupportActionFailuresSurfaceAppropriateErrors() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            val repo = mock(SupportRepository::class.java)
            val controller = StaffSupportController(f.runtime) { repo }

            `when`(repo.requestAdmin("ticket")).thenAnswer { error("admin escalation failed") }
            controller.requestAdmin("ticket")
            f.await { controller.saving == 0 }
            assertEquals(R.string.support_admin_request_error, controller.error)

            `when`(repo.close("ticket", false)).thenAnswer { error("close failed") }
            controller.close("ticket", false)
            f.await { controller.saving == 0 }
            assertEquals(R.string.support_close_error, controller.error)
        }
    }
}
