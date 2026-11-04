package com.olive.app.ui.users.shared.messaging

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.data.messaging.otherPartyName
import kotlinx.coroutines.delay

private val CallBackground = Color(0xFF101A17)
private val CallControlBackground = Color(0xFF2A3733)
private val CallGreen = Color(0xFF2E7D5B)
private val CallRed = Color(0xFFD4423C)

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
) = FullScreenCallDialog(onBack = onDecline) {
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
) = FullScreenCallDialog(onBack = onFinish) {
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
            delay(1_000)
            elapsedSeconds++
        }
    }
    val status = when (state) {
        is VoipSessionState.Connecting -> stringResource(R.string.ui_calling)
        is VoipSessionState.Active -> if (remoteJoined) formatCallDuration(elapsedSeconds)
            else stringResource(R.string.ui_calling)
        is VoipSessionState.Leaving -> stringResource(R.string.ui_end_call)
        is VoipSessionState.Error -> state.message
        VoipSessionState.Idle -> ""
    }
    CallIdentity(
        name = call?.otherPartyName(currentUid).orEmpty()
            .ifBlank { stringResource(R.string.ui_voice_call) },
        status = status,
        isError = state is VoipSessionState.Error,
    )
    Spacer(Modifier.weight(1f))
    if (active != null) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        Spacer(Modifier.height(36.dp))
    }
    if (active != null || state is VoipSessionState.Connecting || state is VoipSessionState.Error) {
        CallAction(
            label = stringResource(
                when {
                    state is VoipSessionState.Error -> R.string.close
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
private fun CallIdentity(name: String, status: String, isError: Boolean = false) {
    Box(
        Modifier.size(116.dp).background(CallGreen.copy(alpha = .28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase().orEmpty(),
            color = Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.SemiBold,
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
