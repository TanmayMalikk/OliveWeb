package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// USER + SAFE CONTACT: Olive Branch routes to the private profile, settings, notes, activity, and messaging features.
// UI sizing/color: the safe home uses the shared form grid, OliveGreen branding, Muted guidance, and responsive feature tiles.
@Composable internal fun SafeArea(session: SessionViewModel, exit: ()->Unit) {
    val profile=session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.ensureMessagingObservation() }
    AnimatedContent(targetState=session.page,label="safe page",
        transitionSpec={
            (fadeIn(tween(180))+slideInHorizontally(tween(180)) { it/12 })
                .togetherWith(fadeOut(tween(120))+slideOutHorizontally(tween(120)) { -it/16 })
        }) { targetPage -> when(targetPage) {
        SafePage.Home -> FormColumn {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.olive_branch),fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                    Text(if(profile.role==AccountRole.SAFE_CONTACT) "Safe Contact space" else "Your safe space",style=MaterialTheme.typography.headlineMedium)
                    Text(if(profile.role==AccountRole.SAFE_CONTACT) "Use Quick exit from any page to lock Olive Branch." else "Use Quick exit from any page to lock this area and return to your cover app.",color=Muted)
                }
                IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.align(Alignment.TopEnd).testTag("safe_settings")) {
                    Icon(Icons.Outlined.Settings,"Settings")
                }
            }
            ModeNotice(session)
            Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_profile")) { Text(stringResource(R.string.my_profile)) }
            Button(onClick={session.navigate(SafePage.Activity)},modifier=Modifier.fillMaxWidth().testTag("safe_activity")) { Text(stringResource(R.string.account_activity)) }
            Text(stringResource(R.string.private_features),style=MaterialTheme.typography.titleLarge)
            // UI sizing/color: feature tiles are two-wide, at least 112dp tall, use 30–32dp icons, and 17–18sp centered labels.
            // Change the two container colors here if these tiles need different shades from the shared theme.
            listOf(1,3).forEach { first ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf(first,first+1).forEach { number ->
                        if(number==1) Button(onClick={session.navigate(SafePage.Notes)},
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF718669),contentColor=Color.White),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_notes")) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Description,"Notes and incidents",Modifier.size(32.dp))
                                Text(stringResource(R.string.notes_incidents),fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        } else if(number==2) Button(onClick={session.navigate(SafePage.Messages)},
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF7D9274),contentColor=Color.White),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_messages")) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                BadgedBox(badge={
                                    if(session.pendingMessageActionCount>0) Badge {
                                        Text(if(session.pendingMessageActionCount>99) "99+" else session.pendingMessageActionCount.toString())
                                    }
                                }) { Icon(Icons.Outlined.ChatBubbleOutline,"Messages",Modifier.size(32.dp)) }
                                Text(stringResource(R.string.messages),fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        } else OutlinedButton(onClick={},enabled=false,
                            colors=ButtonDefaults.outlinedButtonColors(disabledContainerColor=Color(0xFFE7EDE2),disabledContentColor=Muted),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("future_feature_$number")) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.AddCircleOutline,"Future feature",Modifier.size(30.dp))
                                Text("Future feature $number",fontSize=17.sp,lineHeight=21.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        }
                    }
                }
            }
            if(session.pendingSync) {
                Text(stringResource(R.string.profile_changes_are_saved_on_this_device_and_awaiting_cloud_sync))
                OutlinedButton(onClick=session::sync,enabled=!session.busy) { Text(stringResource(R.string.sync_changes)) }
            }
            Text(if(profile.role==AccountRole.SAFE_CONTACT) "Leaving the app locks Olive Branch." else "Leaving the app locks this area. A cover app does not make Olive Branch impossible to discover.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Status(session)
            TextButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.testTag("sign_out")) { Text(stringResource(R.string.sign_out_on_this_device)) }
        }
        SafePage.Profile -> FormColumn {
            SafeBack(session)
            Heading("My profile", "Your saved account information.")
            val rows=buildList {
                add("Account type" to profile.role.title);add("Username" to profile.username);add("Full name" to profile.fullName)
                if(profile.role==AccountRole.USER) {
                    add("People experiencing abuse" to profile.affectedNames);add("Abuser's name" to profile.abuserName);add("Situation" to profile.situation)
                }
                add("Date of birth" to profile.dob);add("Preferred language" to profile.language);add("Region / city / state" to profile.region)
                if(profile.role==AccountRole.USER) {
                    add("Cover app" to profile.coverType.title)
                    add("Delivery address" to listOf(profile.address.line1,profile.address.line2,profile.address.city,profile.address.province,profile.address.postalCode,profile.address.country).filter { it.isNotBlank() }.joinToString(", "))
                }
            }
            rows
                .forEach { (label,value) ->
                    Text(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                    if(value.isBlank()) Text(stringResource(R.string.not_provided))
                    else if(label=="Preferred language") Text(value)
                    else Text(value)
                }
        }
        SafePage.Notes -> PrivateNotes(session)
        SafePage.Messages -> MessagesScreen(session,exit)
        SafePage.Settings -> SettingsEditor(session,profile)
        SafePage.Contacts -> ContactEditor(session,profile)
        SafePage.Activity -> ActivityHistory(session)
    } }
}

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
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous safe page")
                }
                Text(stringResource(R.string.friends_messages),style=MaterialTheme.typography.headlineSmall)
            }
            if(session.localMode) Text(stringResource(R.string.messaging_needs_a_firebase_connected_build),color=Muted)
        }
        if(session.messagingNeedsReconnect) item {
            Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFFFF7E1),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Reconnect your account",style=MaterialTheme.typography.titleMedium)
                    Text("Your local Olive Branch account is locked safely, but Firebase needs your password again before friends and messages can load.",color=Muted)
                    Field("Account password",reconnectPassword,"messaging_reconnect_password",secret=true,showSecretToggle=true,enabled=!session.messagingBusy) {
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
                    modifier=Modifier.weight(1f).testTag("message_username"))
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
                    Text(conversation.lastMessage.ifBlank { "New conversation" },maxLines=1,color=Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
// UI sizing/color: chat bubbles cap at 340dp, sent bubbles use OliveGreen, and the header stays at least 76dp tall.
@Composable private fun ChatPane(session: SessionViewModel, currentUid: String, showBack: Boolean,
    exit: (() -> Unit)?, modifier: Modifier=Modifier) {
    val active=session.activeConversation ?: return
    var draft by rememberSaveable(active.id) { mutableStateOf("") }
    val listState=rememberLazyListState()
    val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardVisible=imeBottom>0
    LaunchedEffect(session.chatMessages.size,session.chatMessages.lastOrNull()?.body) {
        if(session.chatMessages.isNotEmpty()) listState.animateScrollToItem(session.chatMessages.lastIndex)
    }
    LaunchedEffect(keyboardVisible) {
        if(keyboardVisible && session.chatMessages.isNotEmpty()) {
            delay(120)
            listState.animateScrollToItem(session.chatMessages.lastIndex)
        }
    }
    Box(modifier) {
    Column(Modifier.fillMaxSize().padding(start=20.dp,end=20.dp,top=12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showBack) IconButton(onClick=session::closeConversation,modifier=Modifier.testTag("messages_conversation_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All conversations")
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.direct_message),style=MaterialTheme.typography.labelLarge,color=Muted)
                Text(active.peerUsername(currentUid).lowercase(java.util.Locale.ROOT),style=MaterialTheme.typography.titleLarge)
            }
            if(exit!=null) Button(onClick=exit,contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp),
                modifier=Modifier.heightIn(min=44.dp).testTag("quick_exit")) { Text("EXIT") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().imePadding().padding(bottom=10.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(session.chatMessages.isEmpty()) item {
                Text(stringResource(R.string.no_messages_yet_say_hello_when_you_are_ready),color=Muted,modifier=Modifier.padding(12.dp))
            }
            items(session.chatMessages,key={it.id}) { chat ->
                val mine=chat.senderUid==currentUid
                var menuOpen by remember(chat.id) { mutableStateOf(false) }
                Row(Modifier.animateItem().fillMaxWidth(),horizontalArrangement=if(mine) Arrangement.End else Arrangement.Start) {
                    Surface(color=if(mine) OliveGreen else Color(0xFFE7EDE2),contentColor=if(mine) Color.White else Color.Unspecified,
                        shape=MaterialTheme.shapes.large,modifier=Modifier.widthIn(max=340.dp).combinedClickable(onClick={},onLongClick={if(mine && !chat.unsent) menuOpen=true})) {
                        Row(Modifier.padding(start=14.dp,end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(if(mine) session.coverUsername else chat.senderUsername,style=MaterialTheme.typography.labelSmall,
                                    color=if(mine) Color.White.copy(alpha=.78f) else Muted)
                                Text(chat.body,fontStyle=if(chat.unsent) androidx.compose.ui.text.font.FontStyle.Italic else null)
                                if(chat.sentAt>0) Text(DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(chat.sentAt)),
                                    style=MaterialTheme.typography.labelSmall,color=if(mine) Color.White.copy(alpha=.7f) else Muted)
                            }
                            if(mine && !chat.unsent) Box {
                                IconButton(onClick={menuOpen=true},modifier=Modifier.size(34.dp)) { Icon(Icons.Outlined.MoreVert,"Message actions") }
                                DropdownMenu(menuOpen,{menuOpen=false}) {
                                    DropdownMenuItem(text={Text(stringResource(R.string.unsend_message))},onClick={menuOpen=false;session.unsendMessage(chat)})
                                }
                            }
                        }
                    }
                }
            }
        }
        Status(session)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft,{if(it.length<=2_000) draft=it},label={Text(stringResource(R.string.message))},
                modifier=Modifier.weight(1f).testTag("message_body"),maxLines=4)
            Button(onClick={session.sendMessage(draft) { draft="" }},enabled=draft.isNotBlank() && !session.messagingBusy,
                modifier=Modifier.testTag("send_message")) { Text(stringResource(R.string.send)) }
        }
        }
    }
    AnimatedVisibility(visible=exit!=null && session.chatQuickExitHintVisible,
        modifier=Modifier.matchParentSize(),enter=fadeIn(tween(180)),exit=fadeOut(tween(120))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.24f))
            .clickable(interactionSource=remember { MutableInteractionSource() },indication=null) {})
    }
    AnimatedVisibility(visible=exit!=null && session.chatQuickExitHintVisible,
        modifier=Modifier.align(Alignment.TopEnd).padding(top=52.dp,end=16.dp,start=16.dp),
        enter=fadeIn(tween(220))+scaleIn(tween(220),initialScale=.86f)+slideInVertically(tween(220)) { -it/5 },
        exit=fadeOut(tween(140))+scaleOut(tween(140),targetScale=.94f)) {
        Column(horizontalAlignment=Alignment.End) {
            Text("▲",color=OliveGreen,fontSize=28.sp,lineHeight=18.sp,modifier=Modifier.padding(end=18.dp))
            Surface(shape=MaterialTheme.shapes.large,color=OliveGreen,contentColor=Color.White,
                shadowElevation=14.dp,border=BorderStroke(2.dp,Color.White.copy(alpha=.9f)),
                modifier=Modifier.fillMaxWidth(.92f).widthIn(min=280.dp,max=360.dp).testTag("chat_quick_exit_hint")) {
                Column(Modifier.padding(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Quick exit moved here",fontSize=20.sp,lineHeight=25.sp,
                        fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=Color.White)
                    Text("While you are in a chat, use EXIT in the top-right corner.",
                        fontSize=16.sp,lineHeight=22.sp,color=Color.White)
                    Button(onClick=session::dismissChatQuickExitHint,
                        colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=OliveGreen),
                        modifier=Modifier.align(Alignment.End).heightIn(min=46.dp).testTag("dismiss_chat_quick_exit_hint")) {
                        Text("Okay",fontSize=16.sp)
                    }
                }
            }
        }
    }
    }
}


// Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor.
// UI sizing/color: note editing uses 24dp sides, a 300dp minimum body field, yellow pinned icons, and an adaptive 160dp grid.
@Composable internal fun PrivateNotes(session: SessionViewModel) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<PrivateEntryKind?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val formatter=remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z",
        Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    fun openEditor(entry: PrivateEntry?) {
        session.clearPrivateEntryStatus()
        editingId=entry?.id;kind=entry?.kind;title=entry?.title.orEmpty();body=entry?.body.orEmpty();editorOpen=true
    }
    BackHandler(editorOpen || logEntryId!=null || trashOpen) {
        when {
            logEntryId!=null -> logEntryId=null
            editorOpen -> editorOpen=false
            else -> trashOpen=false
        }
    }
    val logEntry=session.privateEntries.firstOrNull { it.id==logEntryId }
    if(logEntry!=null) {
        PrivateEntryEditLog(logEntry,formatter) { logEntryId=null }
        return
    }
    if(editorOpen) {
        val entry=session.privateEntries.firstOrNull { it.id==editingId }
        val editorListState=rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().oliveScrollbar(editorListState),state=editorListState,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={editorOpen=false},modifier=Modifier.testTag("private_notes_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                Text(if(editingId==null) "New private entry" else "Edit private entry",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(entry!=null) IconButton(onClick={session.togglePrivateEntryPin(entry.id)},enabled=!session.busy,modifier=Modifier.testTag("private_pin_editor")) {
                    Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted)
                }
                if(entry!=null) IconButton(onClick={session.trashPrivateEntry(entry.id);editorOpen=false},enabled=!session.busy,
                    modifier=Modifier.testTag("delete_private_entry")) { Icon(Icons.Outlined.DeleteOutline,"Move to Trash") }
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(PrivateEntryKind.Note to "Note",PrivateEntryKind.Incident to "Incident").forEach { (value,label) ->
                    FilterChip(selected=kind==value,onClick={kind=if(kind==value) null else value},label={Text(label)},
                        modifier=Modifier.weight(1f).testTag("private_kind_${value.name.lowercase()}"))
                }
            } }
            item { Field("Title (optional)",title,"private_entry_title",enabled=!session.busy) { title=it.take(120) } }
            item { OutlinedTextField(value=body,onValueChange={body=it.take(10_000)},label={Text(stringResource(R.string.private_details))},
                modifier=Modifier.fillMaxWidth().heightIn(min=300.dp).testTag("private_entry_body"),enabled=!session.busy) }
            item { Button(onClick={
                session.savePrivateEntry(editingId,kind,title,body)
                if(body.isNotBlank()) editorOpen=false
            },enabled=!session.busy && body.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save_private_entry")) {
                Text(if(editingId==null) "Save private entry" else "Update private entry")
            } }
            if(entry?.edits?.isNotEmpty()==true) item { TextButton(onClick={logEntryId=entry.id},modifier=Modifier.fillMaxWidth().testTag("private_edit_log")) {
                Text("${stringResource(R.string.edit_log)} (${entry.edits.size})")
            } }
            item { Status(session) }
        }
        return
    }
    val trashed=session.privateEntries.filter { it.deletedAt!=null }.sortedByDescending { it.deletedAt }
    if(trashOpen) {
        Trash(trashed,formatter,{ id ->
            session.restorePrivateEntry(id)
            trashOpen=false
        }) { trashOpen=false }
        return
    }
    val filtered=session.privateEntries.filter { it.deletedAt==null && (search.isBlank() || it.title.contains(search,true) || it.body.contains(search,true)) }
    val pinned=filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val others=filtered.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
    val notesGridState=rememberLazyGridState()
    LazyVerticalGrid(columns=GridCells.Adaptive(160.dp),modifier=Modifier.fillMaxSize().oliveScrollbar(notesGridState),state=notesGridState,
        contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=112.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous safe page") }
            Text(stringResource(R.string.notes_incidents),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            IconButton(onClick={trashOpen=true},modifier=Modifier.testTag("open_scratchpad")) {
                BadgedBox(badge={if(trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) }}) {
                    Icon(Icons.Outlined.DeleteOutline,"Trash")
                }
            }
            FilledTonalIconButton(onClick={openEditor(null)},enabled=!session.busy,modifier=Modifier.testTag("new_private_entry")) { Icon(Icons.Outlined.Add,"New note") }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { OutlinedTextField(search,{search=it.take(200)},modifier=Modifier.fillMaxWidth().testTag("private_notes_search"),
            placeholder={Text(stringResource(R.string.search_notes))},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,
            trailingIcon=if(search.isNotEmpty()) {{IconButton(onClick={search=""}) { Icon(Icons.Outlined.Close,"Clear search") }}} else null) }
        if(session.message!=null || session.busy) item(span={GridItemSpan(maxLineSpan)}) { Status(session) }
        if(filtered.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(if(search.isBlank()) "No private entries yet." else "No notes found.",color=Muted) }
        if(pinned.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(stringResource(R.string.pinned),style=MaterialTheme.typography.titleMedium,color=OliveGreen) }
        gridItems(pinned,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
        if(others.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(if(pinned.isEmpty()) "All notes" else "Others",style=MaterialTheme.typography.titleMedium) }
        gridItems(others,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
    }
}

// UI sizing/color: Trash cards use a 1dp black outline, warm paper fill, 16dp padding, and Material text sizes.
@Composable private fun Trash(entries: List<PrivateEntry>, formatter: DateTimeFormatter,
    restore: (String)->Unit, close: ()->Unit) {
    var now by remember(entries) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entries) {
        while(true) { delay(1_000L);now=System.currentTimeMillis() }
    }
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=close,modifier=Modifier.testTag("close_scratchpad")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                Text("Trash",style=MaterialTheme.typography.headlineSmall)
            }
            Text(if(PRIVATE_ENTRY_TRASH_RETENTION_MS<60_000L)
                "For testing, deleted notes stay here for 10 seconds before their private contents are permanently erased."
                else "Deleted notes stay here for 30 days, then their private contents are permanently erased.",color=Muted)
        }
        if(entries.isEmpty()) item { Text("Trash is empty.",color=Muted) }
        items(entries,key={it.id}) { entry ->
            Card(Modifier.animateItem().fillMaxWidth().testTag("scratchpad_entry"),border=BorderStroke(1.dp,Color.Black),
                colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    entry.kind?.let { Text(if(it==PrivateEntryKind.Incident) "Incident" else "Note",color=OliveGreen) }
                    if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium)
                    Text(entry.body,maxLines=5,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if(PRIVATE_ENTRY_TRASH_RETENTION_MS<60_000L)
                        "${entry.trashSecondsRemaining(now)} ${"seconds remaining"}"
                        else "${entry.trashDaysRemaining(now)} ${stringResource(R.string.days_remaining)}",
                        style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text(formatter.format(Instant.ofEpochMilli(entry.deletedAt ?: entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    OutlinedButton(onClick={restore(entry.id)},modifier=Modifier.fillMaxWidth().testTag("restore_private_entry")) {
                        Icon(Icons.Outlined.RestoreFromTrash,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.restore))
                    }
                }
            }
        }
    }
}

