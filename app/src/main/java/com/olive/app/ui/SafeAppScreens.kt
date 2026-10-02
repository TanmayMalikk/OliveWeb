package com.olive.app.ui

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// USER + SAFE CONTACT: Olive Branch routes to the private profile, settings, notes, activity, and messaging features.
// UI sizing/color: the safe home uses the shared form grid, OliveGreen branding, Muted guidance, and responsive feature tiles.
@Composable internal fun SafeArea(session: SessionViewModel, exit: ()->Unit) {
    val profile=session.profile ?: return
    AnimatedContent(targetState=session.page,label="safe page",
        transitionSpec={
            (fadeIn(tween(180))+slideInHorizontally(tween(180)) { it/12 })
                .togetherWith(fadeOut(tween(120))+slideOutHorizontally(tween(120)) { -it/16 })
        }) { targetPage -> when(targetPage) {
        SafePage.Home -> FormColumn {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                    LocalizedText(if(profile.role==AccountRole.SAFE_CONTACT) "Safe Contact space" else "Your safe space",style=MaterialTheme.typography.headlineMedium)
                    LocalizedText(if(profile.role==AccountRole.SAFE_CONTACT) "Use Quick exit from any page to lock Olive Branch." else "Use Quick exit from any page to lock this area and return to your cover app.",color=Muted)
                }
                IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.align(Alignment.TopEnd).testTag("safe_settings")) {
                    Icon(Icons.Outlined.Settings,"Settings")
                }
            }
            ModeNotice(session)
            Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_profile")) { LocalizedText("My profile") }
            Button(onClick={session.navigate(SafePage.Activity)},modifier=Modifier.fillMaxWidth().testTag("safe_activity")) { LocalizedText("Account activity") }
            LocalizedText("Private features",style=MaterialTheme.typography.titleLarge)
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
                                LocalizedText("Notes & incidents",fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        } else if(number==2) Button(onClick={session.navigate(SafePage.Messages)},
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF7D9274),contentColor=Color.White),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_messages")) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.ChatBubbleOutline,"Messages",Modifier.size(32.dp))
                                LocalizedText("Messages",fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        } else OutlinedButton(onClick={},enabled=false,
                            colors=ButtonDefaults.outlinedButtonColors(disabledContainerColor=Color(0xFFE7EDE2),disabledContentColor=Muted),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("future_feature_$number")) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.AddCircleOutline,"Future feature",Modifier.size(30.dp))
                                LocalizedText("Future feature $number",fontSize=17.sp,lineHeight=21.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                            }
                        }
                    }
                }
            }
            if(session.pendingSync) {
                LocalizedText("Profile changes are saved on this device and awaiting cloud sync.")
                OutlinedButton(onClick=session::sync,enabled=!session.busy) { LocalizedText("Sync changes") }
            }
            LocalizedText(if(profile.role==AccountRole.SAFE_CONTACT) "Leaving the app locks Olive Branch." else "Leaving the app locks this area. A cover app does not make Olive Branch impossible to discover.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Status(session)
            TextButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.testTag("sign_out")) { LocalizedText("Sign out on this device") }
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
                    LocalizedText(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                    if(value.isBlank()) LocalizedText("Not provided")
                    else if(label=="Preferred language") LocalizedText(value)
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
@Composable private fun MessagesScreen(session: SessionViewModel, exit: ()->Unit) {
    val active=session.activeConversation
    val currentUid=session.coverAccountId.orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=720.dp
        if(wide) Row(Modifier.fillMaxSize().padding(bottom=96.dp)) {
            MessagingSidebar(session,currentUid,Modifier.width(340.dp).fillMaxHeight())
            VerticalDivider()
            if(active==null) Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center) {
                LocalizedText("Select a friend or conversation to start messaging.",color=Muted)
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
                LocalizedText("Friends & messages",style=MaterialTheme.typography.headlineSmall)
            }
            if(session.localMode) LocalizedText("Messaging needs a Firebase-connected build.",color=Muted)
        }
        if(session.messagingNeedsReconnect) item {
            Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFFFF7E1),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    LocalizedText("Reconnect your account",style=MaterialTheme.typography.titleMedium)
                    LocalizedText("Your local Olive Branch account is locked safely, but Firebase needs your password again before friends and messages can load.",color=Muted)
                    Field("Account password",reconnectPassword,"messaging_reconnect_password",secret=true,enabled=!session.messagingBusy) {
                        reconnectPassword=it.take(128)
                    }
                    Button(onClick={session.reconnectMessaging(reconnectPassword) { reconnectPassword="" }},
                        enabled=reconnectPassword.isNotBlank() && !session.messagingBusy,
                        modifier=Modifier.fillMaxWidth().testTag("messaging_reconnect")) { LocalizedText("Reconnect") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query,{if(it.length<=24) query=it},label={LocalizedText("Username")},singleLine=true,
                    modifier=Modifier.weight(1f).testTag("message_username"))
                Button(onClick={session.searchUsers(query)},enabled=query.isNotBlank() && !session.messagingBusy && !session.localMode && !session.messagingNeedsReconnect,
                    modifier=Modifier.testTag("search_users")) { LocalizedText("Search") }
            }
        }
        if(session.messagingBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Status(session) }
        if(session.userSearchResults.isNotEmpty()) {
            item { LocalizedText("Search results",style=MaterialTheme.typography.titleMedium) }
            items(session.userSearchResults,key={"search-${it.uid}"}) { user ->
                val relationship=session.relationshipFor(user.uid)
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFF3F6F0),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(user.username,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        when(relationship) {
                            FriendRelationship.AddFriend -> Button(onClick={session.sendFriendRequest(user)},enabled=!session.messagingBusy) { LocalizedText("Add Friend") }
                            FriendRelationship.RequestSent -> AssistChip(onClick={},enabled=false,label={LocalizedText("Request Sent")})
                            FriendRelationship.IncomingRequest -> AssistChip(onClick={},enabled=false,label={LocalizedText("Incoming Request")})
                            FriendRelationship.Friends -> AssistChip(onClick={},enabled=false,label={LocalizedText("Friends")})
                        }
                    }
                }
            }
        }
        if(session.incomingFriendRequests.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clickable { pendingExpanded=!pendingExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(pendingExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                    LocalizedText("Incoming Friend Requests",style=MaterialTheme.typography.titleMedium)
                    Badge(Modifier.padding(start=8.dp)) { Text(session.incomingFriendRequests.size.toString()) }
                }
            }
            if(pendingExpanded) items(session.incomingFriendRequests,key={"incoming-${it.id}"}) { request ->
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFFFF7E1),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(request.senderUsername,style=MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={session.acceptFriendRequest(request)},enabled=!session.messagingBusy,
                                modifier=Modifier.testTag("accept_friend_"+request.id)) { LocalizedText("Accept") }
                            OutlinedButton(onClick={session.declineFriendRequest(request)},enabled=!session.messagingBusy,
                                modifier=Modifier.testTag("decline_friend_"+request.id)) { LocalizedText("Decline") }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { outgoingExpanded=!outgoingExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(outgoingExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                LocalizedText("Outgoing Friend Requests",style=MaterialTheme.typography.titleMedium)
                Badge(Modifier.padding(start=8.dp)) { Text(session.outgoingFriendRequests.size.toString()) }
            }
        }
        if(outgoingExpanded) {
            if(session.outgoingFriendRequests.isEmpty()) item { LocalizedText("No outgoing requests.",color=Muted) }
            items(session.outgoingFriendRequests,key={"outgoing-${it.id}"}) { request ->
                Surface(shape=MaterialTheme.shapes.medium,color=Color(0xFFF3F6F0),modifier=Modifier.animateItem().fillMaxWidth().animateContentSize()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(request.recipientUsername,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick={session.cancelFriendRequest(request)},enabled=!session.messagingBusy,
                            modifier=Modifier.testTag("cancel_friend_"+request.id)) { LocalizedText("Cancel Request") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { friendsExpanded=!friendsExpanded }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(friendsExpanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
                LocalizedText("Friends",style=MaterialTheme.typography.titleMedium)
                Badge(Modifier.padding(start=8.dp)) { Text(session.friends.size.toString()) }
            }
        }
        if(friendsExpanded) {
            if(session.friends.isEmpty()) item { LocalizedText("No friends yet.",color=Muted) }
            items(session.friends,key={"friend-${it.friendshipId}"}) { friend ->
                OutlinedButton(onClick={session.startConversation(friend)},enabled=!session.messagingBusy,
                    modifier=Modifier.animateItem().fillMaxWidth().testTag("friend_"+friend.uid)) {
                    Text(friend.username,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Start)
                    LocalizedText("Message")
                }
            }
        }
        item { LocalizedText("Direct messages",style=MaterialTheme.typography.titleMedium) }
        if(session.conversations.isEmpty()) item { LocalizedText("No conversations yet.",color=Muted) }
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
    exit: ()->Unit, modifier: Modifier=Modifier) {
    val active=session.activeConversation ?: return
    var draft by rememberSaveable(active.id) { mutableStateOf("") }
    val listState=rememberLazyListState()
    LaunchedEffect(session.chatMessages.size,session.chatMessages.lastOrNull()?.body) {
        if(session.chatMessages.isNotEmpty()) listState.animateScrollToItem(session.chatMessages.lastIndex)
    }
    Column(modifier.padding(start=20.dp,end=20.dp,top=12.dp,bottom=12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showBack) IconButton(onClick=session::closeConversation,modifier=Modifier.testTag("messages_conversation_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All conversations")
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                LocalizedText("Direct message",style=MaterialTheme.typography.labelLarge,color=Muted)
                Text(active.peerUsername(currentUid).lowercase(java.util.Locale.ROOT),style=MaterialTheme.typography.titleLarge)
            }
            Button(onClick=exit,contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp),
                modifier=Modifier.heightIn(min=44.dp).testTag("quick_exit")) { Text("EXIT") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(session.chatMessages.isEmpty()) item {
                LocalizedText("No messages yet. Say hello when you are ready.",color=Muted,modifier=Modifier.padding(12.dp))
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
                                    DropdownMenuItem(text={LocalizedText("Unsend message")},onClick={menuOpen=false;session.unsendMessage(chat)})
                                }
                            }
                        }
                    }
                }
            }
        }
        Status(session)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft,{if(it.length<=2_000) draft=it},label={LocalizedText("Message")},
                modifier=Modifier.weight(1f).testTag("message_body"),maxLines=4)
            Button(onClick={session.sendMessage(draft) { draft="" }},enabled=draft.isNotBlank() && !session.messagingBusy,
                modifier=Modifier.testTag("send_message")) { LocalizedText("Send") }
        }
    }
}


// Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor.
// UI sizing/color: note editing uses 24dp sides, a 300dp minimum body field, yellow pinned icons, and an adaptive 160dp grid.
@Composable private fun PrivateNotes(session: SessionViewModel) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<PrivateEntryKind?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val language=LocalOliveLanguage.current
    val formatter=remember(language) { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z",
        if(language=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA).withZone(ZoneId.systemDefault()) }
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
        PrivateEntryEditLog(logEntry,formatter,language) { logEntryId=null }
        return
    }
    if(editorOpen) {
        val entry=session.privateEntries.firstOrNull { it.id==editingId }
        val editorListState=rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().oliveScrollbar(editorListState),state=editorListState,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={editorOpen=false},modifier=Modifier.testTag("private_notes_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText(if(editingId==null) "New private entry" else "Edit private entry",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(entry!=null) IconButton(onClick={session.togglePrivateEntryPin(entry.id)},enabled=!session.busy,modifier=Modifier.testTag("private_pin_editor")) {
                    Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted)
                }
                if(entry!=null) IconButton(onClick={session.trashPrivateEntry(entry.id);editorOpen=false},enabled=!session.busy,
                    modifier=Modifier.testTag("delete_private_entry")) { Icon(Icons.Outlined.DeleteOutline,"Move to ScratchPad") }
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(PrivateEntryKind.Note to "Note",PrivateEntryKind.Incident to "Incident").forEach { (value,label) ->
                    FilterChip(selected=kind==value,onClick={kind=if(kind==value) null else value},label={LocalizedText(label)},
                        modifier=Modifier.weight(1f).testTag("private_kind_${value.name.lowercase()}"))
                }
            } }
            item { Field("Title (optional)",title,"private_entry_title",enabled=!session.busy) { title=it.take(120) } }
            item { OutlinedTextField(value=body,onValueChange={body=it.take(10_000)},label={LocalizedText("Private details")},
                modifier=Modifier.fillMaxWidth().heightIn(min=300.dp).testTag("private_entry_body"),enabled=!session.busy) }
            item { Button(onClick={
                session.savePrivateEntry(editingId,kind,title,body)
                if(body.isNotBlank()) editorOpen=false
            },enabled=!session.busy && body.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save_private_entry")) {
                LocalizedText(if(editingId==null) "Save private entry" else "Update private entry")
            } }
            if(entry?.edits?.isNotEmpty()==true) item { TextButton(onClick={logEntryId=entry.id},modifier=Modifier.fillMaxWidth().testTag("private_edit_log")) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            } }
            item { Status(session) }
        }
        return
    }
    val trashed=session.privateEntries.filter { it.deletedAt!=null }.sortedByDescending { it.deletedAt }
    if(trashOpen) {
        ScratchPad(trashed,formatter,language,{ id ->
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
            LocalizedText("Notes & incidents",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            IconButton(onClick={trashOpen=true},modifier=Modifier.testTag("open_scratchpad")) {
                BadgedBox(badge={if(trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) }}) {
                    Icon(Icons.Outlined.DeleteOutline,"ScratchPad")
                }
            }
            FilledTonalIconButton(onClick={openEditor(null)},enabled=!session.busy,modifier=Modifier.testTag("new_private_entry")) { Icon(Icons.Outlined.Add,"New note") }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { OutlinedTextField(search,{search=it.take(200)},modifier=Modifier.fillMaxWidth().testTag("private_notes_search"),
            placeholder={LocalizedText("Search notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,
            trailingIcon=if(search.isNotEmpty()) {{IconButton(onClick={search=""}) { Icon(Icons.Outlined.Close,"Clear search") }}} else null) }
        if(session.message!=null || session.busy) item(span={GridItemSpan(maxLineSpan)}) { Status(session) }
        if(filtered.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(search.isBlank()) "No private entries yet." else "No notes found.",color=Muted) }
        if(pinned.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText("Pinned",style=MaterialTheme.typography.titleMedium,color=OliveGreen) }
        gridItems(pinned,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
        if(others.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(pinned.isEmpty()) "All notes" else "Others",style=MaterialTheme.typography.titleMedium) }
        gridItems(others,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
    }
}

// UI sizing/color: ScratchPad cards use a 1dp black outline, warm paper fill, 16dp padding, and Material text sizes.
@Composable private fun ScratchPad(entries: List<PrivateEntry>, formatter: DateTimeFormatter, language: String,
    restore: (String)->Unit, close: ()->Unit) {
    val now=System.currentTimeMillis()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=close,modifier=Modifier.testTag("close_scratchpad")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText("ScratchPad",style=MaterialTheme.typography.headlineSmall)
            }
            LocalizedText("Deleted notes stay here for 30 days, then their private contents are permanently erased.",color=Muted)
        }
        if(entries.isEmpty()) item { LocalizedText("ScratchPad is empty.",color=Muted) }
        items(entries,key={it.id}) { entry ->
            Card(Modifier.animateItem().fillMaxWidth().testTag("scratchpad_entry"),border=BorderStroke(1.dp,Color.Black),
                colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",color=OliveGreen) }
                    if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium)
                    Text(entry.body,maxLines=5,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${entry.trashDaysRemaining(now)} ${translate("days remaining",language)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text(formatter.format(Instant.ofEpochMilli(entry.deletedAt ?: entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    OutlinedButton(onClick={restore(entry.id)},modifier=Modifier.fillMaxWidth().testTag("restore_private_entry")) {
                        Icon(Icons.Outlined.RestoreFromTrash,null);Spacer(Modifier.width(8.dp));LocalizedText("Restore")
                    }
                }
            }
        }
    }
}

// UI sizing/color: revision cards use 16dp padding, black outlines, soft olive badges, and small Muted timestamps.
@Composable private fun PrivateEntryEditLog(entry: PrivateEntry, formatter: DateTimeFormatter, language: String, close: ()->Unit) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick=close,modifier=Modifier.testTag("close_private_edit_log")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);LocalizedText("Close")
            }
            LocalizedText("Edit log",style=MaterialTheme.typography.headlineMedium)
            Text(entry.title.ifBlank { translate("Untitled entry",language) },style=MaterialTheme.typography.titleMedium,color=Muted)
            Text("${entry.edits.size} ${translate("revisions",language)}",style=MaterialTheme.typography.bodySmall,color=Muted)
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
                            Text("${translate("Revision",language)} $revision",style=MaterialTheme.typography.titleMedium)
                            Text(formatter.format(Instant.ofEpochMilli(edit.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                    edit.changes.forEach { change ->
                        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=Color(0xFFF5F3EC)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                LocalizedText(change.field,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                                LocalizedText("Before",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.before.ifBlank { "Empty" },language))
                                HorizontalDivider(Modifier.padding(vertical=3.dp))
                                LocalizedText("After",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.after.ifBlank { "Empty" },language))
                            }
                        }
                    }
                }
            }
        }
    }
}

