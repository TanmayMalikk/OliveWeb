// User and safe-contact accounts: shows friend search results, requests, and saved friends.
package com.olive.app.ui.users.shared.messaging

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.olive.app.data.DirectoryUser
import com.olive.app.data.Friend
import com.olive.app.data.FriendRelationship
import com.olive.app.data.FriendRequest
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel

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
    if (friendsExpanded) {
        if (session.messages.friends.isEmpty()) {
            item { Text(stringResource(R.string.no_friends_yet), color = Muted) }
        }
        items(session.messages.friends, key = { "friend-${it.friendshipId}" }) { friend ->
            FriendRow(session, friend, onNoteTargets)
        }
    }
}

@Composable
private fun LazyItemScope.SearchResultRow(
    session: SessionViewModel,
    user: DirectoryUser,
    onNoteTargets: (List<DirectoryUser>) -> Unit,
) {
    val relationship = session.messages.relationshipFor(user.uid)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFF3F6F0),
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

@Composable
private fun LazyItemScope.IncomingRequestRow(session: SessionViewModel, request: FriendRequest) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFFFF7E1),
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

@Composable
private fun LazyItemScope.OutgoingRequestRow(session: SessionViewModel, request: FriendRequest) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFF3F6F0),
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
