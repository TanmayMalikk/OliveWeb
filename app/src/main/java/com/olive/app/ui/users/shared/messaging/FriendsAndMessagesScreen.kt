// All safe-app roles: shows friends, direct messages, and staff support chats.
package com.olive.app.ui.users.shared.messaging

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*


import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.shared.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.SupportAgent
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
@Composable
internal fun MessagesScreen(session: SessionViewModel, exit: (() -> Unit)? = null) {
    val active = session.messages.activeConversation
    val currentUid = session.coverAccountId.orEmpty()
    // Keep the chosen list visible while a phone opens a chat and returns from it.
    var showingArchived by rememberSaveable { mutableStateOf(false) }
    val chatExit = exit.takeUnless {
        session.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        // Wide screens place the conversation list and open chat beside each other.
        if (wide) Row(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
            MessagingSidebar(
                session, currentUid, portalExit(session, exit), showingArchived,
                onShowingArchivedChange = { showingArchived = it },
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            )
            VerticalDivider()
            if (active == null) Box(
                Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.select_a_friend_or_conversation_to_start_messaging),
                    color = Muted
                )
            } else ChatPane(session, currentUid, false, chatExit, Modifier.weight(1f).fillMaxHeight())
        } else if (active == null) MessagingSidebar(
            session, currentUid, portalExit(session, exit), showingArchived,
            onShowingArchivedChange = { showingArchived = it },
            modifier = Modifier.fillMaxSize(),
        )
        else {
            BackHandler { session.messages.closeConversation() }
            ChatPane(session, currentUid, true, chatExit, Modifier.fillMaxSize())
        }
    }
}

// Chooses whether Back returns to the dashboard or closes the organization portal.
private fun portalExit(session: SessionViewModel, exit: (() -> Unit)?) =
    if (session.screen == SessionScreen.Portal) exit else null

