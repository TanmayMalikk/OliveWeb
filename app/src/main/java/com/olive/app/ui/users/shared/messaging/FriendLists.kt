// User and safe-contact accounts: shows friend search results, requests, and saved friends.
package com.olive.app.ui.users.shared.messaging

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.media.pickerMediaKind
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.SecureFileOpeningIndicator
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.documents.BlurredDocumentPreviewSurface
import com.olive.app.ui.users.user.documents.NoteAttachmentPreviewStrip
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch


/**
 * Keeps the four friend-related lists together while the parent screen handles chat lists.
 *
 * @receiver The lazy list scope that receives the item definitions.
 * @param session Shared session view model owning application state and actions.
 * @param pendingExpanded Whether to apply pending expanded.
 * @param onPendingExpandedChange Callback invoked when pending expanded change changes or occurs.
 * @param outgoingExpanded Whether to apply outgoing expanded.
 * @param onOutgoingExpandedChange Callback invoked when outgoing expanded change changes or occurs.
 * @param friendsExpanded Whether to apply friends expanded.
 * @param onFriendsExpandedChange Callback invoked when friends expanded change changes or occurs.
 * @param onNoteTargets Callback invoked when note targets changes or occurs.
 */
internal fun LazyListScope.friendLists(
    session: SessionViewModel,
    pendingExpanded: Boolean,
    onPendingExpandedChange: (Boolean) -> Unit,
    outgoingExpanded: Boolean,
    onOutgoingExpandedChange: (Boolean) -> Unit,
    friendsExpanded: Boolean,
    onFriendsExpandedChange: (Boolean) -> Unit,
    onNoteTargets: (List<DirectoryUser>) -> Unit,
) {
    // Show incoming requests only when someone is waiting for an answer.
    if (session.messages.incomingFriendRequests.isNotEmpty()) {
        item {
            val description = stringResource(
                if (pendingExpanded) R.string.support_expanded else R.string.support_collapsed
            )
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onPendingExpandedChange(!pendingExpanded) }
                    .semantics {
                        stateDescription = description
                        liveRegion = LiveRegionMode.Polite
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (pendingExpanded) Icons.Outlined.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                )
                Text(
                    stringResource(R.string.incoming_friend_requests),
                    style = MaterialTheme.typography.titleMedium,
                )
                Badge(Modifier.padding(start = 8.dp)) {
                    Text(session.messages.incomingFriendRequests.size.toString())
                }
            }
        }
        if (pendingExpanded) {
            items(session.messages.incomingFriendRequests, key = { "incoming-${it.id}" }) { request ->
                IncomingRequestRow(session, request)
            }
        }
    }

    item {
        val description = stringResource(
            if (outgoingExpanded) R.string.support_expanded else R.string.support_collapsed
        )
        Row(
            Modifier.fillMaxWidth()
                .clickable { onOutgoingExpandedChange(!outgoingExpanded) }
                .semantics { stateDescription = description }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (outgoingExpanded) Icons.Outlined.KeyboardArrowDown
                else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
            Text(
                stringResource(R.string.outgoing_friend_requests),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    // Expand or collapse the list of sent friend requests.
    if (outgoingExpanded) {
        if (session.messages.outgoingFriendRequests.isEmpty()) {
            item { Text(stringResource(R.string.no_outgoing_requests), color = Muted) }
        }
        items(session.messages.outgoingFriendRequests, key = { "outgoing-${it.id}" }) { request ->
            OutgoingRequestRow(session, request)
        }
    }

    item {
        val description = stringResource(
            if (friendsExpanded) R.string.support_expanded else R.string.support_collapsed
        )
        Row(
            Modifier.fillMaxWidth()
                .clickable { onFriendsExpandedChange(!friendsExpanded) }
                .semantics { stateDescription = description }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (friendsExpanded) Icons.Outlined.KeyboardArrowDown
                else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
            Text(stringResource(R.string.friends), style = MaterialTheme.typography.titleMedium)
            if (session.messages.friends.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        session.messages.friends.size.toString(),
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = OliveGreen,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
    // Expand or collapse the accepted-friends list.
    if (friendsExpanded) {
        if (session.messages.friends.isEmpty()) {
            item { Text(stringResource(R.string.no_friends_yet), color = Muted) }
        }
        items(session.messages.friends, key = { "friend-${it.friendshipId}" }) { friend ->
            FriendRow(session, friend, onNoteTargets)
        }
    }
}
/**
 * Shows one username result and the friend action currently allowed.
 *
 * @receiver The lazy list item scope that renders this row.
 * @param session Shared session view model owning application state and actions.
 * @param request Request supplied to [IncomingRequestRow].
 */
@Composable
private fun LazyItemScope.IncomingRequestRow(session: SessionViewModel, request: FriendRequest) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = WarmBannerYellow,
        modifier = Modifier.animateItem().fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.senderUsername, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { session.messages.acceptFriendRequest(request) },
                    enabled = !session.messages.busy,
                    modifier = Modifier.testTag("accept_friend_" + request.id),
                ) { Text(stringResource(R.string.accept)) }
                OutlinedButton(
                    onClick = { session.messages.declineFriendRequest(request) },
                    enabled = !session.messages.busy,
                    modifier = Modifier.testTag("decline_friend_" + request.id),
                ) { Text(stringResource(R.string.decline)) }
            }
        }
    }
}
/**
 * Shows a sent friend request and lets the user cancel it.
 *
 * @receiver The lazy list item scope that renders this row.
 * @param session Shared session view model owning application state and actions.
 * @param request Request supplied to [OutgoingRequestRow].
 */
