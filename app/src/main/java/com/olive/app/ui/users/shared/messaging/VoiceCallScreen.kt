// Voice calls and private contact note dialogs for safe-app messaging.
package com.olive.app.ui.users.shared.messaging

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.data.messaging.otherPartyName
import com.olive.app.ui.shared.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal fun formatCallDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

@Composable
internal fun IncomingCallDialog(
    invitation: VoipInvitation,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onQuickExit: () -> Unit,
) = FullScreenCallDialog(onBack = onDecline) {
    QuickExitCallButton(onQuickExit, Modifier.align(Alignment.End))
    Spacer(Modifier.height(20.dp))
    CallIdentity(
        name = invitation.name.ifBlank { stringResource(R.string.ui_voice_call) },
        status = stringResource(R.string.ui_incoming_call),
    )
    Spacer(Modifier.weight(1f))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallAction(
            label = stringResource(R.string.decline),
            icon = { Icon(Icons.Outlined.CallEnd, null) },
            color = CallRed,
            tag = "decline_voice_call",
            onClick = onDecline,
        )
        CallAction(
            label = stringResource(R.string.accept),
            icon = { Icon(Icons.Outlined.Call, null) },
            color = CallGreen,
            tag = "accept_voice_call",
            onClick = onAccept,
        )
    }
}

@Composable
internal fun ActiveCallDialog(
    state: VoipSessionState,
    currentUid: String,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onMinimize: () -> Unit,
    onQuickExit: () -> Unit,
) = FullScreenCallDialog(onBack = onMinimize) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.olive_branch),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onMinimize,
            colors = ButtonDefaults.buttonColors(containerColor = CallControlBackground),
            modifier = Modifier.size(48.dp).testTag("minimize_voice_call"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                stringResource(R.string.ui_minimize_call),
            )
        }
        Spacer(Modifier.width(10.dp))
        QuickExitCallButton(onQuickExit)
    }
    Spacer(Modifier.height(20.dp))
    val active = state as? VoipSessionState.Active
    val call = when (state) {
        is VoipSessionState.Connecting -> state.call
        is VoipSessionState.Active -> state.call
        is VoipSessionState.Leaving -> state.call
        is VoipSessionState.Error -> state.call
        VoipSessionState.Idle -> null
    }
    var elapsedSeconds by remember(call?.callId, active != null) { mutableLongStateOf(0) }
    val remoteJoined = active != null &&
        (active.call.callerId != currentUid || active.remoteParticipantCount > 0)
    LaunchedEffect(active?.call?.callId, remoteJoined) {
        if (!remoteJoined) return@LaunchedEffect
        while (true) {
            delay(1_000.milliseconds)
            elapsedSeconds++
        }
    }
    val isGroupCall = (call?.participants?.size ?: 0) > 2
    val connectedPeople = if (active == null) 1 else active.remoteParticipantCount + 1
    val status = when (state) {
        is VoipSessionState.Connecting -> stringResource(R.string.ui_calling)
        is VoipSessionState.Active -> if (remoteJoined) {
            if (isGroupCall) stringResource(
                R.string.ui_group_call_status,
                connectedPeople,
                formatCallDuration(elapsedSeconds),
            ) else formatCallDuration(elapsedSeconds)
        } else stringResource(R.string.ui_calling)
        is VoipSessionState.Leaving -> stringResource(R.string.ui_end_call)
        is VoipSessionState.Error -> state.message
        VoipSessionState.Idle -> ""
    }
    CallIdentity(
        name = if (isGroupCall) {
            call?.name.orEmpty().ifBlank { stringResource(R.string.ui_group_voice_call) }
        } else {
            call?.otherPartyName(currentUid).orEmpty()
                .ifBlank { stringResource(R.string.ui_voice_call) }
        },
        status = status,
        isError = state is VoipSessionState.Error,
        isGroup = isGroupCall,
    )
    if (active != null && isGroupCall) {
        Spacer(Modifier.height(28.dp))
        AudioWaveform()
    }
    Spacer(Modifier.weight(1f))
    if (active != null || state is VoipSessionState.Connecting || state is VoipSessionState.Error) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active != null) {
                CallAction(
                    label = stringResource(if (active.microphoneMuted) R.string.ui_unmute else R.string.ui_mute),
                    icon = {
                        Icon(if (active.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic, null)
                    },
                    selected = active.microphoneMuted,
                    tag = "mute_voice_call",
                    onClick = { onMute(!active.microphoneMuted) },
                )
                CallAction(
                    label = stringResource(
                        if (active.speakerEnabled) R.string.ui_speaker_off else R.string.ui_speaker
                    ),
                    icon = {
                        Icon(
                            if (active.speakerEnabled) Icons.AutoMirrored.Outlined.VolumeUp
                            else Icons.AutoMirrored.Outlined.VolumeOff,
                            null,
                        )
                    },
                    selected = active.speakerEnabled,
                    tag = "speaker_voice_call",
                    onClick = { onSpeaker(!active.speakerEnabled) },
                )
            }
            CallAction(
                label = stringResource(
                    when {
                        state is VoipSessionState.Error -> R.string.close
                        isGroupCall && remoteJoined -> R.string.ui_leave_call
                        call?.callerId == currentUid -> R.string.ui_end_call
                        else -> R.string.ui_leave_call
                    }
                ),
                icon = { Icon(Icons.Outlined.CallEnd, null) },
                color = CallRed,
                tag = "finish_voice_call",
                onClick = onFinish,
            )
        }
    }
}

