// All safe-app roles: shows private notes linked to friends or assigned users.
package com.olive.app.ui.users.shared.messaging

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.users.shared.navigation.SessionViewModel

internal fun ChatConversation.privateNoteTargets(
    currentUid: String,
    role: AccountRole,
    friendUids: Set<String>,
): List<DirectoryUser> {
    val ids = if (type == "SUPPORT" && role in setOf(
            AccountRole.SAFE_CONTACT,
            AccountRole.STAFF,
            AccountRole.ADMIN,
        ) && ownerUid.isNotBlank()
    ) listOf(ownerUid) else participantIds.filter { it != currentUid }
    val allowedIds = if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) ids
    else ids.filter(friendUids::contains)
    return allowedIds.distinct().map { uid ->
        DirectoryUser(uid, participantUsernames[uid].orEmpty().ifBlank { uid })
    }
}

@Composable
internal fun ConversationActionsDialog(
    conversation: ChatConversation,
    archived: Boolean,
    closed: Boolean = false,
    currentUid: String,
    role: AccountRole,
    friendUids: Set<String>,
    onArchive: () -> Unit,
    onNotes: (List<DirectoryUser>) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = conversation.privateNoteTargets(currentUid, role, friendUids)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                conversation.displayName(
                    currentUid,
                    role,
                    conversation.anonymousStaffDisplayName(
                        stringResource(R.string.support_chat_name),
                        stringResource(R.string.support_ticket_consultation),
                        stringResource(R.string.support_ticket_support),
                        stringResource(R.string.support_ticket_escalation),
                    ),
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (closed) Text(stringResource(R.string.support_chat_closed))
                else Button(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (archived) R.string.unarchive_chat else R.string.archive_chat))
                }
                if (targets.isNotEmpty()) OutlinedButton(
                    onClick = { onNotes(targets) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.private_note)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun PrivateContactNoteDialog(
    session: SessionViewModel,
    targets: List<DirectoryUser>,
    onDismiss: () -> Unit,
) {
    var selected by remember(targets) { mutableStateOf(targets.singleOrNull()) }
    var addition by remember(selected?.uid) { mutableStateOf("") }
    val target = selected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.private_contact_note)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.private_contact_note_explanation), color = Muted)
                if (target == null) {
                    Text(stringResource(R.string.choose_person), style = MaterialTheme.typography.titleMedium)
                    targets.forEach { person ->
                        OutlinedButton(
                            onClick = { selected = person },
                            modifier = Modifier.fillMaxWidth().testTag("private_note_person_${person.uid}"),
                        ) { Text(person.username) }
                    }
                } else {
                    Text(target.username, style = MaterialTheme.typography.titleMedium)
                    session.messages.privateContactNotes[target.uid]?.body?.let { existing ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(existing, Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                    OutlinedTextField(
                        value = addition,
                        onValueChange = { addition = it.take(2_000) },
                        label = { Text(stringResource(R.string.add_to_private_note)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("private_contact_note_text"),
                    )
                }
            }
        },
        confirmButton = {
            if (target != null) Button(
                onClick = { session.messages.appendPrivateContactNote(target, addition, onDismiss) },
                enabled = addition.isNotBlank() && !session.messages.busy,
                modifier = Modifier.testTag("save_private_contact_note"),
            ) { Text(stringResource(R.string.append_note)) }
        },
        dismissButton = {
            TextButton(onClick = if (target != null && targets.size > 1) {
                { selected = null }
            } else onDismiss) {
                Text(stringResource(if (target != null && targets.size > 1) R.string.back else R.string.cancel))
            }
        },
    )
}