@Composable
private fun LazyItemScope.OutgoingRequestRow(session: SessionViewModel, request: FriendRequest) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = SoftCardSurface,
        modifier = Modifier.animateItem().fillMaxWidth().animateContentSize(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                request.recipientUsername,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = { session.messages.cancelFriendRequest(request) },
                enabled = !session.messages.busy,
                modifier = Modifier.testTag("cancel_friend_" + request.id),
            ) { Text(stringResource(R.string.cancel_request)) }
        }
    }
}

/**
 * Shared safe app friend lists shows this friend row view so the user can view its details and use its actions.
 *
 * @receiver The lazy list item scope that renders this row.
 * @param session Shared session view model owning application state and actions.
 * @param friend Friend relationship record.
 * @param onNoteTargets Callback invoked when note targets changes or occurs.
 */
@Composable
private fun LazyItemScope.FriendRow(
    session: SessionViewModel,
    friend: Friend,
    onNoteTargets: (List<DirectoryUser>) -> Unit,
) {
    Row(Modifier.animateItem().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { session.messages.startConversation(friend) },
            enabled = !session.messages.busy,
            modifier = Modifier.weight(1f).testTag("friend_" + friend.uid),
        ) {
            Text(friend.username, Modifier.weight(1f), textAlign = TextAlign.Start)
            Text(stringResource(R.string.message))
        }
        TextButton(onClick = {
            onNoteTargets(listOf(DirectoryUser(friend.uid, friend.username)))
        }) { Text(stringResource(R.string.private_note)) }
    }
}

// All safe-app roles: shows messages, attachments, and chat actions.


/**
 * Shows text or the correct viewer button for one chat message.
 *
 * @param chat Chat supplied to [ChatMessageBody].
 * @param session Shared session view model owning application state and actions.
 * @param mine Whether to apply mine.
 * @param sender Sender supplied to [ChatMessageBody].
 * @param onOpenMedia Callback invoked when open media changes or occurs.
 */
