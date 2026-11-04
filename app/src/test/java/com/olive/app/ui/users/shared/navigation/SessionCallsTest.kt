package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.AccountRole
import com.olive.app.data.ChatConversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCallsTest {
    // A User can call the other person in an open direct conversation.
    @Test
    fun userCanCallFromDirectConversation() {
        assertTrue(canCallFromConversation(AccountRole.USER, directConversation(), "user-1"))
    }

    // A Safe Contact gets the same direct-call access as a User.
    @Test
    fun safeContactCanCallFromDirectConversation() {
        assertTrue(canCallFromConversation(AccountRole.SAFE_CONTACT, directConversation(), "safe-1"))
    }

    // Staff and Admin accounts cannot reveal or bypass the call button policy.
    @Test
    fun organizationAccountsCannotStartDirectCalls() {
        assertFalse(canCallFromConversation(AccountRole.STAFF, directConversation(), "user-1"))
        assertFalse(canCallFromConversation(AccountRole.ADMIN, directConversation(), "user-1"))
    }

    // Support tickets may contain organization accounts, so they never allow calls.
    @Test
    fun supportConversationCannotStartCall() {
        val support = directConversation().copy(type = "SUPPORT", staffUid = "staff-1")
        assertFalse(canCallFromConversation(AccountRole.USER, support, "user-1"))
        assertFalse(canCallFromConversation(AccountRole.SAFE_CONTACT, support, "safe-1"))
    }

    // Closed chats and unrelated accounts cannot be used to construct a call.
    @Test
    fun closedOrUnrelatedConversationCannotStartCall() {
        assertFalse(canCallFromConversation(AccountRole.USER, directConversation().copy(closedAt = 1L), "user-1"))
        assertFalse(canCallFromConversation(AccountRole.USER, directConversation(), "outsider"))
    }

    private fun directConversation() = ChatConversation(
        id = "chat-1",
        friendshipId = "friendship-1",
        participantIds = listOf("user-1", "safe-1"),
        participantUsernames = mapOf("user-1" to "User", "safe-1" to "Safe Contact"),
    )
}
