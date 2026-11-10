// User and safe-contact accounts: shows friend search results, requests, and saved friends.
package com.olive.app.ui.users.shared.messaging

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.navigation.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


// Keeps the four friend-related lists together while the parent screen handles chat lists.
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
    if (session.messages.userSearchResults.isNotEmpty()) {
        item {
            Text(stringResource(R.string.search_results), style = MaterialTheme.typography.titleMedium)
        }
        items(session.messages.userSearchResults, key = { "search-${it.uid}" }) { user ->
            SearchResultRow(session, user, onNoteTargets)
        }
    }

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
// Shows one username result and the friend action currently allowed.
@Composable
private fun LazyItemScope.SearchResultRow(
    session: SessionViewModel,
    user: DirectoryUser,
    onNoteTargets: (List<DirectoryUser>) -> Unit,
) {
    val relationship = session.messages.relationshipFor(user.uid)
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
            Text(user.username, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (relationship == FriendRelationship.Friends) {
                TextButton(onClick = { onNoteTargets(listOf(user)) }) {
                    Text(stringResource(R.string.private_note))
                }
            }
            // Shows the friend action allowed for this search result.
            when (relationship) {
                FriendRelationship.AddFriend -> Button(
                    onClick = { session.messages.sendFriendRequest(user) },
                    enabled = !session.messages.busy,
                ) { Text(stringResource(R.string.add_friend)) }
                FriendRelationship.RequestSent -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.request_sent)) },
                )
                FriendRelationship.IncomingRequest -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.incoming_request)) },
                )
                FriendRelationship.Friends -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.friends)) },
                )
            }
        }
    }
}

// Shows who sent a request and lets the user accept or decline it.
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
// Shows a sent friend request and lets the user cancel it.
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

// shared safe app friend lists shows this friend row view so the user can view its details and use its actions.
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


// Shows text or the correct viewer button for one chat message.
@Composable
internal fun ChatMessageBody(
    chat: com.olive.app.data.ChatMessage, session: SessionViewModel, mine: Boolean,
    onOpenMedia: (SafetyDocument, File) -> Unit
) {
    val parts = chat.body.split(":", limit = 3)
    if (!chat.unsent && parts.firstOrNull() == "SHARED_MEDIA" && parts.size >= 2) {
        SharedMediaMessage(parts[1], parts.getOrNull(2).orEmpty(), session, mine, onOpenMedia)
        return
    }
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
    Surface(
        onClick = { session.messages.openSharedPrivateEntry(parts[1]) }, shape = MaterialTheme.shapes.medium,
        color = if (mine) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .05f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
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
    }
}

// shared safe app conversation screen shows this shared media message view so the user can view its details and use its actions.
@Composable
internal fun SharedMediaMessage(
    id: String, name: String, session: SessionViewModel, mine: Boolean,
    onOpen: (SafetyDocument, File) -> Unit
) {
    var opening by remember(id) { mutableStateOf(false) }
    val document = session.documents.state.files.firstOrNull { it.id == id }
    val offline = session.media?.offlineCopies?.get(id) ?: OfflineMediaAvailability.NOT_CACHED
    val error = session.documents.state.attachmentErrors[id]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Surface(
        onClick = {
            val uid = session.coverAccountId ?: return@Surface
            if (opening) return@Surface
            opening = true
            session.documents.openShared(
                uid,
                id,
                { document, local -> onOpen(document, local) },
                { opening = false })
        }, enabled = !opening, shape = MaterialTheme.shapes.medium,
        color = if (mine) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .05f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("shared_media_$id")
    ) {
        Row(
            Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (opening) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.AttachFile, stringResource(R.string.shared_secure_file))
            Column {
                Text(
                    stringResource(R.string.shared_secure_file),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    name.ifBlank { stringResource(R.string.encrypted_document) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 2
                )
            }
        }
    }
    if (document?.objectKey?.isNotBlank() == true) {
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
            modifier = Modifier.testTag("shared_media_offline_$id"),
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
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("shared_media_error_$id"))
    }
    }
}