// Shows a small movable call button while the user works elsewhere inside Olive Branch.
@Composable
internal fun DraggableCallBubble(
    state: VoipSessionState,
    onOpen: () -> Unit,
) {
    val label = stringResource(R.string.ui_return_to_call)
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }
    var positionReady by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().onSizeChanged { parentSize = it }) {
        // Starts the bubble near the right edge, then keeps every drag inside the visible app window.
        LaunchedEffect(parentSize, bubbleSize) {
            if (!positionReady && parentSize.width > 0 && bubbleSize.width > 0) {
                position = Offset(
                    x = (parentSize.width - bubbleSize.width - 24).coerceAtLeast(0).toFloat(),
                    y = ((parentSize.height - bubbleSize.height) / 2).coerceAtLeast(0).toFloat(),
                )
                positionReady = true
            } else if (positionReady) {
                position = position.coerceInside(parentSize, bubbleSize)
            }
        }

        Surface(
            onClick = onOpen,
            modifier = Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(72.dp)
                .onSizeChanged { bubbleSize = it }
                .pointerInput(parentSize, bubbleSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        position = (position + dragAmount).coerceInside(parentSize, bubbleSize)
                    }
                }
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }
                .testTag("minimized_voice_call"),
            shape = CircleShape,
            color = CallBackground,
            shadowElevation = 10.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Call,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
                if (state is VoipSessionState.Active) Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(12.dp)
                        .background(CallGreen, CircleShape),
                )
            }
        }
    }
}

// Prevents a dragged call bubble from covering the system edges or leaving the app window.
private fun Offset.coerceInside(parent: IntSize, child: IntSize): Offset = Offset(
    x = x.coerceIn(0f, (parent.width - child.width).coerceAtLeast(0).toFloat()),
    y = y.coerceIn(0f, (parent.height - child.height).coerceAtLeast(0).toFloat()),
)

@Composable
private fun QuickExitCallButton(onQuickExit: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onQuickExit,
        colors = ButtonDefaults.buttonColors(containerColor = CallRed),
        modifier = modifier.testTag("call_quick_exit"),
    ) {
        Icon(Icons.Outlined.Close, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.quick_exit))
    }
}

@Composable
private fun FullScreenCallDialog(
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(enabled = true, onBack = onBack)
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
        Surface(Modifier.fillMaxSize(), color = CallBackground) {
            Column(
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
private fun CallIdentity(
    name: String,
    status: String,
    isError: Boolean = false,
    isGroup: Boolean = false,
) {
    Box(
        Modifier.size(132.dp)
            .background(CallGreen.copy(alpha = .14f), CircleShape)
            .border(3.dp, CallGreen, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isGroup) Icons.Outlined.Group else Icons.Outlined.Headphones,
            null,
            tint = Color.White,
            modifier = Modifier.size(62.dp),
        )
    }
    Spacer(Modifier.height(28.dp))
    Text(
        name,
        color = Color.White,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        status,
        color = if (isError) MaterialTheme.colorScheme.error else Color.White.copy(alpha = .75f),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun AudioWaveform() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(18, 34, 25, 46, 30, 39, 20).forEach { height ->
            Box(
                Modifier.width(5.dp).height(height.dp)
                    .background(CallGreen, CircleShape),
            )
        }
    }
}

@Composable
private fun CallAction(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    color: Color = CallControlBackground,
    selected: Boolean = false,
    tag: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(72.dp).testTag(tag),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) Color.White else color,
                contentColor = if (selected) CallBackground else Color.White,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { icon() }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
