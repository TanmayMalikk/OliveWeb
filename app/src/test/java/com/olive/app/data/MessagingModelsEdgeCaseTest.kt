// Tests direct-message names, anonymous staff names, and support-ticket labels for every role.
package com.olive.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingModelsEdgeCaseTest {
    // Builds a sample conversation for this test.
    private fun conversation(
        type: String = "SUPPORT",
        origin: String = SUPPORT_TAG_SUPPORT,
        tag: String = SUPPORT_TAG_SUPPORT,
        usernames: Map<String, String> = mapOf("owner" to "visible_user", "staff" to "real_staff"),
    ) = ChatConversation(
        id = "chat",
        friendshipId = "",
        participantIds = usernames.keys.toList(),
        participantUsernames = usernames,
        type = type,
        ownerUid = "owner",
        staffUid = "staff",
        adminUid = "admin",
        supportOrigin = origin,
        supportTag = tag,
        ticketSequence = 0L,
    )

    // Personal accounts see Olive Staff, while organization accounts see only the anonymous ticket number.
    @Test
    fun supportNamesHideBothStaffAndUserAccountNames() {
        val chat = conversation()

        assertEquals("Support Ticket #0", chat.displayName("owner", AccountRole.USER, "Olive Staff"))
        assertEquals("Support Ticket #0", chat.displayName("safe", AccountRole.SAFE_CONTACT, "Olive Staff"))
        assertEquals("Support Ticket #0", chat.displayName("staff", AccountRole.STAFF, "Olive Staff"))
        assertEquals("Support Ticket #0", chat.displayName("admin", AccountRole.ADMIN, "Olive Staff"))
    }

    // A missing username does not change the anonymous ticket label shown to organization accounts.
    @Test
    fun missingOwnerNameStillUsesAnonymousTicketNumber() {
        val chat = conversation(usernames = mapOf("staff" to "real_staff"))

        assertEquals("Support Ticket #0", chat.displayName("staff", AccountRole.STAFF))
    }

    // Inputs: the values shown below. Expected: direct chats show the other participant or conversation fallback.
    @Test
    fun directChatsShowTheOtherParticipantOrConversationFallback() {
        val direct = conversation(type = "DIRECT")
        assertEquals("real_staff", direct.displayName("owner", AccountRole.USER))

        val selfOnly = direct.copy(participantUsernames = mapOf("owner" to "visible_user"))
        assertEquals("Conversation", selfOnly.displayName("owner", AccountRole.USER))
    }

    // Inputs: a three-person group in member order. Expected: each viewer sees the other two names together.
    @Test
    fun groupChatsShowEveryOtherParticipant() {
        val group = conversation(
            type = "GROUP",
            usernames = linkedMapOf("owner" to "OliveUser", "safe" to "TrustedOne", "friend" to "FriendTwo"),
        )

        assertEquals("TrustedOne, FriendTwo", group.displayName("owner", AccountRole.USER))
        assertEquals("OliveUser, FriendTwo", group.displayName("safe", AccountRole.SAFE_CONTACT))
    }

    // Inputs: the values shown below. Expected: ticket labels distinguish consultation support and escalation.
    @Test
    fun ticketLabelsDistinguishConsultationSupportAndEscalation() {
        // Checks the name step with the test's sample data.
        fun name(chat: ChatConversation) = chat.anonymousStaffDisplayName(
            staffName = "Olive Staff",
            consultationName = "Consultation",
            supportName = "Support",
            escalationName = "Escalation",
        )

        assertEquals(
            "Consult Ticket #0",
            name(conversation(origin = SUPPORT_TAG_PLAN_CONSULT, tag = SUPPORT_TAG_PLAN_CONSULT)),
        )
        assertEquals("Support Ticket #0", name(conversation()))
        assertEquals(
            "Admin Ticket #0",
            name(conversation(origin = SUPPORT_TAG_PLAN_CONSULT, tag = SUPPORT_TAG_NEEDS_ADMIN)),
        )
        assertEquals("Support Ticket #0", name(conversation(origin = "", tag = "UNKNOWN")))
    }

    // Inputs: the values shown below. Expected: organization sender names are hidden only from personal accounts.
    @Test
    fun organizationSenderNamesAreHiddenOnlyFromPersonalAccounts() {
        val chat = conversation()

        assertEquals("Olive Staff", chat.senderDisplayName("staff", "real_staff", AccountRole.USER, "Olive Staff"))
        assertEquals("Olive Staff", chat.senderDisplayName("admin", "real_admin", AccountRole.SAFE_CONTACT, "Olive Staff"))
        assertEquals("real_staff", chat.senderDisplayName("staff", "real_staff", AccountRole.ADMIN, "Olive Staff"))
        assertEquals("visible_user", chat.senderDisplayName("owner", "visible_user", AccountRole.USER, "Olive Staff"))
    }
}
