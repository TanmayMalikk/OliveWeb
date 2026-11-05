// Users and safe contacts: manages private voice calls opened from direct chats.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.AccountRole
import com.olive.app.data.ChatConversation
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipManager
import com.olive.app.data.messaging.VoipSessionController
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipCallStatus
import com.olive.app.data.messaging.VoipParticipantStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Calls are private-account features. Support tickets can include staff or admins and never qualify.
internal fun canCallFromConversation(
    role: AccountRole,
    conversation: ChatConversation,
    currentUid: String,
): Boolean = role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) &&
    conversation.type == "DIRECT" &&
    conversation.closedAt == 0L &&
    currentUid.isNotBlank() &&
    currentUid in conversation.participantIds &&
    conversation.participantIds.any { it != currentUid }

internal class SessionCalls(
    private val application: Application,
    private val scope: CoroutineScope,
    private val manager: VoipManager = VoipManager(),
) {
    private val invitationPreferences = application.getSharedPreferences(
        "dismissed-voip-invitations",
        android.content.Context.MODE_PRIVATE,
    )
    private val controller = VoipSessionController.create(application, manager)
    private var invitationJob: Job? = null
    private var observedUid: String? = null
    private val suppressedInvitationIds = invitationPreferences
        .getStringSet(DISMISSED_INVITATIONS_KEY, emptySet())
        .orEmpty()
        .toMutableSet()
    private val invitationFirstSeenAt = mutableMapOf<String, Long>()

    val state: StateFlow<VoipSessionState> = controller.state
    var invitations by mutableStateOf(emptyList<VoipInvitation>())
        private set
    var callHistory by mutableStateOf(emptyList<VoipCall>())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // Starts one invitation listener while this signed-in person has the messaging screen open.
    fun observeInvitations(uid: String, role: AccountRole) {
        if (role !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) || uid.isBlank()) {
            stopObserving()
            return
        }
        if (observedUid == uid && invitationJob?.isActive == true) return
        stopObserving()
        observedUid = uid
        invitationJob = scope.launch {
            while (isActive) {
                try {
                    val calls = manager.listCalls()
                    callHistory = calls
                    val now = System.currentTimeMillis()
                    val received = calls.filter { call ->
                        call.status == VoipCallStatus.ACTIVE && call.callerId != uid &&
                            call.participants.any {
                                it.userId == uid && it.status == VoipParticipantStatus.PENDING
                            }
                    }.map { call ->
                        VoipInvitation(
                            callId = call.callId,
                            callerId = call.callerId,
                            name = call.callerName.ifBlank { call.name },
                            status = VoipParticipantStatus.PENDING,
                            expiresAt = call.expiresAt,
                        )
                    }
                    received.forEach { invitationFirstSeenAt.putIfAbsent(it.callId, now) }
                    val locallyExpired = received.filter { invitation ->
                        val deadline = invitation.expiresAt.takeIf { it > 0 }
                            ?: invitationFirstSeenAt.getValue(invitation.callId) + LOCAL_RING_TIMEOUT_MS
                        deadline <= now
                    }.mapTo(mutableSetOf(), VoipInvitation::callId)
                    if (locallyExpired.isNotEmpty()) rememberSuppressed(locallyExpired)
                    invitations = received.filter {
                        it.callId !in suppressedInvitationIds
                    }
                    val sessionCall = when (val current = state.value) {
                        is VoipSessionState.Connecting -> current.call
                        is VoipSessionState.Active -> current.call
                        else -> null
                    }
                    if (sessionCall != null && calls.firstOrNull {
                            it.callId == sessionCall.callId
                        }?.status?.let { it != VoipCallStatus.ACTIVE } == true
                    ) controller.dismiss()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    message = error.message ?: "Could not check incoming calls"
                }
                val callIsOpen = state.value is VoipSessionState.Connecting ||
                    state.value is VoipSessionState.Active || invitations.isNotEmpty()
                delay(if (callIsOpen) ACTIVE_CALL_POLL_MS else IDLE_CALL_POLL_MS)
            }
        }
    }

    // Creates a call only from a direct chat owned by a User or Safe Contact account.
    fun start(conversation: ChatConversation, callerUid: String, role: AccountRole) {
        if (!canCallFromConversation(role, conversation, callerUid)) {
            message = "Voice calls are available only in direct User and Safe Contact chats"
            return
        }
        val recipients = conversation.participantIds.filterNot { it == callerUid }.distinct()
        val recipientName = conversation.participantUsernames.entries
            .firstOrNull { it.key != callerUid }
            ?.value
            .orEmpty()
            .ifBlank { "Private call" }
        val callerName = conversation.participantUsernames[callerUid]
            .orEmpty()
            .ifBlank { "Private call" }
        scope.launch {
            runCallAction {
                controller.startAndJoin(
                    recipientName,
                    callerUid,
                    recipients,
                    callerName,
                    conversation.id,
                )
            }
        }
    }

    // Accepts the selected invitation and joins its LiveKit audio room.
    fun accept(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (role !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        scope.launch {
            runCallAction {
                controller.acceptAndJoin(invitation.callId, currentUid)
            }
        }
    }

    // Hides the invitation before Android opens its microphone permission window. This prevents
    // the polling loop from restoring the full-screen prompt while permission is being answered.
    fun hide(invitation: VoipInvitation) {
        suppressInvitation(invitation.callId)
    }

    // Declines the invitation without opening the microphone or joining its room.
    fun decline(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (role !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        scope.launch {
            runCallAction {
                manager.declineCall(invitation.callId, currentUid)
            }
        }
    }

    // Updates whether other people in the active call can hear this device's microphone.
    fun setMuted(muted: Boolean) {
        scope.launch { runCallAction { controller.setMicrophoneMuted(muted) } }
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        scope.launch { runCallAction { controller.setSpeakerEnabled(enabled) } }
    }

    // Either participant ends the one shared room for both phones.
    fun finish(@Suppress("UNUSED_PARAMETER") currentUid: String) {
        scope.launch {
            runCallAction {
                val active = state.value
                val call = when (active) {
                    is VoipSessionState.Connecting -> active.call
                    is VoipSessionState.Active -> active.call
                    is VoipSessionState.Error -> active.call
                    else -> null
                } ?: return@runCallAction
                when {
                    active is VoipSessionState.Error -> controller.dismiss()
                    else -> controller.endCall()
                }
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    fun report(message: String) {
        this.message = message
    }

    // Stops network polling when the session is cleared or the account no longer supports calls.
    fun close() {
        stopObserving()
        val current = state.value
        if (current is VoipSessionState.Connecting || current is VoipSessionState.Active ||
            current is VoipSessionState.Error) finish("")
    }

    private fun stopObserving() {
        invitationJob?.cancel()
        invitationJob = null
        observedUid = null
        invitations = emptyList()
        invitationFirstSeenAt.clear()
    }

    fun historyFor(conversationId: String): List<VoipCall> =
        callHistory.filter { it.conversationId == conversationId }

    private fun cancelNotification(callId: String) {
        androidx.core.app.NotificationManagerCompat.from(application).cancel(callId.hashCode())
    }

    private fun suppressInvitation(callId: String) {
        rememberSuppressed(setOf(callId))
        invitations = invitations.filterNot { it.callId == callId }
        cancelNotification(callId)
    }

    private fun rememberSuppressed(callIds: Set<String>) {
        suppressedInvitationIds += callIds
        invitationPreferences.edit()
            .putStringSet(DISMISSED_INVITATIONS_KEY, suppressedInvitationIds.toSet())
            .apply()
    }

    private suspend fun runCallAction(action: suspend () -> Unit) {
        message = null
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message = error.message ?: "The voice call could not be completed"
        }
    }

    companion object {
        private const val DISMISSED_INVITATIONS_KEY = "call_ids"
        private const val LOCAL_RING_TIMEOUT_MS = 20_000L
        private const val ACTIVE_CALL_POLL_MS = 1_000L
        private const val IDLE_CALL_POLL_MS = 5_000L
    }
}
