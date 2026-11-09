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
import com.olive.app.data.messaging.canBeJoinedBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.keepVisibleWhileTyping
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.NewsArticle
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ui.session.SessionRuntime


// Calls are private-account features. Direct and personal group chats can call; support chats cannot.
internal fun canCallFromConversation(
    role: AccountRole,
    conversation: ChatConversation,
    currentUid: String,
): Boolean = role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) &&
    conversation.type in setOf("DIRECT", "GROUP") &&
    conversation.closedAt == 0L &&
    currentUid.isNotBlank() &&
    currentUid in conversation.participantIds &&
    conversation.participantIds.any { it != currentUid }

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

    // Joins an active call from the event shown in chat, using the member's current call status.
    fun joinActive(call: VoipCall, currentUid: String, role: AccountRole) {
        if (role !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) ||
            !call.canBeJoinedBy(currentUid)
        ) return
        scope.launch {
            runCallAction {
                when (call.participants.firstOrNull { it.userId == currentUid }?.status) {
                    VoipParticipantStatus.PENDING -> controller.acceptAndJoin(call.callId, currentUid)
                    VoipParticipantStatus.DECLINED -> controller.rejoinCall(call.callId)
                    VoipParticipantStatus.ACCEPTED -> controller.joinCall(call)
                    null -> Unit
                }
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

    // A caller ends the room. A member leaving a group only disconnects their own phone.
    fun finish(currentUid: String) {
        scope.launch {
            runCallAction {
                val active = state.value
                val call = when (active) {
                    is VoipSessionState.Connecting -> active.call
                    is VoipSessionState.Active -> active.call
                    is VoipSessionState.Error -> active.call
                    else -> null
                } ?: return@runCallAction
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

// All safe-app roles: draws shared fields and buttons for account forms.

// Gives every long form the same spacing, scrolling, and room above Quick exit.
@Composable
internal fun FormColumn(
    scrollResetKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        if (scrollResetKey != null) scrollState.scrollTo(0)
    }
    Column(
        Modifier.fillMaxSize().oliveScrollbar(scrollState).verticalScroll(scrollState)
            .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content
    )
}

// Shows the app name, page title, and help text, then moves TalkBack focus to the new page title.
@Composable
internal fun Heading(
    title: String,
    subtitle: String,
    appName: String = stringResource(R.string.olive_branch),
    focusKey: Any? = title,
) {
    val focusRequester = remember { FocusRequester() }
    // Moves TalkBack focus to the new form heading after the account step changes.
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    Text(appName, fontFamily = FontFamily.Serif, fontSize = 30.sp, color = OliveGreen)
    Text(
        title,
        modifier = Modifier.focusRequester(focusRequester).focusable().semantics { heading() },
        style = MaterialTheme.typography.headlineMedium
    )
    Text(subtitle, color = Muted)
}

// Warns testers when data is only being saved on the current device.
@Composable
internal fun ModeNotice(session: SessionViewModel) {
    if (session.localMode) Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            stringResource(R.string.local_test_mode_accounts_stay_on_this_installation_firebase_is_not_connected_use_fictional_information_while_testing),
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Announces status changes to TalkBack and shows when the app is busy.
@Composable
internal fun Status(session: SessionViewModel, showMessage: Boolean = true) {
    // Shows the account form message only when there is text to explain.
    if (showMessage) session.message?.let {
        AccessibleMessage(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("session_message")
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        )
    }
    AnimatedVisibility(
        session.busy,
        enter = fadeIn(tween(120)) + expandVertically(),
        exit = fadeOut(tween(100)) + shrinkVertically()
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        }
    }
}

// Builds a text field with consistent validation, password controls, and keyboard behavior.
@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    showSecretToggle: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    onFocus: (() -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val uiText = rememberResourceText()
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription = when {
        numeric -> uiText(R.string.a11y_pin)
        tag.contains("trigger", ignoreCase = true) -> uiText(R.string.a11y_trigger_word)
        else -> uiText(R.string.a11y_password)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { error?.let { error(it) } }
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        enabled = enabled,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        isError = error != null,
        supportingText = error?.let { message ->
            {
                AccessibleMessage(
                    message,
                    modifier = Modifier.testTag("${tag}_error"),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (secret && showSecretToggle) {
            {
                IconButton(
                    onClick = { secretVisible = !secretVisible },
                    modifier = Modifier.testTag("${tag}_visibility")
                ) {
                    Icon(
                        if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (secretVisible) uiText(
                            R.string.ui_hide_value,
                            secretDescription
                        ) else uiText(R.string.ui_show_value, secretDescription)
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false, keyboardType = when {
                numeric -> KeyboardType.NumberPassword
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        )
    )
}

// Signed-in users: connects cover, private note, activity, and privacy screens to their workflows.

internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    // Passes the private-note button action to the session privacy controller.
    fun noteInteraction() = runtime.privacy.noteInteraction()
    // Records that Android detected a screenshot or screen recording.
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    // Records when Android reports that screen recording has stopped.
    fun recordingStopped() = runtime.privacy.recordingStopped()
    // Adds a settings change to the user's private activity history.
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    // Adds or removes an article from the news cover's bookmarks.
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)

    // Opens one category in the private activity-history screen.
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    // Returns from an activity category to the full history list.
    fun activityBack() = runtime.history.activityBack()

    // Clears the note editor's success or error message before the next action.
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    // Creates a private note or updates the note with the matching ID.
    fun savePrivateEntry(
        id: String?, kind: PrivateEntryKind, title: String, body: String,
        incidentDate: String = "", incidentTime: String = "", incidentLocation: String = "",
        incidentLatitude: Double? = null, incidentLongitude: Double? = null,
        attachmentIds: List<String> = emptyList(),
    ) = runtime.notes.savePrivateEntry(
        id, kind, title, body, incidentDate, incidentTime, incidentLocation,
        incidentLatitude, incidentLongitude, attachmentIds,
    )

    // Pins or unpins a private note so important notes stay easy to find.
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    // Moves a private note into the note trash.
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    // Restores a private note from the note trash.
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}

// All app roles: opens and closes the app's root screens and safe-app pages.

internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    // Gives navigation screens the same quick-exit action from the current session.
    fun quickExit() = runtime.navigation.quickExit()
    // Sends the app to the background when the user presses Quick Exit.
    fun background() = runtime.navigation.background()
    // Returns to the previous Safe App screen.
    fun back() = runtime.navigation.back()
    // Opens the requested Safe App page from shared navigation.
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}
