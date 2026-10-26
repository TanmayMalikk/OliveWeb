package com.olive.app.ui.users.shared.messaging

import com.olive.app.data.AccountRole
import com.olive.app.data.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateContactNotesTest {
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
            conversation.privateNoteTargets("current", AccountRole.USER).map { it.uid },
        )
    }

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
            conversation.privateNoteTargets("staff", AccountRole.STAFF).map { it.uid },
        )
    }
}