// UI sizing/color: revision cards use 16dp padding, black outlines, soft olive badges, and small Muted timestamps.
@Composable private fun PrivateEntryEditLog(entry: PrivateEntry, formatter: DateTimeFormatter, close: ()->Unit) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick=close,modifier=Modifier.testTag("close_private_edit_log")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text(stringResource(R.string.close))
            }
            Text(stringResource(R.string.edit_log),style=MaterialTheme.typography.headlineMedium)
            Text(entry.title.ifBlank { stringResource(R.string.untitled_entry) },style=MaterialTheme.typography.titleMedium,color=Muted)
            Text("${entry.edits.size} ${stringResource(R.string.revisions)}",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        items(entry.edits.asReversed(),key={it.id}) { edit ->
            val revision=entry.edits.indexOf(edit)+1
            Card(Modifier.animateItem().fillMaxWidth(),border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Surface(shape=CircleShape,color=Color(0xFFE7EDE2)) {
                            Text(revision.toString(),Modifier.padding(horizontal=11.dp,vertical=6.dp),fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=OliveGreen)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${stringResource(R.string.revision)} $revision",style=MaterialTheme.typography.titleMedium)
                            Text(formatter.format(Instant.ofEpochMilli(edit.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                    edit.changes.forEach { change ->
                        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=Color(0xFFF5F3EC)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text(change.field,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                                Text(stringResource(R.string.before),style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(change.before.ifBlank { "Empty" })
                                HorizontalDivider(Modifier.padding(vertical=3.dp))
                                Text(stringResource(R.string.after),style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(change.after.ifBlank { "Empty" })
                            }
                        }
                    }
                }
            }
        }
    }
}

// UI sizing/color: note cards use a 1dp outline, warm paper fill, 14dp padding, and up to three title lines.
@Composable private fun PrivateNoteCard(entry: PrivateEntry, formatter: DateTimeFormatter,
    open: ()->Unit, pin: ()->Unit, openLog: ()->Unit, modifier: Modifier=Modifier) {
    Card(onClick=open,modifier=modifier.fillMaxWidth().testTag("private_entry_row"),
        border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                entry.kind?.let { Text(if(it==PrivateEntryKind.Incident) "Incident" else "Note",style=MaterialTheme.typography.labelMedium,color=OliveGreen,modifier=Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
                IconButton(onClick=pin,modifier=Modifier.size(34.dp).testTag("private_pin")) { Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted) }
            }
            if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(entry.body,maxLines=8,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text((if(entry.edits.isNotEmpty()) "${stringResource(R.string.edited)} · " else "")+formatter.format(Instant.ofEpochMilli(entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(entry.edits.isNotEmpty()) TextButton(onClick=openLog,modifier=Modifier.testTag("private_edit_log"),contentPadding=PaddingValues(0.dp)) {
                Text("${stringResource(R.string.edit_log)} (${entry.edits.size})")
            }
        }
    }
}
// UI sizing/text: this back action keeps Material button sizing and localized text rather than a custom fixed size.
@Composable private fun SafeBack(session: SessionViewModel) {
    TextButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(stringResource(R.string.back)) }
}

// Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them.
// UI sizing/text: contact cards use 16dp padding and Material title text; edits stay in shared full-width fields.
@Composable private fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    FormColumn {
        TextButton(onClick=session::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(stringResource(R.string.settings)) }
        Heading("Safe contacts", "Save trusted people here. Olive does not send messages or make calls.")
        profile.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(contact.name,style=MaterialTheme.typography.titleMedium)
                    Text(contact.details)
                    Row {
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Opened contact editor");editing=contact.id; name=contact.name; details=contact.details},enabled=!session.busy) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Selected remove contact");session.saveContacts(profile.contacts.filterNot { it.id==contact.id }); if(editing==contact.id) {editing=null; name=""; details=""}},enabled=!session.busy) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        if(profile.contacts.isEmpty()) Text(stringResource(R.string.no_contacts_yet_adding_one_is_optional),color=Muted)
        Text(if(editing==null) "Add a contact" else "Edit contact",style=MaterialTheme.typography.titleMedium)
        Field("Contact name",name,"contact_name",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact name")}) { name=it.take(120) }
        Field("Phone or email",details,"contact_details",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact details")}) { details=it.take(200) }
        Status(session)
        Button(onClick={
            if(name.isBlank() || details.isBlank()) session.error("Enter a name and a phone number or email.")
            else if(editing==null && profile.contacts.size>=25) session.error("You can save up to 25 contacts.")
            else {
                val contact=SafeContact(editing ?: UUID.randomUUID().toString(),name.trim(),details.trim())
                session.saveContacts(if(editing==null) profile.contacts+contact else profile.contacts.map { if(it.id==editing) contact else it })
                editing=null; name=""; details=""
            }
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("save_contact")) { Text(stringResource(R.string.save_contact)) }
        if(editing!=null) TextButton(onClick={editing=null; name=""; details=""}) { Text(stringResource(R.string.cancel_edit)) }
    }
}


// Same two-language picker on the safe side, kept separate so the layout stays simple.
// UI sizing/text: the language control fills its parent so translated language names have the full form width.
@Composable internal fun LanguagePicker(value: String, enabled: Boolean = true, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val supportedLanguages = AppLocaleManager.supportedLanguages
    Box {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("language")) { Text(stringResource(R.string.language_required)); Spacer(Modifier.weight(1f)); Text(AppLocaleManager.getDisplayLanguage(value)) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            supportedLanguages.forEach { language -> DropdownMenuItem(text={Text(AppLocaleManager.getDisplayLanguage(language))},onClick={onChange(language);open=false}) }
        }
    }
}
// Edit private profile, language, and PIN-guarded trigger settings in this section.
// UI sizing/text: settings reuse shared form spacing and Material titleLarge section headings so translated labels can wrap.
@Composable internal fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
    val userRole=profile.role==AccountRole.USER
    var fullName by remember { mutableStateOf(profile.fullName) }
    var affected by remember { mutableStateOf(profile.affectedNames) }
    var abuserName by remember { mutableStateOf(profile.abuserName) }
    var dob by remember { mutableStateOf(profile.dob) }
    var language by remember { mutableStateOf(profile.language) }
    var situation by remember { mutableStateOf(profile.situation) }
    var address by remember { mutableStateOf(profile.address) }
    var trigger by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentDevicePin by remember { mutableStateOf("") }
    var newDevicePin by remember { mutableStateOf("") }
    var confirmDevicePin by remember { mutableStateOf("") }
    FormColumn {
        SafeBack(session)
        Heading("Settings","Update your profile and language at any time.")
        if(userRole) OutlinedButton(onClick={session.navigate(SafePage.Contacts)},modifier=Modifier.fillMaxWidth().testTag("safe_contacts")) { Text(stringResource(R.string.safe_contacts)) }
        if(userRole) {
            // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
            CoverPicker(profile.coverType,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected cover app");session.changeCover(it) }
            Text(stringResource(R.string.olive_and_daily_digest_use_search_for_safe_entry_better_calculator_uses_your_device_pin_followed_by_two_equals_taps),style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Field("Full name (required)",fullName,"settings_full_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused full name")}) { fullName=it.take(120) }
        if(userRole) {
            Field("Names of people experiencing abuse (optional)",affected,"settings_affected",onFocus={session.recordSettingsInteraction("Olive Branch","Focused affected names")}) { affected=it.take(500) }
            Field("Abuser's name (optional)",abuserName,"settings_abuser_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused abuser name")}) { abuserName=it.take(120) }
        }
        BirthdayPicker(dob,"settings_dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected language");language=it }
        if(userRole) Field("Brief description of your situation (optional, recommended)",situation,"settings_situation",multiline=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused situation")}) { situation=it.take(2000) }
        Text("Location · city required",style=MaterialTheme.typography.titleLarge)
        AddressFields(address,cityOnlyRequired=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused address: $it")}) { address=it }
        Button(onClick={session.saveSettings(fullName,affected,abuserName,dob,language,situation,address)},enabled=!session.busy,modifier=Modifier.testTag("save_settings")) { Text(stringResource(R.string.save_settings)) }
        HorizontalDivider()
        Text(stringResource(R.string.change_account_password),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.your_username_cannot_be_changed))
        Field("Current password",currentPassword,"current_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current password")}) { currentPassword=it.take(24) }
        Field("New password",newPassword,"new_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new password")}) { newPassword=it.take(24) }
        Field("Confirm new password",confirmPassword,"confirm_new_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused password confirmation")}) { confirmPassword=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmPassword);currentPassword="";newPassword="";confirmPassword=""},enabled=!session.busy,modifier=Modifier.testTag("change_password")) { Text(stringResource(R.string.change_password)) }
        if(userRole) {
            HorizontalDivider()
            Text(stringResource(R.string.change_device_pin),style=MaterialTheme.typography.titleLarge)
            Field("Current device PIN",currentDevicePin,"current_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current PIN")}) { currentDevicePin=it.filter(Char::isDigit).take(6) }
            Field("New device PIN",newDevicePin,"new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new PIN")}) { newDevicePin=it.filter(Char::isDigit).take(6) }
            Field("Confirm new device PIN",confirmDevicePin,"confirm_new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused PIN confirmation")}) { confirmDevicePin=it.filter(Char::isDigit).take(6) }
            Button(onClick={session.changePin(currentDevicePin,newDevicePin,confirmDevicePin);currentDevicePin="";newDevicePin="";confirmDevicePin=""},enabled=!session.busy,modifier=Modifier.testTag("change_device_pin")) { Text(stringResource(R.string.change_device_pin)) }
            HorizontalDivider()
            Text(stringResource(R.string.change_trigger_word),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.enter_your_current_device_pin_to_change_the_trigger_word_incorrect_attempts_count_toward_the_five_attempt_limit))
            Field("New trigger word or phrase",trigger,"settings_trigger",secret=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger word")}) { trigger=it.take(64) }
            Field("Current PIN",pin,"settings_pin",secret=true,numeric=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger-change PIN")}) { pin=it.filter { c -> c in '0'..'9' }.take(6) }
            Button(onClick={session.changeTrigger(trigger,pin);pin="";trigger=""},enabled=!session.busy,modifier=Modifier.testTag("change_trigger")) { Text(stringResource(R.string.change_trigger_word)) }
        }
        Status(session)
    }
}

