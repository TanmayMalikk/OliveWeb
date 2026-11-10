// Users and safe contacts: manages private voice calls opened from direct chats.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.data.messaging.*
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map


// Calls are private-account features. Direct and personal group chats can call; support chats cannot.
internal fun canCallFromConversation(
    role: AccountRole,
    conversation: ChatConversation,
    currentUid: String,
    featureAvailable: Boolean = isVoipApiConfigured(),
): Boolean = featureAvailable && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) &&
    conversation.type in setOf("DIRECT", "GROUP") &&
    conversation.closedAt == 0L &&
    currentUid.isNotBlank() &&
    currentUid in conversation.participantIds &&
    conversation.participantIds.any { it != currentUid }

internal fun canUseVoip(role: AccountRole): Boolean =
    role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

internal fun shouldShowCallOverlay(screen: SessionScreen, role: AccountRole): Boolean =
    screen == SessionScreen.Safe && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

// A failed call with no room attached is shown inside its eligible chat instead of covering every safe-app page.
internal fun shouldShowActiveCall(state: VoipSessionState): Boolean = when (state) {
    is VoipSessionState.Connecting,
    is VoipSessionState.Active,
    is VoipSessionState.Leaving -> true
    is VoipSessionState.Error -> state.call != null
    VoipSessionState.Idle -> false
}

internal fun resolveIncomingCaller(
    invitation: VoipInvitation,
    conversations: List<ChatConversation>,
    currentUid: String,
): VoipInvitation? {
    val callerUsername = conversations.firstOrNull { conversation ->
        invitation.callerId in conversation.participantIds && currentUid in conversation.participantIds
    }?.participantUsernames?.get(invitation.callerId).orEmpty()
    return invitation.copy(name = callerUsername).takeIf { callerUsername.isNotBlank() }
}

internal fun shouldLeaveGroupCall(
    call: VoipCall,
    currentUid: String,
    remoteParticipantCount: Int,
): Boolean = call.participants.size > 2 && currentUid.isNotBlank() &&
    (call.callerId != currentUid || remoteParticipantCount > 0)

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
    private var callJoinJob: Job? = null
    private var observedUid: String? = null
    private var configuredRole: AccountRole? = null
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

    init {
        // Keeps the microphone session running while the phone is on Home or another app is open.
        scope.launch {
            state.map { callState ->
                when (callState) {
                    is VoipSessionState.Connecting -> callState.call.callId
                    is VoipSessionState.Active -> callState.call.callId
                    is VoipSessionState.Leaving -> callState.call.callId
                    else -> null
                }
            }.distinctUntilChanged().collect { callId ->
                if (callId == null) VoipCallForegroundService.stop(application)
                else VoipCallForegroundService.start(application, callId)
            }
        }
    }

    // Starts one invitation listener while this signed-in person has the messaging screen open.
    fun observeInvitations(uid: String, role: AccountRole) {
        if (configuredRole != role && uid.isNotBlank()) {
            configuredRole = role
            scope.launch {
                runCatching { VoipPushTokenRegistration.configureForRole(application, role) }
            }
        }
        if (!canUseVoip(role) || !isVoipApiConfigured() || uid.isBlank()) {
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

    // Starts a call with every other person in a direct or personal group chat.
    fun start(conversation: ChatConversation, callerUid: String, role: AccountRole) {
        if (!canCallFromConversation(role, conversation, callerUid)) {
            message = "Voice calls are available only in personal User and Safe Contact chats"
            return
        }
        val recipients = conversation.participantIds.filterNot { it == callerUid }.distinct()
        val recipientName = conversation.participantIds
            .filterNot { it == callerUid }
            .mapNotNull(conversation.participantUsernames::get)
            .filter(String::isNotBlank)
            .joinToString(", ")
            .ifBlank { "Private call" }
        val callerName = conversation.participantUsernames[callerUid]
            .orEmpty()
            .ifBlank { "Private call" }
        launchCallJoin {
            controller.startAndJoin(
                recipientName,
                callerUid,
                recipients,
                callerName,
                conversation.id,
            )
        }
    }

    // Accepts the selected invitation and joins its LiveKit audio room.
    fun accept(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        launchCallJoin { controller.acceptAndJoin(invitation.callId, currentUid) }
    }

    // Hides the invitation before Android opens its microphone permission window. This prevents
    // the polling loop from restoring the full-screen prompt while permission is being answered.
    fun hide(invitation: VoipInvitation) {
        suppressInvitation(invitation.callId)
    }

    // Declines the invitation without opening the microphone or joining its room.
    fun decline(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        scope.launch {
            runCallAction {
                manager.declineCall(invitation.callId, currentUid)
            }
        }
    }

    // Joins an active call from the event shown in chat, using the member's current call status.
    fun joinActive(call: VoipCall, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) ||
            !call.canBeJoinedBy(currentUid)
        ) return
        launchCallJoin {
            when (call.participants.firstOrNull { it.userId == currentUid }?.status) {
                VoipParticipantStatus.PENDING -> controller.acceptAndJoin(call.callId, currentUid)
                VoipParticipantStatus.DECLINED -> controller.rejoinCall(call.callId)
                VoipParticipantStatus.ACCEPTED -> controller.joinCall(call)
                null -> Unit
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

    private fun launchCallJoin(action: suspend () -> Unit) {
        if (callJoinJob?.isActive == true) return
        callJoinJob = scope.launch { runCallAction(action) }
    }

    fun canHandleNotification(callId: String): Boolean = when (val current = state.value) {
        is VoipSessionState.Connecting -> current.call.callId == callId
        is VoipSessionState.Active -> current.call.callId == callId
        else -> false
    }

    // A caller ends the room. A member leaving a group only disconnects their own phone.
    fun finish(currentUid: String, expectedCallId: String? = null): Job {
        val joining = callJoinJob
        return scope.launch {
            if (state.value == VoipSessionState.Idle) joining?.join()
            runCallAction {
                val active = state.value
                val call = when (active) {
                    is VoipSessionState.Connecting -> active.call
                    is VoipSessionState.Active -> active.call
                    is VoipSessionState.Error -> active.call
                    else -> null
                } ?: return@runCallAction
                if (expectedCallId != null && call.callId != expectedCallId) return@runCallAction
                val remoteParticipantCount = (active as? VoipSessionState.Active)
                    ?.remoteParticipantCount
                    ?: 0
                when {
                    active is VoipSessionState.Error -> controller.dismiss()
                    shouldLeaveGroupCall(call, currentUid, remoteParticipantCount) ->
                        controller.leaveCall()
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
        VoipCallForegroundService.stop(application)
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

    fun historyFor(conversationId: String, role: AccountRole): List<VoipCall> =
        if (canUseVoip(role)) callHistory.filter { it.conversationId == conversationId }
        else emptyList()

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
            // A failed API or room request returns to chat so its error cannot cover unrelated support screens.
            if (state.value is VoipSessionState.Error) controller.dismiss()
        }
    }

    companion object {
        private const val DISMISSED_INVITATIONS_KEY = "call_ids"
        private const val LOCAL_RING_TIMEOUT_MS = 20_000L
        private const val ACTIVE_CALL_POLL_MS = 1_000L
        private const val IDLE_CALL_POLL_MS = 5_000L
    }
}
