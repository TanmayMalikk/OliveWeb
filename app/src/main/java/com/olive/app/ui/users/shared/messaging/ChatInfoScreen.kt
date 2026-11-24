// All chat roles: shows conversation members and shared items away from the message timeline.
package com.olive.app.ui.users.shared.messaging

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatMembershipAction
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.LocalQuickExit
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.shared.WalkthroughInWindowDialog
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SharedChatItem(
    val key: String,
    val kind: String,
    val id: String,
    val title: String,
    val senderUid: String,
)

/**
 * Reads only the structured share messages so ordinary chat text stays out of the shared-items list.
 *
 * @param messages Collection of messages values to process.
 * @return List of resulting shared chat items items.
 */
private fun sharedChatItems(messages: List<ChatMessage>): List<SharedChatItem> = messages
    .asSequence()
    .filterNot(ChatMessage::unsent)
    .mapNotNull { message ->
        val parts = message.body.split(":", limit = 3)
        val kind = parts.firstOrNull().orEmpty()
        if (kind !in setOf(
                "SHARED_MEDIA", "SHARED_VOICE", "SHARED_NOTE", "SHARED_INCIDENT", "SHARED_SAFETY_PLAN", "SHARED_LOCATION"
            )
        ) return@mapNotNull null
        val id = parts.getOrNull(1).orEmpty()
        SharedChatItem(
            key = "$kind:${id.ifBlank { message.id }}",
            kind = kind,
            id = id,
            title = if (kind == "SHARED_VOICE") "Voice message.m4a" else parts.getOrNull(2).orEmpty(),
            senderUid = message.senderUid,
        )
    }
    .distinctBy(SharedChatItem::key)
    .toList()

