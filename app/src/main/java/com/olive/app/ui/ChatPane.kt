package com.olive.app.ui

import androidx.compose.ui.semantics.heading
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
// UI sizing/color: chat bubbles cap at 340dp, sent bubbles use OliveGreen, and the header stays at least 76dp tall.
@Composable internal fun ChatPane(session: SessionViewModel, currentUid: String, showBack: Boolean,
    exit: (() -> Unit)?, modifier: Modifier=Modifier) {
    val uiText=rememberResourceText()
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
            delay(120.milliseconds)
            listState.animateScrollToItem(session.chatMessages.lastIndex)
        }
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
                                Text(chat.body,fontStyle=if(chat.unsent) androidx.compose.ui.text.font.FontStyle.Italic else null)
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
            OutlinedTextField(draft,{if(it.length<=2_000) draft=it},label={Text(stringResource(R.string.message))},
                modifier=Modifier.weight(1f).testTag("message_body").keepVisibleWhileTyping(),maxLines=4)
            Button(onClick={session.sendMessage(draft) { draft="" }},enabled=draft.isNotBlank() && !session.messagingBusy,
                modifier=Modifier.testTag("send_message")) { Text(stringResource(R.string.send)) }
        }
        }
    }
    if(exit!=null && session.chatQuickExitHintVisible && session.screen==SessionScreen.Safe && !session.privacyBlackout) {
        androidx.compose.ui.window.Dialog(onDismissRequest=session::dismissChatQuickExitHint) {
            Surface(shape=MaterialTheme.shapes.large,color=OliveGreen,contentColor=Color.White,
                shadowElevation=14.dp,border=BorderStroke(2.dp,Color.White.copy(alpha=.9f)),
                modifier=Modifier.fillMaxWidth().widthIn(max=360.dp).testTag("chat_quick_exit_hint")) {
                Column(Modifier.padding(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(uiText(R.string.ui_quick_exit_moved_here),fontSize=20.sp,lineHeight=25.sp,
                        fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=Color.White)
                    Text(uiText(R.string.ui_while_you_are_in_a_chat_use_exit_in_the_top_right_corner),
                        fontSize=16.sp,lineHeight=22.sp,color=Color.White)
                    Button(onClick=session::dismissChatQuickExitHint,
                        colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=OliveGreen),
                        modifier=Modifier.align(Alignment.End).heightIn(min=46.dp).testTag("dismiss_chat_quick_exit_hint")) {
                        Text(uiText(R.string.ui_okay),fontSize=16.sp)
                    }
                    TextButton(onClick=exit,modifier=Modifier.align(Alignment.End).testTag("chat_hint_quick_exit")) {
                        Text(stringResource(R.string.quick_exit),color=Color.White)
                    }
                }
            }
        }
    }
    }
}
