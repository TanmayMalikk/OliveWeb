package com.olive.app.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.olive.app.data.SafetyDocument
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
// UI sizing/color: chat bubbles cap at 340dp, sent bubbles use OliveGreen, and the header stays at least 76dp tall.
@Composable internal fun ChatPane(session: SessionViewModel, currentUid: String, showBack: Boolean,
    exit: (() -> Unit)?, modifier: Modifier=Modifier) {
    val uiText=rememberResourceText()
    val active=session.activeConversation ?: return
    var draft by rememberSaveable(active.id) { mutableStateOf("") }
    var showAttachmentPicker by rememberSaveable(active.id) { mutableStateOf(false) }
    var openedMedia by remember { mutableStateOf<Pair<SafetyDocument,File>?>(null) }
    val listState=rememberLazyListState()
    val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardVisible=imeBottom>0
    LaunchedEffect(session.chatMessages.size,session.chatMessages.lastOrNull()?.body) {
        if(session.chatMessages.isNotEmpty()) listState.animateScrollToItem(session.chatMessages.lastIndex)
    }
    LaunchedEffect(keyboardVisible) {
        if(keyboardVisible && session.chatMessages.isNotEmpty()) {
            delay(120.milliseconds)
            listState.animateScrollToItem(session.chatMessages.lastIndex)
        }
    }
    openedMedia?.let { (document,file) ->
        SafetyDocumentViewer(document,file) { file.delete();openedMedia=null }
        return
    }
    Box(modifier) {
    Column(Modifier.fillMaxSize().padding(start=20.dp,end=20.dp,top=12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showBack) IconButton(onClick=session::closeConversation,modifier=Modifier.testTag("messages_conversation_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.all_conversations))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.direct_message),style=MaterialTheme.typography.labelLarge,color=Muted)
                Text(active.peerUsername(currentUid).lowercase(Locale.ROOT),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
            }
            if(exit!=null) Button(onClick=exit,contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp),
                modifier=Modifier.heightIn(min=44.dp).testTag("quick_exit")) { Text(uiText(R.string.ui_exit)) }
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
                                ChatMessageBody(chat,session,mine) { document,file -> openedMedia=document to file }
                                if(chat.sentAt>0) Text(DateTimeFormatter.ofPattern(uiText(R.string.ui_mmm_d_hh_mm)).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(chat.sentAt)),
                                    style=MaterialTheme.typography.labelSmall,color=if(mine) Color.White.copy(alpha=.7f) else Muted)
                            }
                            if(mine && !chat.unsent) Box {
                                IconButton(onClick={menuOpen=true},modifier=Modifier.size(34.dp)) { Icon(Icons.Outlined.MoreVert,uiText(R.string.a11y_message_actions)) }
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
            IconButton(onClick={showAttachmentPicker=true},enabled=!session.messagingBusy,
                modifier=Modifier.testTag("attach_private_entry")) {
                Icon(Icons.Outlined.AttachFile,stringResource(R.string.attach_secure_entry))
            }
            OutlinedTextField(draft,{if(it.length<=2_000) draft=it},label={Text(stringResource(R.string.message))},
                modifier=Modifier.weight(1f).testTag("message_body").keepVisibleWhileTyping(),maxLines=4)
            Button(onClick={session.sendMessage(draft) { draft="" }},enabled=draft.isNotBlank() && !session.messagingBusy,
                modifier=Modifier.testTag("send_message")) { Text(stringResource(R.string.send)) }
        }
        }
    }
    if(exit!=null && session.chatQuickExitHintVisible && session.screen==SessionScreen.Safe && !session.privacyBlackout) {
        val density=LocalDensity.current
        Popup(alignment=Alignment.TopEnd,
            offset=IntOffset(with(density) { (-14).dp.roundToPx() },with(density) { 82.dp.roundToPx() }),
            onDismissRequest=session::dismissChatQuickExitHint,
            properties=PopupProperties(focusable=true,dismissOnBackPress=true,dismissOnClickOutside=true)) {
            Surface(shape=MaterialTheme.shapes.large,color=OliveGreen,contentColor=Color.White,
                shadowElevation=14.dp,border=BorderStroke(2.dp,Color.White.copy(alpha=.9f)),
                modifier=Modifier.widthIn(min=280.dp,max=340.dp).testTag("chat_quick_exit_hint").semantics {
                    paneTitle=uiText(R.string.ui_quick_exit_moved_here)
                }) {
                Column(Modifier.padding(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(uiText(R.string.ui_quick_exit_moved_here),fontSize=20.sp,lineHeight=25.sp,
                            fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=Color.White,
                            modifier=Modifier.weight(1f))
                        Icon(Icons.Outlined.NorthEast,null,tint=Color.White,modifier=Modifier.size(30.dp))
                    }
                    Text(uiText(R.string.ui_while_you_are_in_a_chat_use_exit_in_the_top_right_corner),
                        fontSize=16.sp,lineHeight=22.sp,color=Color.White)
                    Button(onClick=session::dismissChatQuickExitHint,
                        colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=OliveGreen),
                        modifier=Modifier.align(Alignment.End).heightIn(min=46.dp).testTag("dismiss_chat_quick_exit_hint")) {
                        Text(uiText(R.string.ui_okay),fontSize=16.sp)
                    }
                }
            }
        }
    }
    if(showAttachmentPicker) AttachmentPicker(session) { showAttachmentPicker=false }
    }
}