// UI sizing/color: the sidebar uses 20dp side padding, 10dp list gaps, soft status-card colors, and Material title styles.
@Composable
private fun MessagingSidebar(
    session: SessionViewModel,
    currentUid: String,
    exit: (() -> Unit)?,
    showingArchived: Boolean,
    onShowingArchivedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val organizationAccount = session.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    var supportFilter by rememberSaveable { mutableStateOf(SUPPORT_TAG_SUPPORT) }
    val roleConversations = if (organizationAccount) session.messages.conversations.filter {
        it.type == "SUPPORT" && it.supportTag == supportFilter
    } else session.messages.conversations
    val shownConversations = roleConversations.filter {
        (it.closedAt > 0L || it.id in session.messages.archivedConversationIds) == showingArchived
    }
    val supportConversations = shownConversations.filter { it.type == "SUPPORT" }
    val directConversations = shownConversations.filter { it.type != "SUPPORT" }
    val archivedCount = roleConversations.count { it.closedAt > 0L || it.id in session.messages.archivedConversationIds }
    var conversationActions by remember { mutableStateOf<ChatConversation?>(null) }
    var noteTargets by remember { mutableStateOf<List<DirectoryUser>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var reconnectPassword by rememberSaveable { mutableStateOf("") }
    var friendsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingExpanded by rememberSaveable { mutableStateOf(false) }
    var outgoingExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = exit ?: session.navigation::back, modifier = Modifier.testTag("safe_home")) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        uiText(R.string.ui_previous_safe_page)
                    )
                }
                Text(
                    stringResource(if (organizationAccount) R.string.support_conversations else R.string.friends_messages),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
            }
            if (session.localMode) Text(
                stringResource(R.string.messaging_needs_a_firebase_connected_build),
                color = Muted
            )
        }
        if (session.messages.needsReconnect) item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Color(0xFFFFF7E1),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        uiText(R.string.ui_reconnect_your_account),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        uiText(R.string.ui_your_local_olive_branch_account_is_locked_safely_but_firebase_nee),
                        color = Muted
                    )
                    Field(
                        uiText(R.string.ui_account_password),
                        reconnectPassword,
                        "messaging_reconnect_password",
                        secret = true,
                        showSecretToggle = true,
                        enabled = !session.messages.busy
                    ) {
                        reconnectPassword = it.take(128)
                    }
                    Button(
                        onClick = {
                            session.messages.reconnectMessaging(reconnectPassword) {
                                reconnectPassword = ""
                            }
                        },
                        enabled = reconnectPassword.isNotBlank() && !session.messages.busy,
                        modifier = Modifier.fillMaxWidth().testTag("messaging_reconnect")
                    ) { Text(stringResource(R.string.reconnect)) }
                }
            }
        }
        // Hides friend controls for staff and admin accounts because support chats are assigned.
        if (organizationAccount) item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.staff_conversations_assigned_help),
                    modifier = Modifier.padding(14.dp),
                    color = OliveGreen,
                )
            }
        }
        if (organizationAccount) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    SUPPORT_TAG_PLAN_CONSULT to stringResource(R.string.support_filter_plan_consults),
                    SUPPORT_TAG_SUPPORT to stringResource(R.string.support_filter_support),
                    SUPPORT_TAG_NEEDS_ADMIN to stringResource(R.string.support_filter_needs_admin),
                ).forEach { (tag, label) ->
                    val unreadCount = session.messages.unreadCountForSupportTag(tag)
                    FilterChip(
                        selected = supportFilter == tag,
                        onClick = { supportFilter = tag },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(label)
                                if (unreadCount > 0) Badge {
                                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                                }
                            }
                        },
                        modifier = Modifier.testTag("support_filter_$tag"),
                    )
                }
            }
        }
        if (!organizationAccount) item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    query,
                    { if (it.length <= 24) query = it },
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("message_username")
                        .keepVisibleWhileTyping()
                )
                Button(
                    onClick = { session.messages.searchUsers(query) },
                    enabled = query.isNotBlank() && !session.messages.busy && !session.localMode && !session.messages.needsReconnect,
                    modifier = Modifier.testTag("search_users")
                ) { Text(stringResource(R.string.search)) }
            }
        }
        if (session.messages.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Status(session) }
        if (!organizationAccount) friendLists(
            session = session,
            pendingExpanded = pendingExpanded,
            onPendingExpandedChange = { pendingExpanded = it },
            outgoingExpanded = outgoingExpanded,
            onOutgoingExpandedChange = { outgoingExpanded = it },
            friendsExpanded = friendsExpanded,
            onFriendsExpandedChange = { friendsExpanded = it },
            onNoteTargets = { noteTargets = it },
        )
        if (showingArchived) item {
            Text(stringResource(R.string.archived_chats), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
        }
        item {
            Text(stringResource(R.string.support_conversations), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
        }
        // Explain how support starts when this account has no support tickets yet.
        if (supportConversations.isEmpty()) item {
            Text(stringResource(when {
                showingArchived -> R.string.no_archived_chats
                organizationAccount -> R.string.no_assigned_support_conversations
                else -> R.string.support_no_conversations
            }), color = Muted)
        }
        items(supportConversations, key = { "conversation-${it.id}" }) { conversation ->
            ConversationRow(
                conversation, currentUid, session.accountRole,
                Modifier.animateItem().fillMaxWidth().testTag("conversation_${conversation.id}"),
                unread = session.messages.isUnread(conversation),
                onOpen = { session.messages.openConversation(conversation) },
                onActions = { conversationActions = conversation },
            )
        }
        // Personal accounts also show friend requests and direct messages.
        if (!organizationAccount) {
            item {
                Text(stringResource(R.string.direct_messages), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
            }
            if (directConversations.isEmpty()) item {
                Text(stringResource(if (showingArchived) R.string.no_archived_chats
                    else R.string.no_conversations_yet), color = Muted)
            }
            items(directConversations, key = { "conversation-${it.id}" }) { conversation ->
                ConversationRow(
                    conversation, currentUid, session.accountRole,
                    Modifier.animateItem().fillMaxWidth().testTag("conversation_${conversation.id}"),
                    unread = session.messages.isUnread(conversation),
                    onOpen = { session.messages.openConversation(conversation) },
                    onActions = { conversationActions = conversation },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { onShowingArchivedChange(!showingArchived) },
                modifier = Modifier.fillMaxWidth().testTag("archived_chats"),
            ) {
                Text(
                    if (showingArchived) stringResource(R.string.back_to_messages)
                    else stringResource(R.string.archived_chats_count, archivedCount)
                )
            }
        }
    }
    conversationActions?.let { conversation ->
        ConversationActionsDialog(
            conversation = conversation,
            archived = conversation.closedAt > 0L || conversation.id in session.messages.archivedConversationIds,
            closed = conversation.closedAt > 0L,
            currentUid = currentUid,
            role = session.accountRole,
            friendUids = session.messages.friends.mapTo(mutableSetOf()) { it.uid },
            onArchive = {
                session.messages.setConversationArchived(
                    conversation,
                    conversation.closedAt == 0L && conversation.id !in session.messages.archivedConversationIds,
                )
                conversationActions = null
            },
            onNotes = {
                conversationActions = null
                noteTargets = it
            },
            onDismiss = { conversationActions = null },
        )
    }
    noteTargets?.let { targets ->
        PrivateContactNoteDialog(session, targets, onDismiss = { noteTargets = null })
    }
}

// shared safe app friends and messages screen shows this conversation row view so the user can view its details and use its actions.
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    currentUid: String,
    viewerRole: AccountRole,
    modifier: Modifier,
    unread: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val uiText = rememberResourceText()
    val organizationAccount = viewerRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    OutlinedCard(modifier.combinedClickable(onClick = onOpen, onLongClick = onActions)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (conversation.type == "SUPPORT") Icons.Outlined.SupportAgent
                else Icons.Outlined.PersonOutline,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                if (organizationAccount) Text(
                    supportTagLabel(conversation.supportTag),
                    style = MaterialTheme.typography.labelMedium,
                    color = OliveGreen,
                )
                Text(
                    conversation.displayName(
                        currentUid,
                        viewerRole,
                        conversation.anonymousStaffDisplayName(
                            uiText(R.string.support_chat_name),
                            uiText(R.string.support_ticket_consultation),
                            uiText(R.string.support_ticket_support),
                            uiText(R.string.support_ticket_escalation),
                        ),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        conversation.lastMessage == "Message unsent" -> uiText(R.string.message_unsent)
                        conversation.lastMessageId.isNotBlank() -> uiText(R.string.secure_message)
                        else -> uiText(R.string.new_conversation)
                    },
                    maxLines = 1,
                    color = Muted,
                )
            }
            if (unread) Badge { Text("1") }
        }
    }
}

// Formats the support tag label shown by secure messaging.
@Composable
private fun supportTagLabel(tag: String) = stringResource(
    when (tag) {
        SUPPORT_TAG_PLAN_CONSULT -> R.string.support_filter_plan_consults
        SUPPORT_TAG_NEEDS_ADMIN -> R.string.support_filter_needs_admin
        else -> R.string.support_filter_support
    }
)
