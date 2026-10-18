package com.olive.app.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight

import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*

// Friends and DMs share this adaptive screen: one pane on phones and a sidebar plus chat on wider devices.
// UI sizing: messaging switches to two panes at 720dp; the sidebar is 340dp wide and leaves 96dp for bottom controls.
@Composable internal fun MessagesScreen(session: SessionViewModel, exit: (() -> Unit)?=null) {
    val active=session.activeConversation
    val currentUid=session.coverAccountId.orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=720.dp
        if(wide) Row(Modifier.fillMaxSize().padding(bottom=96.dp)) {
            MessagingSidebar(session,currentUid,Modifier.width(340.dp).fillMaxHeight())
            VerticalDivider()
            if(active==null) Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center) {
                Text(stringResource(R.string.select_a_friend_or_conversation_to_start_messaging),color=Muted)
            } else ChatPane(session,currentUid,false,exit,Modifier.weight(1f).fillMaxHeight())
        } else if(active==null) MessagingSidebar(session,currentUid,Modifier.fillMaxSize())
        else {
            BackHandler { session.closeConversation() }
            ChatPane(session,currentUid,true,exit,Modifier.fillMaxSize())
        }
    }
}

// UI sizing/color: the sidebar uses 20dp side padding, 10dp list gaps, soft status-card colors, and Material title styles.
@Composable private fun MessagingSidebar(session: SessionViewModel, currentUid: String, modifier: Modifier=Modifier) {
    val uiText=rememberResourceText()
    var query by rememberSaveable { mutableStateOf("") }
    var reconnectPassword by rememberSaveable { mutableStateOf("") }
    var friendsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingExpanded by rememberSaveable { mutableStateOf(false) }
    var outgoingExpanded by rememberSaveable { mutableStateOf(false) }
    val listState=rememberLazyListState()
    LazyColumn(modifier,state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.ui_previous_safe_page))
                }
                Text(stringResource(R.string.friends_messages),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics { heading() })
            }
            if(session.localMode) Text(stringResource(R.string.messaging_needs_a_firebase_connected_build),color=Muted)
        }
        if(session.messagingNeedsReconnect) item {
            Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFFFF7E1),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(uiText(R.string.ui_reconnect_your_account),style=MaterialTheme.typography.titleMedium)
                    Text(uiText(R.string.ui_your_local_olive_branch_account_is_locked_safely_but_firebase_nee),color=Muted)
                    Field(uiText(R.string.ui_account_password),reconnectPassword,"messaging_reconnect_password",secret=true,showSecretToggle=true,enabled=!session.messagingBusy) {
                        reconnectPassword=it.take(128)
                    }
                    Button(onClick={session.reconnectMessaging(reconnectPassword) { reconnectPassword="" }},
                        enabled=reconnectPassword.isNotBlank() && !session.messagingBusy,
                        modifier=Modifier.fillMaxWidth().testTag("messaging_reconnect")) { Text(stringResource(R.string.reconnect)) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query,{if(it.length<=24) query=it},label={Text(stringResource(R.string.username))},singleLine=true,
                    modifier=Modifier.weight(1f).testTag("message_username").keepVisibleWhileTyping())
                Button(onClick={session.searchUsers(query)},enabled=query.isNotBlank() && !session.messagingBusy && !session.localMode && !session.messagingNeedsReconnect,
                    modifier=Modifier.testTag("search_users")) { Text(stringResource(R.string.search)) }
            }
        }
        if(session.messagingBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Status(session) }
        if(session.userSearchResults.isNotEmpty()) {
            item { Text(stringResource(R.string.search_results),style=MaterialTheme.typography.titleMedium) }
            items(session.userSearchResults,key={"search-${it.uid}"}) { user ->
                val relationship=session.relationshipFor(user.uid)
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFF3F6F0),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(user.username,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        when(relationship) {
                            FriendRelationship.AddFriend -> Button(onClick={session.sendFriendRequest(user)},enabled=!session.messagingBusy) { Text(stringResource(R.string.add_friend)) }
                            FriendRelationship.RequestSent -> AssistChip(onClick={},enabled=false,label={Text(stringResource(R.string.request_sent))})
                            FriendRelationship.IncomingRequest -> AssistChip(onClick={},enabled=false,label={Text(stringResource(R.string.incoming_request))})
                            FriendRelationship.Friends -> AssistChip(onClick={},enabled=false,label={Text(stringResource(R.string.friends))})
                        }
                    }
                }
            }
        }
        if(session.incomingFriendRequests.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clickable { pendingExpanded=!pendingExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(pendingExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                    Text(stringResource(R.string.incoming_friend_requests),style=MaterialTheme.typography.titleMedium)
                    Badge(Modifier.padding(start=8.dp)) { Text(session.incomingFriendRequests.size.toString()) }
                }
            }
            if(pendingExpanded) items(session.incomingFriendRequests,key={"incoming-${it.id}"}) { request ->
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFFFF7E1),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(request.senderUsername,style=MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={session.acceptFriendRequest(request)},enabled=!session.messagingBusy,
                                modifier=Modifier.testTag("accept_friend_"+request.id)) { Text(stringResource(R.string.accept)) }
                            OutlinedButton(onClick={session.declineFriendRequest(request)},enabled=!session.messagingBusy,
                                modifier=Modifier.testTag("decline_friend_"+request.id)) { Text(stringResource(R.string.decline)) }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { outgoingExpanded=!outgoingExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(outgoingExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                Text(stringResource(R.string.outgoing_friend_requests),style=MaterialTheme.typography.titleMedium)
            }
        }
        if(outgoingExpanded) {
            if(session.outgoingFriendRequests.isEmpty()) item { Text(stringResource(R.string.no_outgoing_requests),color=Muted) }
            items(session.outgoingFriendRequests,key={"outgoing-${it.id}"}) { request ->
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFF3F6F0),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(request.recipientUsername,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick={session.cancelFriendRequest(request)},enabled=!session.messagingBusy,
                            modifier=Modifier.testTag("cancel_friend_"+request.id)) { Text(stringResource(R.string.cancel_request)) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { friendsExpanded=!friendsExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(friendsExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                Text(stringResource(R.string.friends),style=MaterialTheme.typography.titleMedium)
                if(session.friends.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=CircleShape,
                    modifier=Modifier.padding(start=8.dp)) {
                    Text(session.friends.size.toString(),Modifier.padding(horizontal=8.dp,vertical=2.dp),
                        color=OliveGreen,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        if(friendsExpanded) {
            if(session.friends.isEmpty()) item { Text(stringResource(R.string.no_friends_yet),color=Muted) }
            items(session.friends,key={"friend-${it.friendshipId}"}) { friend ->
                OutlinedButton(onClick={session.startConversation(friend)},enabled=!session.messagingBusy,
                    modifier=Modifier.animateItem().fillMaxWidth().testTag("friend_"+friend.uid)) {
                    Text(friend.username,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Start)
                    Text(stringResource(R.string.message))
                }
            }
        }
        item { Text(stringResource(R.string.direct_messages),style=MaterialTheme.typography.titleMedium) }
        if(session.conversations.isEmpty()) item { Text(stringResource(R.string.no_conversations_yet),color=Muted) }
        items(session.conversations,key={"conversation-${it.id}"}) { conversation ->
            OutlinedButton(onClick={session.openConversation(conversation)},modifier=Modifier.animateItem().fillMaxWidth().testTag("conversation_"+conversation.id)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(conversation.peerUsername(currentUid),style=MaterialTheme.typography.titleMedium)
                    Text(conversation.lastMessage.ifBlank { uiText(R.string.new_conversation) },maxLines=1,color=Muted)
                }
            }
        }
    }
}
