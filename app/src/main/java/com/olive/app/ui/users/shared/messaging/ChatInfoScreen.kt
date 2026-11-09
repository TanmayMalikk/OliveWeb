// All chat roles: shows conversation members and shared items away from the message timeline.
package com.olive.app.ui.users.shared.messaging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.ChatMessage
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import java.io.File

private val ChatInfoBackground = Color(0xFFF8F7F1)
private val ChatInfoCard = Color.White
private val ChatInfoBorder = Color(0xFFD8DDCE)
private val ChatInfoDanger = Color(0xFF9D3E37)

private data class SharedChatItem(
    val key: String,
    val kind: String,
    val id: String,
    val title: String,
    val senderUid: String,
)

// Reads only the structured share messages so ordinary chat text stays out of the shared-items list.
private fun sharedChatItems(messages: List<ChatMessage>): List<SharedChatItem> = messages
    .asSequence()
    .filterNot(ChatMessage::unsent)
    .mapNotNull { message ->
        val parts = message.body.split(":", limit = 3)
        val kind = parts.firstOrNull().orEmpty()
        if (kind !in setOf(
                "SHARED_MEDIA", "SHARED_NOTE", "SHARED_INCIDENT", "SHARED_SAFETY_PLAN", "SHARED_LOCATION"
            )
        ) return@mapNotNull null
        val id = parts.getOrNull(1).orEmpty()
        SharedChatItem(
            key = "$kind:${id.ifBlank { message.id }}",
            kind = kind,
            id = id,
            title = parts.getOrNull(2).orEmpty(),
            senderUid = message.senderUid,
        )
    }
    .distinctBy(SharedChatItem::key)
    .toList()

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
    val personalViewer = session.accountRole in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)
    val sharedItems = sharedChatItems(session.messages.chatMessages)
    val canAddPeople = conversation.closedAt == 0L && when {
        conversation.type == "SUPPORT" -> session.accountRole == AccountRole.USER && conversation.ownerUid == currentUid
        else -> personalViewer
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
        if (created != null) {
            session.messages.openConversation(created)
            onClose()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().testTag("chat_info_screen"),
            color = ChatInfoBackground,
        ) {
            Scaffold(
                containerColor = ChatInfoBackground,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().background(Color.White)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                },
            ) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
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
                                stringResource(R.string.chat_member_count, conversation.participantIds.size),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        HorizontalDivider(color = ChatInfoBorder)
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
                            organizationMember -> stringResource(R.string.chat_organization_member)
                            anonymousPersonalMember -> "Ticket ${conversation.participantTicketNumber(uid)}"
                            else -> conversation.participantUsernames[uid].orEmpty().ifBlank { stringResource(R.string.account) }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ChatInfoCard),
                            border = BorderStroke(1.dp, ChatInfoBorder),
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
                            trailingContent = if (
                                conversation.type == "GROUP" && conversation.ownerUid == currentUid &&
                                uid != currentUid && conversation.participantIds.size > 2
                            ) ({
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
                            colors = androidx.compose.material3.ListItemDefaults.colors(
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
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = ChatInfoDanger,
                                ),
                                border = BorderStroke(1.dp, ChatInfoDanger.copy(alpha = .45f)),
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
                            "SHARED_MEDIA" -> SharedMediaMessage(
                                item.id,
                                item.title,
                                session,
                                item.senderUid == currentUid,
                                onOpenMedia,
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
        }
    }
}