// Tap a range edge to choose its local calendar date first and clock time second.
// UI sizing: date/time controls split the row evenly with 8dp gaps and 14x12dp button content padding.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDateTimePicker(label: String, value: Long?, tag: String, onChange: (Long?)->Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val zone=ZoneId.systemDefault()
    val locale= remember { Locale.getDefault() }
    val display=value?.let { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",locale).withZone(zone).format(Instant.ofEpochMilli(it)) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={showDate=true},modifier=Modifier.weight(1f).testTag(tag),contentPadding=PaddingValues(horizontal=14.dp,vertical=12.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                Text(label,style=MaterialTheme.typography.labelMedium)
                Text(display ?: "Not selected",color=if(value==null) Muted else MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.AccessTime,null)
        }
        if(value!=null) TextButton(onClick={onChange(null)}) { Text(stringResource(R.string.clear)) }
    }
    if(showDate) key(value) {
        val initial=value ?: System.currentTimeMillis()
        val localDate=Instant.ofEpochMilli(initial).atZone(zone).toLocalDate()
        val utcDate=localDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker=rememberDatePickerState(initialSelectedDateMillis=utcDate)
        DatePickerDialog(onDismissRequest={showDate=false},confirmButton={
            TextButton(onClick={
                pendingDate=picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() } ?: localDate
                showDate=false;showTime=true
            }) { Text(stringResource(R.string.next)) }
        },dismissButton={TextButton(onClick={showDate=false}) { Text(stringResource(R.string.cancel)) }}) {
            DatePicker(state=picker,modifier=Modifier.testTag("${tag}_calendar"))
        }
    }
    if(showTime) key(pendingDate,value) {
        val initial=value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: java.time.LocalTime.now(zone)
        val picker=rememberTimePickerState(initialHour=initial.hour,initialMinute=initial.minute,is24Hour=true)
        AlertDialog(onDismissRequest={showTime=false},title={Text(stringResource(R.string.select_time))},text={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            TimePicker(state=picker,modifier=Modifier.testTag("${tag}_clock"))
        }},
            confirmButton={TextButton(onClick={
                val date=pendingDate ?: LocalDate.now(zone)
                onChange(date.atTime(picker.hour,picker.minute).atZone(zone).toInstant().toEpochMilli());showTime=false
            }) { Text(stringResource(R.string.apply)) }},dismissButton={TextButton(onClick={showTime=false}) { Text(stringResource(R.string.cancel)) }})
    }
}