/**
 * Displays the chat info dialog overlay.
 *
 * @param session Shared session view model owning application state and actions.
 * @param currentUid Current user ID supplied to [ChatInfoDialog].
 * @param onClose Callback invoked when close changes or occurs.
 * @param onOpenMedia Callback invoked when open media changes or occurs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInfoDialog(
    session: SessionViewModel,
    currentUid: String,
    onClose: () -> Unit,
    onOpenMedia: (SafetyDocument, File) -> Unit,
) {
    DisposableEffect(Unit) {
        session.staffSupport.clearChatInfoErrors()
        onDispose(session.staffSupport::clearChatInfoErrors)
    }
    val conversation = session.messages.activeConversation ?: return
    val quickExit = LocalQuickExit.current
    val personalViewer = session.accountRole in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)
    val sharedItems = sharedChatItems(session.messages.chatMessages)
    val canAddPeople = conversation.closedAt == 0L && when {
        conversation.type == "SUPPORT" -> session.accountRole == AccountRole.USER && conversation.ownerUid == currentUid
        else -> personalViewer
    }
    val ownsOpenTicket = conversation.type == "SUPPORT" && conversation.closedAt == 0L &&
        personalViewer && conversation.ownerUid == currentUid
    var ticketFriends by remember(conversation.id, currentUid, ownsOpenTicket) { mutableStateOf(emptySet<String>()) }
    DisposableEffect(conversation.id, currentUid, ownsOpenTicket) {
        val subscription = if (ownsOpenTicket) session.messages.observeMembershipEvents(conversation.id, currentUid) { events ->
            ticketFriends = events.sortedBy { it.createdAt }.associateBy { it.participantUid }.values
                .filter { it.action == ChatMembershipAction.ADDED }.map { it.participantUid }.toSet()
        } else null
        onDispose { subscription?.close() }
    }
    val candidates = if (canAddPeople) {
        session.messages.friends.filterNot { it.uid in conversation.participantIds }
    } else emptyList()
    val canRequestStaff = personalViewer && conversation.type != "SUPPORT" && conversation.closedAt == 0L
    var requestedConversationId by remember { mutableStateOf("") }
    var showAddPeople by remember { mutableStateOf(false) }

    // Shows accepted Users and Safe Contacts in one picker instead of filling the page with buttons.
    if (showAddPeople) {
        AlertDialog(
            onDismissRequest = { showAddPeople = false },
            title = { Text(stringResource(R.string.chat_add_people)) },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("chat_add_people_list"),
                ) {
                    items(candidates, key = { "picker-${it.uid}" }) { friend ->
                        OutlinedButton(
                            onClick = {
                                session.messages.addConversationParticipant(conversation.id, friend.uid)
                                showAddPeople = false
                            },
                            enabled = session.staffSupport.saving == 0,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                                .testTag("chat_add_${friend.uid}"),
                        ) {
                            Icon(Icons.Outlined.PersonAdd, null)
                            Text(
                                stringResource(R.string.chat_add_person, friend.username),
                                Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showAddPeople = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Opens the new support chat after the live conversation listener receives it from Firebase.
    LaunchedEffect(requestedConversationId, session.messages.conversations) {
        val created = session.messages.conversations.firstOrNull { it.id == requestedConversationId }
            ?: session.messages.findRequestedConversation(requestedConversationId)
        if (created != null) {
            session.messages.openConversation(created)
            onClose()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = ChatPageBackground,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxWidth().testTag("chat_info_screen")
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("chat_info_back")) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close))
                    }
                    Text(
                        stringResource(R.string.chat_info),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 20.dp, top = 16.dp, end = 20.dp, bottom = if (quickExit != null) 92.dp else 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                shape = RoundedCornerShape(22.dp),
                                color = OliveGreen,
                                shadowElevation = 5.dp,
                            ) {
                                Icon(
                                    when {
                                        conversation.type == "SUPPORT" -> Icons.Outlined.SupportAgent
                                        conversation.type == "GROUP" || conversation.participantIds.size > 2 -> Icons.Outlined.Group
                                        else -> Icons.Outlined.PersonOutline
                                    },
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                conversation.displayName(currentUid, session.accountRole),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                pluralStringResource(R.plurals.chat_member_count, conversation.participantIds.size, conversation.participantIds.size),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        HorizontalDivider(color = ChatBorder)
                        Text(stringResource(R.string.chat_members).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                    }
                    items(conversation.participantIds, key = { "member-$it" }) { uid ->
                        val organizationMember = conversation.type == "SUPPORT" && personalViewer &&
                            uid in setOf(conversation.staffUid, conversation.adminUid).filter(String::isNotBlank)
                        val anonymousPersonalMember = conversation.type == "SUPPORT" &&
                            session.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) &&
                            uid !in setOf(conversation.staffUid, conversation.adminUid).filter(String::isNotBlank)
                        val name = when {
                            conversation.isChatAdmin(uid) -> stringResource(R.string.support_admin_chat_name)
                            organizationMember -> stringResource(R.string.chat_organization_member)
                            anonymousPersonalMember -> if (uid == conversation.ownerUid) {
                                "Ticket ${conversation.ticketNumber()}"
                            } else {
                                "Friend"
                            }
                            else -> conversation.participantUsernames[uid].orEmpty().ifBlank { stringResource(R.string.account) }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, ChatBorder),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().testTag("chat_member_$uid"),
                        ) { ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = if (uid == currentUid) ({ Text(stringResource(R.string.you)) }) else null,
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(42.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = CircleShape,
                                        color = OliveGreen.copy(alpha = .14f),
                                    ) {}
                                    Text(
                                        name.firstOrNull()?.uppercase().orEmpty(),
                                        color = OliveGreen,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            },
                            trailingContent = if (uid != currentUid && when {
                                ownsOpenTicket -> uid in ticketFriends && uid != conversation.staffUid && uid != conversation.adminUid
                                else -> conversation.type == "GROUP" && conversation.closedAt == 0L &&
                                    conversation.ownerUid == currentUid && conversation.participantIds.size > 2
                            }) ({
                                IconButton(
                                    onClick = {
                                        session.messages.removeConversationParticipant(conversation.id, uid)
                                    },
                                    enabled = session.staffSupport.saving == 0,
                                    modifier = Modifier.testTag("chat_remove_$uid"),
                                ) {
                                    Icon(
                                        Icons.Outlined.PersonRemove,
                                        stringResource(R.string.chat_remove_person, name),
                                    )
                                }
                            }) else null,
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
                        ) }
                    }

                    session.staffSupport.addParticipantError?.let { message ->
                        item {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("chat_add_participant_error"),
                            )
                        }
                    }
                    if (canAddPeople) {
                        item {
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showAddPeople = true },
                                enabled = candidates.isNotEmpty() && session.staffSupport.saving == 0,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                    .testTag("chat_add_people"),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.PersonAdd, null)
                                    Text(
                                        stringResource(R.string.chat_add_people),
                                        Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (canRequestStaff) {
                        item {
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.chat_request_staff_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                            )
                            Text(
                                stringResource(R.string.chat_request_staff_description),
                                color = Muted,
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = {
                                    session.messages.requestStaffForConversation(conversation.id) {
                                        requestedConversationId = it
                                    }
                                },
                                enabled = session.staffSupport.saving == 0,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = ChatDangerRed,
                                ),
                                border = BorderStroke(1.dp, ChatDangerRed.copy(alpha = .45f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                    .testTag("chat_request_staff"),
                            ) {
                                Icon(Icons.Outlined.SupportAgent, null)
                                Text(
                                    stringResource(R.string.chat_request_staff_button),
                                    Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        session.staffSupport.requestStaffError?.let { message ->
                            item {
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("chat_request_staff_error"),
                                )
                            }
                        }
                    }

                    item {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.chat_shared_items),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                        )
                    }
                    if (sharedItems.isEmpty()) item {
                        Text(stringResource(R.string.chat_no_shared_items), color = Muted)
                    }
                    items(sharedItems, key = SharedChatItem::key) { item ->
                        when (item.kind) {
                            "SHARED_MEDIA", "SHARED_VOICE" -> SharedMediaMessage(
                                item.id,
                                item.title,
                                session,
                                item.senderUid == currentUid,
                                onOpenMedia,
                                onLightSurface = true,
                            )
                            "SHARED_NOTE", "SHARED_INCIDENT" -> Button(
                                onClick = {
                                    onClose()
                                    session.messages.openSharedPrivateEntry(item.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.FolderShared, null)
                                Text(
                                    item.title.ifBlank {
                                        stringResource(
                                            if (item.kind == "SHARED_INCIDENT") R.string.shared_incident_report
                                            else R.string.shared_private_note
                                        )
                                    },
                                    Modifier.padding(start = 8.dp),
                                )
                            }
                            "SHARED_SAFETY_PLAN" -> Button(
                                onClick = {
                                    onClose()
                                    session.navigation.openSafePage(SafePage.SafetyPlan)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.shared_safety_plan)) }
                            "SHARED_LOCATION" -> Button(
                                onClick = {
                                    onClose()
                                    session.navigation.openSafePage(SafePage.Location)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.LocationOn, null)
                                Text(stringResource(R.string.shared_live_location), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (quickExit != null) {
                FloatingQuickExit(
                    onClick = quickExit,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                )
            }
        }
    }
}

/**
 * Lets the user copy, share, or refresh their invite and indicates when it becomes unavailable.
 *
 * @param uid The account that owns the invite.
 * @param createInvite Loads or creates an invite. A refresh requests a replacement code.
 * @param observeUnavailable Subscribes to changes that invalidate the displayed code.
 * @param onDismiss Closes the dialog.
 * @param inWindow Whether the dialog is hosted in the current window.
 */
