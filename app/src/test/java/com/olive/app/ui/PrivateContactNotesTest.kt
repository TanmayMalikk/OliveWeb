// Tests private notes linked to friends and assigned users.
package com.olive.app.ui.users.shared.messaging

import com.olive.app.data.AccountRole
import com.olive.app.data.ChatConversation
import com.olive.app.data.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.SUPPORT_TAG_PLAN_CONSULT
import com.olive.app.data.SUPPORT_TAG_SUPPORT
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateContactNotesTest {
    // Support chats hide organization names from personal accounts and user names from organization accounts.
    @Test
    fun supportChatsShowTheUserToStaffAndHideOrganizationNamesFromPersonalAccounts() {
        val conversation = ChatConversation(
            id = "support",
            friendshipId = "support",
            participantIds = listOf("user", "staff", "admin"),
            participantUsernames = mapOf("user" to "olive_user", "staff" to "real_staff_name"),
            type = "SUPPORT",
            ownerUid = "user",
            staffUid = "staff",
            adminUid = "admin",
            supportOrigin = SUPPORT_TAG_SUPPORT,
            supportTag = SUPPORT_TAG_SUPPORT,
        )

        assertEquals("Olive Staff", conversation.senderDisplayName("staff", "real_staff_name", AccountRole.USER, "Olive Staff"))
        assertEquals("Olive Staff", conversation.senderDisplayName("admin", "real_admin_name", AccountRole.SAFE_CONTACT, "Olive Staff"))
        assertEquals("real_staff_name", conversation.senderDisplayName("staff", "real_staff_name", AccountRole.ADMIN, "Olive Staff"))
        val anonymousName = conversation.anonymousStaffDisplayName(
            "Olive Staff", "Consultation", "Support", "Escalation"
        )
        assertEquals("Olive Staff • Support", conversation.displayName("user", AccountRole.USER, anonymousName))
        assertEquals("Olive Staff • Support", conversation.displayName("contact", AccountRole.SAFE_CONTACT, anonymousName))
        assertEquals("Ticket ${conversation.ticketNumber()}", conversation.displayName("staff", AccountRole.STAFF, "Olive Staff"))
        assertEquals("Ticket ${conversation.ticketNumber()}", conversation.displayName("admin", AccountRole.ADMIN, "Olive Staff"))
        assertEquals(
            "Olive Staff • Consultation",
            conversation.copy(supportOrigin = SUPPORT_TAG_PLAN_CONSULT, supportTag = SUPPORT_TAG_PLAN_CONSULT)
                .anonymousStaffDisplayName("Olive Staff", "Consultation", "Support", "Escalation"),
        )
        assertEquals(
            "Olive Staff • Escalation",
            conversation.copy(supportTag = SUPPORT_TAG_NEEDS_ADMIN)
                .anonymousStaffDisplayName("Olive Staff", "Consultation", "Support", "Escalation"),
        )
    }

    // Inputs: the values shown below. Expected: direct chats offer the other participant for private notes.
    @Test
    fun directChatsOfferTheOtherParticipantForPrivateNotes() {
        val conversation = ChatConversation(
            id = "chat",
            friendshipId = "friendship",
            participantIds = listOf("current", "friend"),
            participantUsernames = mapOf("current" to "olive_user", "friend" to "trusted_friend"),
        )
        assertEquals(
            listOf("friend"),
            conversation.privateNoteTargets("current", AccountRole.USER, setOf("friend")).map { it.uid },
        )
    }

    // Inputs: the values shown below. Expected: organization support chats offer only the user who owns the request.
    @Test
    fun organizationSupportChatsOfferOnlyTheUserWhoOwnsTheRequest() {
        val conversation = ChatConversation(
            id = "support",
            friendshipId = "support",
            participantIds = listOf("user", "staff", "contact"),
            participantUsernames = mapOf(
                "user" to "olive_user",
                "staff" to "olive_staff",
                "contact" to "safe_contact",
            ),
            type = "SUPPORT",
            ownerUid = "user",
        )
        assertEquals(
            listOf("user"),
            conversation.privateNoteTargets("staff", AccountRole.STAFF, emptySet()).map { it.uid },
        )
    }

    // Inputs: the values shown below. Expected: personal accounts cannot note people who are not friends.
    @Test
    fun personalAccountsCannotNotePeopleWhoAreNotFriends() {
        val conversation = ChatConversation(
            id = "support",
            friendshipId = "support",
            participantIds = listOf("user", "contact", "staff"),
            participantUsernames = mapOf("user" to "olive_user", "contact" to "safe_contact"),
            type = "SUPPORT",
            ownerUid = "user",
        )

        assertEquals(
            emptyList<String>(),
            conversation.privateNoteTargets("contact", AccountRole.SAFE_CONTACT, emptySet()).map { it.uid },
        )
    }
}