// Activity stays grouped on the overview, then See more opens one searchable category at a time.
// UI sizing/text: activity uses 24dp page padding, 14dp row gaps, 16dp cards, titleLarge sections, and bodySmall timestamps.
@Composable internal fun ActivityHistory(session: SessionViewModel) {
    val formatter=remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss z",
        Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    var from by remember { mutableStateOf<Long?>(null) }
    var to by remember { mutableStateOf<Long?>(null) }
    fun inRange(time: Long)=(from==null || time>=from!!) && (to==null || time<=to!!+59_999)
    val settings=session.settingsHistory.filter { inRange(it.timestamp) }
    val triggers=session.triggerHistory.filter { inRange(it.timestamp) }
    val attempts=session.attempts.filter { inRange(it.timestamp) }
    val checkouts=session.checkoutHistory.filter { inRange(it.timestamp) }
    val privacy=session.privacyHistory.filter { inRange(it.timestamp) }
    val selected=session.activityCategory
    fun <T> visible(category: ActivityCategory,rows: List<T>)=if(selected==category) rows else rows.take(5)
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState).testTag("activity_list"),state=listState,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=24.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick=session::activityBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(if(selected==null) "Safe home" else "All activity") } }
        item { Heading(if(selected==null) "Account activity" else when(selected) {
            ActivityCategory.Settings -> "Settings history";ActivityCategory.Triggers -> "Trigger history"
            ActivityCategory.PinAttempts -> "PIN attempt history";ActivityCategory.Checkouts -> "Checkout attempts"
            ActivityCategory.Privacy -> "Privacy events"
        },"Search activity between optional local date and time values.") }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ActivityDateTimePicker("From",from,"activity_from") { from=it }
                ActivityDateTimePicker("To",to,"activity_to") { to=it }
            }
        }
        if(selected==null || selected==ActivityCategory.Settings) {
            item { Text(stringResource(R.string.settings_history),style=MaterialTheme.typography.titleLarge) }
            if(settings.isEmpty()) item { Text(stringResource(R.string.no_settings_activity_yet)) }
            items(visible(ActivityCategory.Settings,settings),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("settings_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${event.area} · ${event.action}",fontWeight= FontWeight.SemiBold)
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.changes.forEach { change -> Text("${change.field}: ${change.before.ifBlank { "Empty" }} → ${change.after.ifBlank { "Empty" }}",style=MaterialTheme.typography.bodySmall) }
                } }
            }
            if(selected==null && settings.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Settings)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Triggers) {
            item { HorizontalDivider();Text(stringResource(R.string.trigger_history),style=MaterialTheme.typography.titleLarge) }
            if(triggers.isEmpty()) item { Text(stringResource(R.string.no_trigger_activity_yet)) }
            items(visible(ActivityCategory.Triggers,triggers),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("trigger_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(event.source,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(event.pinAttempts>0) Text("${stringResource(R.string.pin_attempts)}: ${event.pinAttempts} · ${event.lastResult}")
                } }
            }
            if(selected==null && triggers.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Triggers)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.PinAttempts) {
            item { HorizontalDivider();Text(stringResource(R.string.pin_attempt_history),style=MaterialTheme.typography.titleLarge) }
            if(attempts.isEmpty()) item { Text(stringResource(R.string.no_failed_attempts_recorded)) }
            items(visible(ActivityCategory.PinAttempts,attempts),key={it.id}) { attempt ->
                Card(Modifier.fillMaxWidth().testTag("attempt_row")) { Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.incorrect_pin));Text(formatter.format(Instant.ofEpochMilli(attempt.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                } }
            }
            if(selected==null && attempts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.PinAttempts)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Checkouts) {
            item { HorizontalDivider();Text(stringResource(R.string.checkout_attempts),style=MaterialTheme.typography.titleLarge) }
            if(checkouts.isEmpty()) item { Text(stringResource(R.string.no_checkout_attempts_yet)) }
            items(visible(ActivityCategory.Checkouts,checkouts),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("safe_checkout_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.foods.forEach { Text("${it.quantity} × ${it.food}") };Text(event.error)
                } }
            }
            if(selected==null && checkouts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Checkouts)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Privacy) {
            item { HorizontalDivider();Text(stringResource(R.string.privacy_events),style=MaterialTheme.typography.titleLarge) }
            if(privacy.isEmpty()) item { Text(stringResource(R.string.no_capture_events_reported_by_android)) }
            items(visible(ActivityCategory.Privacy,privacy),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(event.action);Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted) } }
            }
            if(selected==null && privacy.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Privacy)}) { Text(stringResource(R.string.see_more)) } }
        }
    }
}

// UI sizing/text: cover choices fill the form width and use Material button text so longer translated names can wrap.
@Composable internal fun CoverPicker(selected: CoverType, enabled: Boolean, onSelect: (CoverType) -> Unit) {
    Text(stringResource(R.string.choose_your_cover_app),style=MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.the_app_stays_open_its_icon_and_name_update_after_you_return_to_the_home_screen),style=MaterialTheme.typography.bodySmall,color=Muted)
    CoverType.entries.forEach { type ->
        val label=when(type) {
            CoverType.FOOD -> "Food Delivery App · Olive"
            CoverType.GAME -> "Game · In development"
            CoverType.NEWS -> "News App · Daily Digest"
            CoverType.CALCULATOR -> "Calculator · Better Calculator"
        }
        OutlinedButton(onClick={onSelect(type)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_choice_${type.name.lowercase()}")) {
            Text(if(selected==type) "✓  $label" else label)
        }
    }
}