@Composable
internal fun FriendInviteDialog(
    uid: String,
    createInvite: suspend (refresh: Boolean) -> FriendInvite,
    observeUnavailable: (String, () -> Unit) -> ChatSubscription,
    onDismiss: () -> Unit,
    inWindow: Boolean = false,
) {
    val context = LocalContext.current
    val openSystem = LocalSafeSystemLauncher.current
    val inviteLabel = stringResource(R.string.friend_invite)
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<FriendInvite?>(null) }
    var busy by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /**
     * Loads an invite and updates the dialog with the invite or an error.
     *
     * @param refresh Whether to replace the current invite.
     */
    suspend fun generate(refresh: Boolean) {
        busy = true
        error = null
        invite = null
        unavailable = false
        try { invite = createInvite(refresh); unavailable = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(uid) { generate(false) }
    LaunchedEffect(invite) {
        invite?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); unavailable = true }
    }
    DisposableEffect(uid, invite?.code) {
        val subscription = invite?.let { current ->
            observeUnavailable(current.code) {
                if (invite?.code == current.code) unavailable = true
            }
        }
        onDispose { subscription?.close() }
    }
    /**
     * Copies invite information to the system clipboard.
     *
     * @param value The invite code or link to copy.
     */
    fun copy(value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(inviteLabel, value))
    }
    val bodyContent: @Composable () -> Unit = {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val message = if (unavailable) stringResource(R.string.friend_invite_unavailable) else error
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            invite?.takeUnless { unavailable }?.let { value ->
                val expiry = Date(value.expiresAt)
                val expiresAt = remember(context, value.expiresAt) {
                    "${DateFormat.getMediumDateFormat(context).format(expiry)} · ${DateFormat.getTimeFormat(context).format(expiry)}"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.friend_invite_code), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text(
                                value.code,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            stringResource(R.string.friend_invite_expires, expiresAt),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                OliveButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, value.link)
                        }
                        openSystem(Intent.createChooser(intent, inviteLabel))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.friend_invite_share))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OliveOutlinedButton(onClick = { copy(value.code) }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friend_invite_copy_code))
                    }
                    OliveOutlinedButton(onClick = { copy(value.link) }) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friend_invite_copy_link))
                    }
                }
            }
            HorizontalDivider()
            OliveTextButton(
                onClick = { scope.launch { generate(true) } },
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.friend_invite_refresh))
            }
            if (invite != null && !unavailable) {
                Text(
                    stringResource(R.string.friend_invite_once),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (inWindow) {
        WalkthroughInWindowDialog(
            dialogTargetId = "friend_invite_dialog",
            title = { Text(inviteLabel) },
            onDismiss = onDismiss,
            confirmButton = { OliveTextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_invite_done)) } },
            text = bodyContent,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(inviteLabel) },
            text = bodyContent,
            confirmButton = {
                OliveTextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_invite_done)) }
            },
        )
    }
}
