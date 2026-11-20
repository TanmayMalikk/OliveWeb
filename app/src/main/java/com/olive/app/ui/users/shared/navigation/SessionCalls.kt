// Users and safe contacts: manages private voice calls opened from direct chats.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds


/**
 * Calls are private-account features. Direct and personal group chats can call; support chats cannot.
 *
 * @param role Account role determining workspace permissions.
 * @param conversation Conversation metadata including participants and ticket status.
 * @param currentUid Current user ID supplied to [canCallFromConversation].
 * @param featureAvailable Whether to apply feature available.
 * @return `true` if can call from conversation, or `false` otherwise.
 */
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

/**
 * Checks whether the caller can use VoIP.
 *
 * @param role Account role determining workspace permissions.
 * @return `true` if can use VoIP, or `false` otherwise.
 */
internal fun canUseVoip(role: AccountRole): Boolean =
    role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

/**
 * Determines whether the app should show call overlay.
 *
 * @param screen Screen supplied to [shouldShowCallOverlay].
 * @param role Account role determining workspace permissions.
 * @return `true` if should show call overlay, or `false` otherwise.
 */
internal fun shouldShowCallOverlay(screen: SessionScreen, role: AccountRole): Boolean =
    screen == SessionScreen.Safe && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

/**
 * A failed call with no room attached is shown inside its eligible chat instead of covering every safe-app page.
 *
 * @param state Current state instance to read or render.
 * @return `true` if should show active call, or `false` otherwise.
 */
internal fun shouldShowActiveCall(state: VoipSessionState): Boolean = when (state) {
    is VoipSessionState.Connecting,
    is VoipSessionState.Active,
    is VoipSessionState.Leaving -> true
    is VoipSessionState.Error -> state.call != null
    VoipSessionState.Idle -> false
}

/**
 * Resolves incoming caller.
 *
 * @param invitation Invitation supplied to [resolveIncomingCaller].
 * @param conversations Collection of conversations values to process.
 * @param currentUid Current user ID supplied to [resolveIncomingCaller].
 * @return The resulting [VoipInvitation], or `null` if unavailable.
 */
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

/**
 * Determines whether the app should leave group call.
 *
 * @param call Call supplied to [shouldLeaveGroupCall].
 * @param currentUid Current user ID supplied to [shouldLeaveGroupCall].
 * @param remoteParticipantCount Remote participant count supplied to [shouldLeaveGroupCall].
 * @return `true` if should leave group call, or `false` otherwise.
 */
internal fun shouldLeaveGroupCall(
    call: VoipCall,
    currentUid: String,
    remoteParticipantCount: Int,
): Boolean = call.participants.size > 2 && currentUid.isNotBlank() &&
    (call.callerId != currentUid || remoteParticipantCount > 0)

internal data class SessionCallsUiState(
    val invitations: List<VoipInvitation> = emptyList(),
    val callHistory: List<VoipCall> = emptyList(),
    val message: String? = null,
)