// UI sizing/color: note cards use a 1dp outline, warm paper fill, 14dp padding, and up to three title lines.
@Composable private fun PrivateNoteCard(entry: PrivateEntry, formatter: DateTimeFormatter, language: String,
    open: ()->Unit, pin: ()->Unit, openLog: ()->Unit, modifier: Modifier=Modifier) {
    Card(onClick=open,modifier=modifier.fillMaxWidth().testTag("private_entry_row"),
        border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",style=MaterialTheme.typography.labelMedium,color=OliveGreen,modifier=Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
                IconButton(onClick=pin,modifier=Modifier.size(34.dp).testTag("private_pin")) { Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted) }
            }
            if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(entry.body,maxLines=8,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text((if(entry.edits.isNotEmpty()) "${translate("Edited",language)} · " else "")+formatter.format(Instant.ofEpochMilli(entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(entry.edits.isNotEmpty()) TextButton(onClick=openLog,modifier=Modifier.testTag("private_edit_log"),contentPadding=PaddingValues(0.dp)) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            }
        }
    }
}
// UI sizing/text: this back action keeps Material button sizing and localized text rather than a custom fixed size.
@Composable private fun SafeBack(session: SessionViewModel) {
    TextButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText("Back") }
}

// Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them.
// UI sizing/text: contact cards use 16dp padding and Material title text; edits stay in shared full-width fields.
@Composable private fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    FormColumn {
        TextButton(onClick=session::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText("Settings") }
        Heading("Safe contacts", "Save trusted people here. Olive does not send messages or make calls.")
        profile.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(contact.name,style=MaterialTheme.typography.titleMedium)
                    Text(contact.details)
                    Row {
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Opened contact editor");editing=contact.id; name=contact.name; details=contact.details},enabled=!session.busy) { LocalizedText("Edit") }
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Selected remove contact");session.saveContacts(profile.contacts.filterNot { it.id==contact.id }); if(editing==contact.id) {editing=null; name=""; details=""}},enabled=!session.busy) { LocalizedText("Remove") }
                    }
                }
            }
        }
        if(profile.contacts.isEmpty()) LocalizedText("No contacts yet. Adding one is optional.",color=Muted)
        LocalizedText(if(editing==null) "Add a contact" else "Edit contact",style=MaterialTheme.typography.titleMedium)
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
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("save_contact")) { LocalizedText("Save contact") }
        if(editing!=null) TextButton(onClick={editing=null; name=""; details=""}) { LocalizedText("Cancel edit") }
    }
}