@Composable
internal fun ChatMessageBody(
    chat: ChatMessage, session: SessionViewModel, mine: Boolean,
    sender: String,
    onOpenMedia: (SafetyDocument, File) -> Unit
) {
    val parts = chat.body.split(":", limit = 3)
    if (!chat.unsent && parts.firstOrNull() == "SHARED_VOICE" &&
        parts.getOrNull(1)?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true
    ) {
        if (!session.privacyBlackout) key(session.coverAccountId, chat.id) {
            VoiceMessageBubble(parts[1], parts.getOrNull(2).orEmpty(), session, mine)
        }
        return
    }
    if (!chat.unsent && parts.firstOrNull() == "SHARED_MEDIA" && parts.size >= 2) {
        SharedMediaMessage(parts[1], parts.getOrNull(2).orEmpty(), session, mine, onOpenMedia, sender)
        return
    }
    val senderDescription = stringResource(R.string.chat_attachment_sender, sender)
    // An unsent location card can still be removed before it reaches the chat.
    if (!chat.unsent && parts.firstOrNull() == "SHARED_LOCATION") {
        val expiresAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val active = expiresAt > System.currentTimeMillis()
        val status = if (active) {
            stringResource(
                R.string.shared_location_active_until,
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(expiresAt)),
            )
        } else stringResource(R.string.shared_location_ended)
        Surface(
            onClick = { session.navigation.openSafePage(SafePage.Location) }, shape = MaterialTheme.shapes.medium,
            color = if (mine) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .05f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .testTag("shared_live_location")
                .semantics { stateDescription = senderDescription }
        ) {
            Column {
                Surface(
                    color = OliveGreen.copy(alpha = if (mine) .55f else .16f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.LocationOn, null)
                        Text(
                            stringResource(R.string.shared_live_location),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.open_location_map),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }
        return
    }
    // An unsent safety-plan card can still be removed before it reaches the chat.
    if (!chat.unsent && parts.firstOrNull() == "SHARED_SAFETY_PLAN") {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (mine) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .05f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.HealthAndSafety, stringResource(R.string.shared_safety_plan))
                Text(stringResource(R.string.shared_safety_plan), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
        return
    }
    val shared = parts.firstOrNull() in setOf("SHARED_NOTE", "SHARED_INCIDENT") && parts.size >= 2
    if (chat.unsent || !shared) {
        Text(
            when {
                chat.unsent -> stringResource(R.string.message_unsent)
                chat.body.isBlank() -> stringResource(R.string.encrypted_message_unavailable)
                else -> chat.body
            }, fontStyle = if (chat.unsent) androidx.compose.ui.text.font.FontStyle.Italic else null
        )
        return
    }
    val incident = parts[0] == "SHARED_INCIDENT"
    val title = parts.getOrNull(2).orEmpty().ifBlank { stringResource(R.string.encrypted_document) }
    val matchingEntry = session.privateEntries.firstOrNull { it.id == parts[1] }
        ?: session.sharedPrivateEntries.firstOrNull { it.entry.id == parts[1] }?.entry
    Surface(
        onClick = { session.messages.openSharedPrivateEntry(parts[1]) }, shape = MaterialTheme.shapes.medium,
        color = if (mine) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .05f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .semantics { stateDescription = senderDescription }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (incident) Icons.Outlined.Warning else Icons.Outlined.Lock,
                    stringResource(if (incident) R.string.shared_incident_report else R.string.shared_private_note)
                )
                Column {
                    Text(
                        stringResource(if (incident) R.string.shared_incident_report else R.string.shared_private_note),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
            if (matchingEntry?.attachmentIds?.isNotEmpty() == true) {
                NoteAttachmentPreviewStrip(
                    attachmentIds = matchingEntry.attachmentIds,
                    viewerUid = session.coverAccountId,
                    controller = session.documents,
                )
            }
        }
    }
}

/**
 * Shared safe app conversation screen shows this shared media message view so the user can view its details and use its actions.
 *
 * @param id ID supplied to [SharedMediaMessage].
 * @param name Field, key, or display name.
 * @param session Shared session view model owning application state and actions.
 * @param mine Whether to apply mine.
 * @param onOpen Callback invoked when open changes or occurs.
 * @param sender Optional sender supplied to [SharedMediaMessage].
 * @param onLightSurface Whether to use styling intended for a light background.
 */
@Composable
internal fun SharedMediaMessage(
    id: String, name: String, session: SessionViewModel, mine: Boolean,
    onOpen: (SafetyDocument, File) -> Unit,
    sender: String? = null,
    onLightSurface: Boolean = false,
) {
    val opening = id in session.documents.state.openingDocumentIds
    val document = session.documents.documentById(id)
    val offline = session.media?.offlineCopies?.get(id) ?: OfflineMediaAvailability.NOT_CACHED
    val error = session.documents.state.attachmentErrors[id]
    val senderDescription = sender?.let { stringResource(R.string.chat_attachment_sender, it) }
    LaunchedEffect(session.coverAccountId, id, document?.id) {
        val uid = session.coverAccountId ?: return@LaunchedEffect
        session.documents.requestPreviewById(uid, id)
    }
    val displayName = document?.name?.takeIf { it.isNotBlank() }
        ?: name.ifBlank { stringResource(R.string.encrypted_document) }
    val resolvedMime = document?.mimeType.orEmpty()
    val isAudio = pickerMediaKind(resolvedMime) == MediaKind.AUDIO
    val offlineCached = offline in setOf(
        OfflineMediaAvailability.AVAILABLE,
        OfflineMediaAvailability.REVALIDATION_REQUIRED,
    )
    val openMedia: () -> Unit = {
        val uid = session.coverAccountId
        if (uid != null && !opening) {
            session.documents.openShared(uid, id) { doc, local -> onOpen(doc, local) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val mediaContent: @Composable () -> Unit = {
            if (!isAudio) {
                BlurredDocumentPreviewSurface(
                    document = document,
                    preview = session.documents.previewFor(id),
                    loading = session.documents.isPreviewLoading(id),
                    fallbackMimeType = resolvedMime,
                    fallbackName = displayName,
                    offlineCached = offlineCached,
                    opening = opening,
                    modifier = Modifier.fillMaxWidth().height(148.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (opening && isAudio) SecureFileOpeningIndicator()
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onLightSurface) {
            Surface(
                onClick = openMedia,
                enabled = !opening,
                shape = MaterialTheme.shapes.medium,
                color = Color.White,
                contentColor = Ink,
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("shared_media_$id")
                    .semantics { if (senderDescription != null) stateDescription = senderDescription },
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mediaContent()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !opening, onClick = openMedia)
                    .testTag("shared_media_$id")
                    .semantics { if (senderDescription != null) stateDescription = senderDescription },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                mediaContent()
            }
        }
        if (document?.objectKey?.isNotBlank() == true &&
            canManageSharedMediaOfflineCopy(session.accountRole)
        ) {
            val cached = offline in setOf(
                OfflineMediaAvailability.AVAILABLE,
                OfflineMediaAvailability.REVALIDATION_REQUIRED,
            )
            TextButton(
                onClick = {
                    if (cached) session.documents.removeOfflineCopy(document)
                    else session.documents.makeAvailableOffline(document)
                },
                enabled = offline != OfflineMediaAvailability.SAVING,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                colors = if (mine && !onLightSurface) ButtonDefaults.textButtonColors(
                    contentColor = Paper,
                    disabledContentColor = PaleOlive,
                ) else ButtonDefaults.textButtonColors(),
                modifier = Modifier.defaultMinSize(minHeight = 32.dp).testTag("shared_media_offline_$id"),
            ) {
                Text(stringResource(when {
                    offline == OfflineMediaAvailability.SAVING -> R.string.documents_saving_offline
                    cached -> R.string.documents_remove_offline_copy
                    else -> R.string.documents_make_available_offline
                }))
            }
            if (offline == OfflineMediaAvailability.RECENT_AVAILABLE) Text(
                stringResource(R.string.documents_recently_cached),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let {
            Text(
                it,
                color = if (mine && !onLightSurface) OliveErrorContainer else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("shared_media_error_$id"),
            )
        }
    }
}

/**
 * Checks whether the caller can manage shared media offline copy.
 *
 * @param role Account role determining workspace permissions.
 * @return `true` if can manage shared media offline copy, or `false` otherwise.
 */
internal fun canManageSharedMediaOfflineCopy(role: AccountRole) =
    role == AccountRole.USER || role == AccountRole.SAFE_CONTACT

/**
 * Shows the controls for sharing an invite and connecting with an existing code.
 *
 * @param session The signed-in session that creates and claims invites.
 * @param unreadOnly Optional unread-only filter state to render alongside the invite button.
 * @param onUnreadOnlyChange Optional callback invoked when the unread-only filter is toggled.
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun FriendInviteControls(
    session: SessionViewModel,
    unreadOnly: Boolean? = null,
    onUnreadOnlyChange: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var opened by remember(session.coverAccountId) { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (unreadOnly != null) Arrangement.SpaceBetween else Arrangement.End,
        ) {
            if (unreadOnly != null && onUnreadOnlyChange != null) {
                FilterChip(
                    selected = unreadOnly,
                    onClick = onUnreadOnlyChange,
                    label = { Text(stringResource(R.string.chat_unread_only)) },
                )
            }
            Button(
                onClick = { opened = true },
                enabled = !session.localMode,
                modifier = Modifier.walkthroughTarget("friend_invite").testTag("friend_invite")
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.friend_invite))
            }
        }
        InviteCodeEntry(
            messages = session.messages,
            enabled = !session.localMode,
            accountUid = session.coverAccountId,
            onConnected = { session.messages.ensureMessagingObservation() }
        )
    }
    if (opened) {
        val uid = session.coverAccountId.orEmpty()
        FriendInviteDialog(
            uid,
            createInvite = { refresh -> session.messages.createFriendInvite(uid, refresh) },
            observeUnavailable = { code, onUnavailable ->
                session.messages.observeFriendInviteUnavailable(uid, code, onUnavailable)
            },
            onDismiss = { opened = false },
        )
    }
}

/**
 * Lets someone claim an invite code and displays the result in place.
 *
 * @param messages The messaging controller that claims the code.
 * @param enabled Whether the user can enter and submit a code.
 * @param accountUid The account claiming the invite, if already known to the caller.
 * @param onConnected Runs after the code is claimed successfully.
 */
@Composable
internal fun InviteCodeEntry(
    messages: SessionMessages,
    enabled: Boolean = true,
    accountUid: String? = null,
    onConnected: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<String?>(null) }
    var shakeAttempt by remember { mutableIntStateOf(0) }
    val effectiveShake = rememberFieldErrorShakeTrigger(error, shakeAttempt)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().walkthroughTarget("friend_invite_code_card"),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.friend_invite_question), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(32); error = null },
                    label = { Text(stringResource(R.string.friend_invite_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    enabled = enabled && !busy,
                    isError = error != null,
                    modifier = Modifier.weight(1f).testTag("friend_invite_code").oliveErrorShake(effectiveShake)
                )
                val codeRequiredText = stringResource(R.string.friend_invite_code)
                Button(
                    onClick = {
                        if (code.isBlank()) {
                            error = codeRequiredText
                            shakeAttempt += 1
                            return@Button
                        }
                        busy = true; error = null; connected = null
                        scope.launch {
                            try {
                                connected = messages.claimFriendInvite(accountUid, code)
                                code = ""
                                onConnected()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.localizedMessage
                                shakeAttempt += 1
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = enabled && !busy,
                    modifier = Modifier.testTag("friend_invite_claim")
                ) {
                    Text(stringResource(if (busy) R.string.friend_invite_connecting else R.string.friend_invite_connect))
                }
            }
            connected?.let {
                Text(
                    stringResource(R.string.friend_invite_connected, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