internal class SessionCalls(
    private val application: Application,
    private val scope: CoroutineScope,
    private val manager: VoipManager = VoipManager(),
    private val controller: VoipSessionController = VoipSessionController.create(application, manager),
    private val available: Boolean = isVoipApiConfigured(),
) {
    private val invitationPreferences = application.getSharedPreferences(
        "dismissed-voip-invitations",
        android.content.Context.MODE_PRIVATE,
    )
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
    private val uiStateFlow = MutableStateFlow(SessionCallsUiState())
    val uiState: StateFlow<SessionCallsUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    private inline fun updateUiState(transform: (SessionCallsUiState) -> SessionCallsUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    var invitations: List<VoipInvitation>
        get() = uiStateSnapshot.invitations
        private set(value) = updateUiState { it.copy(invitations = value) }
    var callHistory: List<VoipCall>
        get() = uiStateSnapshot.callHistory
        private set(value) = updateUiState { it.copy(callHistory = value) }
    var message: String?
        get() = uiStateSnapshot.message
        private set(value) = updateUiState { it.copy(message = value) }

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
                else VoipCallForegroundService.start(application, callId, configuredRole == AccountRole.USER)
            }
        }
    }

    /**
     * Starts one invitation listener while this signed-in person has the messaging screen open.
     *
     * @param uid Firebase UID of the target user account.
     * @param role Account role determining workspace permissions.
     */
    fun observeInvitations(uid: String, role: AccountRole) {
        if (configuredRole != role && uid.isNotBlank()) {
            configuredRole = role
            scope.launch {
                runCatching { VoipPushTokenRegistration.configureForRole(application, role) }
            }
        }
        if (!canUseVoip(role) || !available || uid.isBlank()) {
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
                            ?: (invitationFirstSeenAt.getValue(invitation.callId) + LOCAL_RING_TIMEOUT_MS)
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
                delay((if (callIsOpen) ACTIVE_CALL_POLL_MS else IDLE_CALL_POLL_MS).milliseconds)
            }
        }
    }

    /**
     * Starts a call with every other person in a direct or personal group chat.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @param callerUid Caller user ID supplied to [start].
     * @param role Account role determining workspace permissions.
     */
    fun start(conversation: ChatConversation, callerUid: String, role: AccountRole) {
        if (!canCallFromConversation(role, conversation, callerUid, available)) {
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

    /**
     * Accepts the selected invitation and joins its LiveKit audio room.
     *
     * @param invitation Invitation supplied to [accept].
     * @param currentUid Current user ID supplied to [accept].
     * @param role Account role determining workspace permissions.
     */
    fun accept(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        launchCallJoin { controller.acceptAndJoin(invitation.callId, currentUid) }
    }

    /**
     * Hides the invitation before Android opens its microphone permission window. This prevents the polling loop from restoring the full-screen prompt while permission is being answered.
     *
     * @param invitation Invitation supplied to [hide].
     */
    fun hide(invitation: VoipInvitation) {
        suppressInvitation(invitation.callId)
    }

    /**
     * Declines the invitation without opening the microphone or joining its room.
     *
     * @param invitation Invitation supplied to [decline].
     * @param currentUid Current user ID supplied to [decline].
     * @param role Account role determining workspace permissions.
     */
    fun decline(invitation: VoipInvitation, currentUid: String, role: AccountRole) {
        if (!canUseVoip(role) || currentUid.isBlank()) return
        suppressInvitation(invitation.callId)
        scope.launch {
            runCallAction {
                manager.declineCall(invitation.callId, currentUid)
            }
        }
    }

    /**
     * Joins an active call from the event shown in chat, using the member's current call status.
     *
     * @param call Call supplied to [joinActive].
     * @param currentUid Current user ID supplied to [joinActive].
     * @param role Account role determining workspace permissions.
     */
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

    /**
     * Updates whether other people in the active call can hear this device's microphone.
     *
     * @param muted Whether to apply muted.
     */
    fun setMuted(muted: Boolean) {
        scope.launch { runCallAction { controller.setMicrophoneMuted(muted) } }
    }

    /**
     * Sets speaker enabled.
     *
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setSpeakerEnabled(enabled: Boolean) {
        scope.launch { runCallAction { controller.setSpeakerEnabled(enabled) } }
    }

    /**
     * Launches call join.
     *
     * @param action Action or callback executed for action.
     */
    private fun launchCallJoin(action: suspend () -> Unit) {
        if (callJoinJob?.isActive == true) return
        callJoinJob = scope.launch { runCallAction(action) }
    }

    /**
     * Checks whether the caller can handle notification.
     *
     * @param callId Unique identifier of the VoIP call session.
     * @return `true` if can handle notification, or `false` otherwise.
     */
    fun canHandleNotification(callId: String): Boolean = when (val current = state.value) {
        is VoipSessionState.Connecting -> current.call.callId == callId
        is VoipSessionState.Active -> current.call.callId == callId
        else -> false
    }

    /**
     * A caller ends the room. A member leaving a group only disconnects their own phone.
     *
     * @param currentUid Current user ID supplied to [finish].
     * @param expectedCallId Optional expected call ID supplied to [finish].
     * @return The resulting [Job] instance.
     */
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

    /**
     * Clears message.
     */
    fun clearMessage() {
        message = null
    }

    /**
     * Reports the session calls.
     *
     * @param message Chat message or remote payload.
     */
    fun report(message: String) {
        this.message = message
    }

    /**
     * Stops network polling when the session is cleared or the account no longer supports calls.
     */
    fun close() {
        stopObserving()
        VoipCallForegroundService.stop(application)
        val current = state.value
        if (current is VoipSessionState.Connecting || current is VoipSessionState.Active ||
            current is VoipSessionState.Error) finish("")
    }

    /**
     * Stops observing.
     */
    private fun stopObserving() {
        invitationJob?.cancel()
        invitationJob = null
        observedUid = null
        invitations = emptyList()
        invitationFirstSeenAt.clear()
    }

    /**
     * Computes history for.
     *
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param role Account role determining workspace permissions.
     * @return List of resulting history for items.
     */
    fun historyFor(conversationId: String, role: AccountRole): List<VoipCall> =
        if (canUseVoip(role)) callHistory.filter { it.conversationId == conversationId }
        else emptyList()

    /**
     * Cancels notification.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    private fun cancelNotification(callId: String) {
        androidx.core.app.NotificationManagerCompat.from(application).cancel(callId.hashCode())
    }

    /**
     * Computes suppress invitation.
     *
     * @param callId Unique identifier of the VoIP call session.
     */
    private fun suppressInvitation(callId: String) {
        rememberSuppressed(setOf(callId))
        invitations = invitations.filterNot { it.callId == callId }
        cancelNotification(callId)
    }

    /**
     * Computes remember suppressed.
     *
     * @param callIds Collection of call IDs values to process.
     */
    private fun rememberSuppressed(callIds: Set<String>) {
        suppressedInvitationIds += callIds
        invitationPreferences.edit {
                putStringSet(DISMISSED_INVITATIONS_KEY, suppressedInvitationIds.toSet())
            }
    }

    /**
     * Executes call action.
     *
     * @param action Action or callback executed for action.
     */
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