// Same two-language picker on the safe side, kept separate so the layout stays simple.
// UI sizing/text: the language control fills its parent so translated language names have the full form width.
@Composable internal fun LanguagePicker(value: String, enabled: Boolean = true, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    Box {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("language")) { LocalizedText("Language (required)"); Spacer(Modifier.weight(1f)); LocalizedText(if(value=="French") "Français" else "English") }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            listOf("English","French").forEach { language -> DropdownMenuItem(text={LocalizedText(if(language=="French") "Français" else "English")},onClick={onChange(language);open=false}) }
        }
    }
}
// Edit private profile, language, and PIN-guarded trigger settings in this section.
// UI sizing/text: settings reuse shared form spacing and Material titleLarge section headings so translated labels can wrap.
@Composable private fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
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
        OutlinedButton(onClick={session.navigate(SafePage.Contacts)},modifier=Modifier.fillMaxWidth().testTag("safe_contacts")) { LocalizedText("Safe contacts") }
        if(userRole) {
            // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
            CoverPicker(profile.coverType,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected cover app");session.changeCover(it) }
            LocalizedText("Olive and Daily Digest use search for safe entry. Better Calculator uses your device PIN followed by two equals taps.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Field("Full name (required)",fullName,"settings_full_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused full name")}) { fullName=it.take(120) }
        if(userRole) {
            Field("Names of people experiencing abuse (optional)",affected,"settings_affected",onFocus={session.recordSettingsInteraction("Olive Branch","Focused affected names")}) { affected=it.take(500) }
            Field("Abuser's name (optional)",abuserName,"settings_abuser_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused abuser name")}) { abuserName=it.take(120) }
        }
        BirthdayPicker(dob,"settings_dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected language");language=it }
        if(userRole) Field("Brief description of your situation (optional, recommended)",situation,"settings_situation",multiline=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused situation")}) { situation=it.take(2000) }
        LocalizedText("Location · city required",style=MaterialTheme.typography.titleLarge)
        AddressFields(address,cityOnlyRequired=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused address: $it")}) { address=it }
        Button(onClick={session.saveSettings(fullName,affected,abuserName,dob,language,situation,address)},enabled=!session.busy,modifier=Modifier.testTag("save_settings")) { LocalizedText("Save settings") }
        HorizontalDivider()
        LocalizedText("Change account password",style=MaterialTheme.typography.titleLarge)
        LocalizedText("Your username cannot be changed.")
        Field("Current password",currentPassword,"current_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current password")}) { currentPassword=it.take(24) }
        Field("New password",newPassword,"new_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new password")}) { newPassword=it.take(24) }
        Field("Confirm new password",confirmPassword,"confirm_new_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused password confirmation")}) { confirmPassword=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmPassword);currentPassword="";newPassword="";confirmPassword=""},enabled=!session.busy,modifier=Modifier.testTag("change_password")) { LocalizedText("Change password") }
        if(userRole) {
            HorizontalDivider()
            LocalizedText("Change device PIN",style=MaterialTheme.typography.titleLarge)
            Field("Current device PIN",currentDevicePin,"current_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current PIN")}) { currentDevicePin=it.filter(Char::isDigit).take(6) }
            Field("New device PIN",newDevicePin,"new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new PIN")}) { newDevicePin=it.filter(Char::isDigit).take(6) }
            Field("Confirm new device PIN",confirmDevicePin,"confirm_new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused PIN confirmation")}) { confirmDevicePin=it.filter(Char::isDigit).take(6) }
            Button(onClick={session.changePin(currentDevicePin,newDevicePin,confirmDevicePin);currentDevicePin="";newDevicePin="";confirmDevicePin=""},enabled=!session.busy,modifier=Modifier.testTag("change_device_pin")) { LocalizedText("Change device PIN") }
            HorizontalDivider()
            LocalizedText("Change trigger word",style=MaterialTheme.typography.titleLarge)
            LocalizedText("Enter your current device PIN to change the trigger word. Incorrect attempts count toward the five-attempt limit.")
            Field("New trigger word or phrase",trigger,"settings_trigger",secret=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger word")}) { trigger=it.take(64) }
            Field("Current PIN",pin,"settings_pin",secret=true,numeric=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger-change PIN")}) { pin=it.filter { c -> c in '0'..'9' }.take(6) }
            Button(onClick={session.changeTrigger(trigger,pin);pin="";trigger=""},enabled=!session.busy,modifier=Modifier.testTag("change_trigger")) { LocalizedText("Change trigger word") }
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
    val locale=if(LocalOliveLanguage.current=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA
    val display=value?.let { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",locale).withZone(zone).format(Instant.ofEpochMilli(it)) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={showDate=true},modifier=Modifier.weight(1f).testTag(tag),contentPadding=PaddingValues(horizontal=14.dp,vertical=12.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                LocalizedText(label,style=MaterialTheme.typography.labelMedium)
                LocalizedText(display ?: "Not selected",color=if(value==null) Muted else MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.AccessTime,null)
        }
        if(value!=null) TextButton(onClick={onChange(null)}) { LocalizedText("Clear") }
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
            }) { LocalizedText("Next") }
        },dismissButton={TextButton(onClick={showDate=false}) { LocalizedText("Cancel") }}) {
            DatePicker(state=picker,modifier=Modifier.testTag("${tag}_calendar"))
        }
    }
    if(showTime) key(pendingDate,value) {
        val initial=value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: java.time.LocalTime.now(zone)
        val picker=rememberTimePickerState(initialHour=initial.hour,initialMinute=initial.minute,is24Hour=true)
        AlertDialog(onDismissRequest={showTime=false},title={LocalizedText("Select time")},text={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            TimePicker(state=picker,modifier=Modifier.testTag("${tag}_clock"))
        }},
            confirmButton={TextButton(onClick={
                val date=pendingDate ?: LocalDate.now(zone)
                onChange(date.atTime(picker.hour,picker.minute).atZone(zone).toInstant().toEpochMilli());showTime=false
            }) { LocalizedText("Apply") }},dismissButton={TextButton(onClick={showTime=false}) { LocalizedText("Cancel") }})
    }
}

