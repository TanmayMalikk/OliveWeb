// All safe-app roles: shows messages, attachments, and chat actions.
package com.olive.app.ui.users.shared.messaging

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.olive.app.R
import com.olive.app.data.SafetyDocument
import com.olive.app.data.*
import com.olive.app.data.messaging.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.camera.AttachmentPicker
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private sealed interface ConversationTimelineItem {
    val key: String
    val timestamp: Long

    data class Message(val value: com.olive.app.data.ChatMessage) : ConversationTimelineItem {
        override val key = "message-${value.id}"
        override val timestamp = value.sentAt
    }

    data class Call(val value: VoipCall) : ConversationTimelineItem {
        override val key = "call-${value.callId}"
        override val timestamp = value.endedAt.takeIf { it > 0 } ?: value.createdAt
    }
}

@OptIn(ExperimentalFoundationApi::class)
// UI sizing/color: chat bubbles cap at 340dp, sent bubbles use OliveGreen, and the header stays at least 76dp tall.
@Composable
internal fun ChatPane(
    session: SessionViewModel, currentUid: String, showBack: Boolean,
    exit: (() -> Unit)?, modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val active = session.messages.activeConversation ?: return
    val callState by session.calls.state.collectAsState()
    val callAllowed = canCallFromConversation(session.accountRole, active, currentUid)
    val canAttachMedia = session.accountRole !in setOf(
        com.olive.app.data.AccountRole.STAFF,
        com.olive.app.data.AccountRole.ADMIN,
    )
    var draft by rememberSaveable(active.id) { mutableStateOf("") }
    var showAttachmentPicker by rememberSaveable(active.id) { mutableStateOf(false) }
    var showChatInfo by rememberSaveable(active.id) { mutableStateOf(false) }
    var openedMedia by remember { mutableStateOf<Pair<SafetyDocument, File>?>(null) }
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardVisible = imeBottom > 0
    val context = LocalContext.current
    val callHistory = session.calls.historyFor(active.id, session.accountRole)
    val timeline = remember(session.messages.chatMessages, callHistory) {
        (session.messages.chatMessages.map(ConversationTimelineItem::Message) +
            callHistory.map(ConversationTimelineItem::Call))
            .sortedWith(compareBy(ConversationTimelineItem::timestamp, ConversationTimelineItem::key))
    }
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        session.endCallPermissionRequest()
        val action = permissionAction
        permissionAction = null
        if (session.screen == SessionScreen.Safe &&
            session.messages.activeConversation?.id == active.id
        ) {
            if (granted) action?.invoke()
            else session.calls.report(uiText(R.string.ui_microphone_permission_required))
        }
    }
    val withMicrophonePermission: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) action() else {
            permissionAction = action
            session.beginCallPermissionRequest()
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(currentUid, session.coverUsername, session.localMode) {
        session.documents.load(currentUid, session.coverUsername, session.localMode)
    }
    LaunchedEffect(session.messages.chatMessages.size, session.messages.chatMessages.lastOrNull()?.body) {
        if (session.messages.chatMessages.isNotEmpty()) listState.animateScrollToItem(session.messages.chatMessages.lastIndex)
    }
    // Keep the newest message visible when the chat grows.
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible && session.messages.chatMessages.isNotEmpty()) {
            delay(120.milliseconds)
            listState.animateScrollToItem(session.messages.chatMessages.lastIndex)
        }
    }
    openedMedia?.let { (document, file) ->
        SafetyDocumentViewer(document, file) {
            file.delete()
            openedMedia = null
        }
        return
    }
    Box(modifier.background(ChatPageBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 78.dp)
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showBack) IconButton(
                    onClick = session.messages::closeConversation,
                    modifier = Modifier.testTag("messages_conversation_back")
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.all_conversations))
                }
                Box(
                    Modifier.size(44.dp).background(OliveGreen, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            active.type == "SUPPORT" -> Icons.Outlined.SupportAgent
                            active.type == "GROUP" || active.participantIds.size > 2 -> Icons.Outlined.Group
                            else -> Icons.Outlined.PersonOutline
                        },
                        null,
                        tint = Color.White,
                    )
                    Box(
                        Modifier.size(10.dp).background(OnlineIndicatorGreen, CircleShape)
                            .align(Alignment.BottomEnd),
                    )
                }
                Column(
                    Modifier.weight(1f).clickable { showChatInfo = true }
                        .testTag("open_chat_info")
                        .semantics { contentDescription = uiText(R.string.chat_open_info) },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        active.displayName(
                            currentUid,
                            session.accountRole,
                            active.anonymousStaffDisplayName(
                                uiText(R.string.support_chat_name),
                                uiText(R.string.support_ticket_consultation),
                                uiText(R.string.support_ticket_support),
                                uiText(R.string.support_ticket_escalation),
                            ),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(
                        if (active.type == "SUPPORT") {
                            stringResource(R.string.support_conversation)
                        } else {
                            stringResource(R.string.chat_header_member_count, active.participantIds.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    if (active.type == "SUPPORT" && session.accountRole in setOf(
                            com.olive.app.data.AccountRole.STAFF,
                            com.olive.app.data.AccountRole.ADMIN,
                        )
                    ) Text(
                        stringResource(
                            when (active.supportTag) {
                                SUPPORT_TAG_PLAN_CONSULT -> R.string.support_filter_plan_consults
                                SUPPORT_TAG_NEEDS_ADMIN -> R.string.support_filter_needs_admin
                                else -> R.string.support_filter_support
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = OliveGreen,
                    )
                }
                if (callAllowed && callState == VoipSessionState.Idle) IconButton(
                    onClick = {
                        withMicrophonePermission {
                            session.calls.start(active, currentUid, session.accountRole)
                        }
                    },
                    modifier = Modifier.testTag("start_voice_call"),
                ) {
                    Icon(Icons.Outlined.Call, stringResource(R.string.ui_start_voice_call))
                }
                if (exit != null) FilledTonalButton(
                    onClick = exit,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 44.dp)
                        .testTag(if (canAttachMedia) "quick_exit" else "chat_exit_to_portal")
                ) { Text(uiText(R.string.ui_exit)) }
            }
            HorizontalDivider()
            if (session.accountRole == com.olive.app.data.AccountRole.STAFF &&
                active.type == "SUPPORT" && active.closedAt == 0L && active.adminUid.isBlank()) {
                OutlinedButton(
                    onClick = { session.messages.requestAdminHelp(active.id) },
                    enabled = session.staffSupport.saving == 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .testTag("request_admin_help"),
                ) { Text(stringResource(R.string.request_admin_help)) }
            }
            if (session.accountRole in setOf(
                    com.olive.app.data.AccountRole.STAFF,
                    com.olive.app.data.AccountRole.ADMIN,
                ) &&
                active.type == "SUPPORT" && active.closedAt == 0L) {
                OutlinedButton(
                    onClick = { session.messages.closeSupportChat(active.id) },
                    enabled = session.staffSupport.saving == 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("close_support_chat"),
                ) { Text(stringResource(R.string.support_close_chat)) }
            }
            session.staffSupport.error?.let { message ->
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            }
            Column(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (timeline.isEmpty()) item {
                        Text(
                            stringResource(R.string.no_messages_yet_say_hello_when_you_are_ready),
                            color = Muted,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    items(timeline, key = ConversationTimelineItem::key) { timelineItem ->
                        if (timelineItem is ConversationTimelineItem.Call) {
                            val call = timelineItem.value
                            CallHistoryRow(
                                call = call,
                                canJoin = callState == VoipSessionState.Idle &&
                                    call.canBeJoinedBy(currentUid),
                                onJoin = {
                                    withMicrophonePermission {
                                        session.calls.joinActive(
                                            call,
                                            currentUid,
                                            session.accountRole,
                                        )
                                    }
                                },
                            )
                            return@items
                        }
                        val chat = (timelineItem as ConversationTimelineItem.Message).value
                        val mine = chat.senderUid == currentUid
                        var menuOpen by remember(chat.id) { mutableStateOf(false) }
                        Row(
                            Modifier.animateItem().fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (mine) ChatSentBubble else ChatIncomingBubble,
                                contentColor = if (mine) Color.White else Color.Unspecified,
                                shape = RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = if (mine) 18.dp else 5.dp,
                                    bottomEnd = if (mine) 5.dp else 18.dp,
                                ),
                                tonalElevation = 1.dp,
                                shadowElevation = 1.dp,
                                border = if (mine) null else BorderStroke(1.dp, ChatBorder),
                                modifier = Modifier.fillMaxWidth(.84f).widthIn(max = 360.dp).then(
                                    if (mine && !chat.unsent) Modifier.combinedClickable(
                                        onClickLabel = uiText(R.string.a11y_message_actions),
                                        onClick = { menuOpen = true },
                                        onLongClickLabel = uiText(R.string.a11y_message_actions),
                                        onLongClick = { menuOpen = true },
                                    ) else Modifier
                                )
                            ) {
                                Row(
                                    Modifier.padding(
                                        start = 15.dp,
                                        end = 6.dp,
                                        top = 11.dp,
                                        bottom = 10.dp
                                    ), verticalAlignment = Alignment.Top
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (mine) session.coverUsername else active.senderDisplayName(
                                                chat.senderUid,
                                                chat.senderUsername,
                                                session.accountRole,
                                                uiText(R.string.support_chat_name),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (mine) Color.White.copy(alpha = .78f) else Muted
                                        )
                                        ChatMessageBody(
                                            chat,
                                            session,
                                            mine
                                        ) { document, file -> openedMedia = document to file }
                                        if (chat.sentAt > 0) Text(
                                            DateTimeFormatter.ofPattern(uiText(R.string.ui_mmm_d_hh_mm))
                                                .withZone(ZoneId.systemDefault())
                                                .format(Instant.ofEpochMilli(chat.sentAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (mine) Color.White.copy(alpha = .7f) else Muted
                                        )
                                        if (mine && chat.deliveryStatus != MessageDeliveryStatus.SENT) {
                                            val retryable = chat.deliveryStatus in setOf(
                                                MessageDeliveryStatus.FAILED_RETRYABLE,
                                                MessageDeliveryStatus.FAILED_PERMANENT,
                                            )
                                            TextButton(
                                                onClick = { session.messages.retryMessage(chat) },
                                                enabled = retryable,
                                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                                modifier = Modifier.testTag("message_status_${chat.id}")
                                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        when (chat.deliveryStatus) {
                                                            MessageDeliveryStatus.QUEUED -> R.string.message_delivery_queued
                                                            MessageDeliveryStatus.SENDING -> R.string.message_delivery_sending
                                                            MessageDeliveryStatus.FAILED_RETRYABLE -> R.string.message_delivery_retrying
                                                            MessageDeliveryStatus.FAILED_PERMANENT -> R.string.message_delivery_failed_retry
                                                            MessageDeliveryStatus.SENT -> R.string.message_delivery_sent
                                                        }
                                                    ),
                                                    color = Color.White.copy(alpha = if (retryable) 1f else .75f),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                    if (mine && !chat.unsent && chat.deliveryStatus == MessageDeliveryStatus.SENT) Box {
                                        IconButton(
                                            onClick = { menuOpen = true },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.MoreVert,
                                                uiText(R.string.a11y_message_actions)
                                            )
                                        }
                                        DropdownMenu(menuOpen, { menuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.unsend_message)) },
                                                onClick = {
                                                    menuOpen = false
                                                    session.messages.unsendMessage(chat)
                                                })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Status(session)
                if (active.closedAt == 0L) Surface(
                    color = Color.White,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, ChatBorder),
                ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canAttachMedia) {
                        IconButton(
                            onClick = { showAttachmentPicker = true },
                            enabled = !session.messages.busy,
                            modifier = Modifier.testTag("attach_private_entry")
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                stringResource(R.string.attach_secure_entry)
                            )
                        }
                    }
                    OutlinedTextField(
                        draft,
                        { if (it.length <= 2_000) draft = it },
                        label = { Text(stringResource(R.string.message)) },
                        modifier = Modifier.weight(1f).testTag("message_body")
                            .keepVisibleWhileTyping(),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                    )
                    FilledIconButton(
                        onClick = { session.messages.sendMessage(draft) { draft = "" } },
                        enabled = draft.isNotBlank() && !session.messages.busy &&
                            (active.type != "SUPPORT" || active.participantIds.size >= 2),
                        modifier = Modifier.size(50.dp).testTag("send_message")
                    ) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send)) }
                }
                }
            }
        }
        if (exit != null && session.messages.chatQuickExitHintVisible && session.screen == SessionScreen.Safe && !session.privacyBlackout) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(
                    with(density) { (-14).dp.roundToPx() },
                    with(density) { 82.dp.roundToPx() }),
                onDismissRequest = session.messages::dismissChatQuickExitHint,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = OliveGreen,
                    contentColor = Color.White,
                    shadowElevation = 14.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .9f)),
                    modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
                        .testTag("chat_quick_exit_hint").semantics {
                            paneTitle = uiText(R.string.ui_quick_exit_moved_here)
                        }) {
                    Column(
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                uiText(R.string.ui_quick_exit_moved_here),
                                fontSize = 20.sp,
                                lineHeight = 25.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Outlined.NorthEast,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Text(
                            uiText(R.string.ui_while_you_are_in_a_chat_use_exit_in_the_top_right_corner),
                            fontSize = 16.sp, lineHeight = 22.sp, color = Color.White
                        )
                        Button(
                            onClick = session.messages::dismissChatQuickExitHint,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = OliveGreen
                            ),
                            modifier = Modifier.align(Alignment.End).heightIn(min = 46.dp)
                                .testTag("dismiss_chat_quick_exit_hint")
                        ) {
                            Text(uiText(R.string.ui_okay), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
        if (showAttachmentPicker && canAttachMedia) {
            AttachmentPicker(session) { showAttachmentPicker = false }
        }
        if (showChatInfo) ChatInfoDialog(
            session = session,
            currentUid = currentUid,
            onClose = { showChatInfo = false },
            onOpenMedia = { document, file -> openedMedia = document to file },
        )
        session.calls.message?.takeIf {
            callAllowed && callState == VoipSessionState.Idle
        }?.let { message ->
            AlertDialog(
                onDismissRequest = session.calls::clearMessage,
                title = { Text(stringResource(R.string.ui_voice_call)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = session.calls::clearMessage) { Text(stringResource(R.string.ui_okay)) }
                },
            )
        }
    }
}

// Shows one voice call status card in the conversation timeline.
@Composable
internal fun CallHistoryRow(
    call: VoipCall,
    canJoin: Boolean = false,
    onJoin: () -> Unit = {},
) {
    val label = stringResource(
        when (call.status) {
            VoipCallStatus.MISSED -> R.string.ui_missed_call
            VoipCallStatus.DECLINED -> R.string.ui_call_declined
            VoipCallStatus.ENDED -> R.string.ui_call_ended
            VoipCallStatus.ACTIVE -> R.string.ui_call_in_progress
        }
    )
    val activeColor = MaterialTheme.colorScheme.primaryContainer
    Surface(
        color = if (call.status == VoipCallStatus.ACTIVE) activeColor
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
            .testTag("call_history_${call.callId}")
            .then(if (call.status == VoipCallStatus.ACTIVE && canJoin) {
                Modifier.clickable(onClick = onJoin)
            } else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Call, null, tint = OliveGreen)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                val timestamp = call.endedAt.takeIf { it > 0 } ?: call.createdAt
                if (timestamp > 0) Text(
                    DateTimeFormatter.ofPattern("MMM d, HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (call.status == VoipCallStatus.ACTIVE && canJoin) {
                FilledTonalButton(
                    onClick = onJoin,
                    modifier = Modifier.testTag("join_call_${call.callId}"),
                ) {
                    Text(stringResource(R.string.ui_join_call))
                }
            }
        }
    }
}
