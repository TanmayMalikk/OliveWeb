package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.AccountRole
import com.olive.app.data.ChatConversation
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.canBeRejoinedBy
import com.olive.app.data.messaging.canBeJoinedBy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCallsTest {
    // A User can call the other person in an open direct conversation.
    @Test
    fun userCanCallFromDirectConversation() {
        assertTrue(canCallFromConversation(AccountRole.USER, directConversation(), "user-1"))
    }

    // A personal group call includes every User and Safe Contact already in the chat.
    @Test
    fun userCanCallFromGroupConversation() {
        val group = directConversation().copy(
            type = "GROUP",
            participantIds = listOf("user-1", "safe-1", "user-2"),
        )
        assertTrue(canCallFromConversation(AccountRole.USER, group, "user-1"))
    }

    // A Safe Contact gets the same direct-call access as a User.
    @Test
    fun safeContactCanCallFromDirectConversation() {
        assertTrue(canCallFromConversation(AccountRole.SAFE_CONTACT, directConversation(), "safe-1"))
    }

    // Staff and Admin accounts cannot reveal or bypass the call button policy.
    @Test
    fun organizationAccountsCannotStartDirectCalls() {
        assertFalse(canUseVoip(AccountRole.STAFF))
        assertFalse(canUseVoip(AccountRole.ADMIN))
        assertFalse(canCallFromConversation(AccountRole.STAFF, directConversation(), "user-1"))
        assertFalse(canCallFromConversation(AccountRole.ADMIN, directConversation(), "user-1"))
    }

    // Only personal User and Safe Contact accounts register for incoming calls.
    @Test
    fun onlyPersonalAccountsUseVoip() {
        assertTrue(canUseVoip(AccountRole.USER))
        assertTrue(canUseVoip(AccountRole.SAFE_CONTACT))
        assertFalse(canUseVoip(AccountRole.STAFF))
        assertFalse(canUseVoip(AccountRole.ADMIN))
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

    // Missing optional VoIP configuration hides calling while the rest of messaging stays available.
    @Test
    fun missingVoipConfigurationDisablesOnlyCalling() {
        assertFalse(
            canCallFromConversation(
                AccountRole.USER,
                directConversation(),
                "user-1",
                featureAvailable = false,
            )
        )
    }

    // A guest leaving a group only disconnects that guest; the caller still owns the room.
    @Test
    fun onlyNonCallerGroupMembersLeaveWithoutEndingRoom() {
        val groupCall = VoipCall(
            callId = "call-1",
            name = "Group",
            callerId = "user-1",
            participants = listOf(
                VoipParticipant("user-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("safe-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("user-2", VoipParticipantStatus.ACCEPTED),
            ),
        )
        assertTrue(shouldLeaveGroupCall(groupCall, "safe-1", remoteParticipantCount = 2))
        assertTrue(shouldLeaveGroupCall(groupCall, "user-1", remoteParticipantCount = 1))
        assertFalse(shouldLeaveGroupCall(groupCall, "user-1", remoteParticipantCount = 0))
        assertFalse(
            shouldLeaveGroupCall(
                groupCall.copy(participants = groupCall.participants.take(2)),
                "safe-1",
                remoteParticipantCount = 1,
            )
        )
    }

    // The chat offers rejoin only to someone who left while two members remain connected.
    @Test
    fun rejoinRequiresTwoConnectedMembers() {
        val call = VoipCall(
            callId = "call-1",
            name = "Group",
            callerId = "user-1",
            participants = listOf(
                VoipParticipant("user-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("safe-1", VoipParticipantStatus.DECLINED),
                VoipParticipant("user-2", VoipParticipantStatus.ACCEPTED),
            ),
        )
        assertTrue(call.canBeRejoinedBy("safe-1"))
        assertFalse(
            call.copy(participants = call.participants.dropLast(1))
                .canBeRejoinedBy("safe-1")
        )
        assertFalse(call.canBeRejoinedBy("user-2"))
    }

    // The active call card lets a pending member accept and a connected member restore audio.
    @Test
    fun activeCallCardAllowsInvitedAndConnectedMembersToJoin() {
        val call = VoipCall(
            callId = "call-1",
            name = "Group",
            callerId = "user-1",
            participants = listOf(
                VoipParticipant("user-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("safe-1", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("user-2", VoipParticipantStatus.PENDING),
            ),
        )
        assertTrue(call.canBeJoinedBy("user-1"))
        assertTrue(call.canBeJoinedBy("user-2"))
        assertFalse(call.canBeJoinedBy("outsider"))
    }

    private fun directConversation() = ChatConversation(
        id = "chat-1",
        friendshipId = "friendship-1",
        participantIds = listOf("user-1", "safe-1"),
        participantUsernames = mapOf("user-1" to "User", "safe-1" to "Safe Contact"),
    )
}