// Activity stays grouped on the overview, then See more opens one searchable category at a time.
// UI sizing/text: activity uses 24dp page padding, 14dp row gaps, 16dp cards, titleLarge sections, and bodySmall timestamps.
@Composable private fun ActivityHistory(session: SessionViewModel) {
    val language=LocalOliveLanguage.current
    val formatter=DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss z",
        if(language=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA).withZone(ZoneId.systemDefault())
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
        item { TextButton(onClick=session::activityBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText(if(selected==null) "Safe home" else "All activity") } }
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
            item { LocalizedText("Settings history",style=MaterialTheme.typography.titleLarge) }
            if(settings.isEmpty()) item { LocalizedText("No settings activity yet.") }
            items(visible(ActivityCategory.Settings,settings),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("settings_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${translate(event.area,language)} · ${translate(event.action,language)}",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.changes.forEach { change -> Text("${translate(change.field,language)}: ${translate(change.before.ifBlank { "Empty" },language)} → ${translate(change.after.ifBlank { "Empty" },language)}",style=MaterialTheme.typography.bodySmall) }
                } }
            }
            if(selected==null && settings.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Settings)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Triggers) {
            item { HorizontalDivider();LocalizedText("Trigger history",style=MaterialTheme.typography.titleLarge) }
            if(triggers.isEmpty()) item { LocalizedText("No trigger activity yet.") }
            items(visible(ActivityCategory.Triggers,triggers),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("trigger_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    LocalizedText(event.source,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(event.pinAttempts>0) Text("${translate("PIN attempts",language)}: ${event.pinAttempts} · ${translate(event.lastResult,language)}")
                } }
            }
            if(selected==null && triggers.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Triggers)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.PinAttempts) {
            item { HorizontalDivider();LocalizedText("PIN attempt history",style=MaterialTheme.typography.titleLarge) }
            if(attempts.isEmpty()) item { LocalizedText("No failed attempts recorded.") }
            items(visible(ActivityCategory.PinAttempts,attempts),key={it.id}) { attempt ->
                Card(Modifier.fillMaxWidth().testTag("attempt_row")) { Column(Modifier.padding(16.dp)) {
                    LocalizedText("Incorrect PIN");LocalizedText(formatter.format(Instant.ofEpochMilli(attempt.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                } }
            }
            if(selected==null && attempts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.PinAttempts)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Checkouts) {
            item { HorizontalDivider();LocalizedText("Checkout attempts",style=MaterialTheme.typography.titleLarge) }
            if(checkouts.isEmpty()) item { LocalizedText("No checkout attempts yet.") }
            items(visible(ActivityCategory.Checkouts,checkouts),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("safe_checkout_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.foods.forEach { LocalizedText("${it.quantity} × ${it.food}") };LocalizedText(event.error)
                } }
            }
            if(selected==null && checkouts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Checkouts)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Privacy) {
            item { HorizontalDivider();LocalizedText("Privacy events",style=MaterialTheme.typography.titleLarge) }
            if(privacy.isEmpty()) item { LocalizedText("No capture events reported by Android.") }
            items(visible(ActivityCategory.Privacy,privacy),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { LocalizedText(event.action);LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted) } }
            }
            if(selected==null && privacy.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Privacy)}) { LocalizedText("See more") } }
        }
    }
}

// UI sizing/text: cover choices fill the form width and use Material button text so longer translated names can wrap.
@Composable internal fun CoverPicker(selected: CoverType, enabled: Boolean, onSelect: (CoverType) -> Unit) {
    LocalizedText("Choose your cover app",style=MaterialTheme.typography.titleLarge)
    LocalizedText("The app stays open. Its icon and name update after you return to the Home screen.",style=MaterialTheme.typography.bodySmall,color=Muted)
    CoverType.entries.forEach { type ->
        val label=when(type) {
            CoverType.FOOD -> "Food Delivery App · Olive"
            CoverType.GAME -> "Game · In development"
            CoverType.NEWS -> "News App · Daily Digest"
            CoverType.CALCULATOR -> "Calculator · Better Calculator"
        }
        OutlinedButton(onClick={onSelect(type)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_choice_${type.name.lowercase()}")) {
            LocalizedText(if(selected==type) "✓  $label" else label)
        }
    }
}