@Composable private fun ChatMessageBody(chat:com.olive.app.data.ChatMessage,session:SessionViewModel,mine:Boolean,
    onOpenMedia:(SafetyDocument,File)->Unit) {
    val parts=chat.body.split(":",limit=3)
    if(!chat.unsent && parts.firstOrNull()=="SHARED_MEDIA" && parts.size>=2) {
        SharedMediaMessage(parts[1],parts.getOrNull(2).orEmpty(),session,mine,onOpenMedia)
        return
    }
    if(!chat.unsent && parts.firstOrNull()=="SHARED_LOCATION") {
        Surface(onClick={session.navigate(SafePage.Location)},shape=MaterialTheme.shapes.medium,
            color=if(mine) Color.White.copy(alpha=.2f) else Color.Black.copy(alpha=.05f),
            modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("shared_live_location")) {
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.LocationOn,stringResource(R.string.shared_live_location))
                Column { Text(stringResource(R.string.shared_live_location),style=MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.open_location_map),fontWeight=androidx.compose.ui.text.font.FontWeight.Bold) }
            }
        }
        return
    }
    val shared=parts.firstOrNull() in setOf("SHARED_NOTE","SHARED_INCIDENT") && parts.size>=2
    if(chat.unsent || !shared) {
        Text(when { chat.unsent -> stringResource(R.string.message_unsent)
            chat.body.isBlank() -> stringResource(R.string.encrypted_message_unavailable)
            else -> chat.body },fontStyle=if(chat.unsent) androidx.compose.ui.text.font.FontStyle.Italic else null)
        return
    }
    val incident=parts[0]=="SHARED_INCIDENT"
    val title=parts.getOrNull(2).orEmpty().ifBlank { stringResource(R.string.encrypted_document) }
    Surface(onClick={session.openSharedPrivateEntry(parts[1])},shape=MaterialTheme.shapes.medium,
        color=if(mine) Color.White.copy(alpha=.2f) else Color.Black.copy(alpha=.05f),
        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(if(incident) Icons.Outlined.Warning else Icons.Outlined.Lock,
                stringResource(if(incident) R.string.shared_incident_report else R.string.shared_private_note))
            Column {
                Text(stringResource(if(incident) R.string.shared_incident_report else R.string.shared_private_note),
                    style=MaterialTheme.typography.labelSmall)
                Text(title,style=MaterialTheme.typography.bodyMedium,
                    fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }
}

@Composable private fun SharedMediaMessage(id:String,name:String,session:SessionViewModel,mine:Boolean,
    onOpen:(SafetyDocument,File)->Unit) {
    var opening by remember(id) { mutableStateOf(false) }
    Surface(onClick={
        val uid=session.coverAccountId ?: return@Surface
        if(opening) return@Surface
        opening=true
        session.documents.openShared(uid,id,{ document,local -> onOpen(document,local) },{ opening=false })
    },enabled=!opening,shape=MaterialTheme.shapes.medium,
        color=if(mine) Color.White.copy(alpha=.2f) else Color.Black.copy(alpha=.05f),
        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("shared_media_$id")) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(opening) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
            else Icon(Icons.Outlined.AttachFile,stringResource(R.string.shared_secure_file))
            Column {
                Text(stringResource(R.string.shared_secure_file),style=MaterialTheme.typography.labelSmall)
                Text(name.ifBlank { stringResource(R.string.encrypted_document) },style=MaterialTheme.typography.bodyMedium,
                    fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,maxLines=2)
            }
        }
    }
}
