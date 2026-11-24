// Safe-app users and safe contacts: renders shared private-entry cards and direct note-sharing controls.
package com.olive.app.ui.users.user.notes

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.SafeContact
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SharedPrivateEntry
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Displays a read-only private note or incident report that another account shared with this user or safe contact.
 *
 * @param item The resource or record being processed.
 * @param ownerDisplayName The display name associated with the item's owner.
 * @param formatter The formatter used to display dates or values.
 * @param onOpen Runs when the user opens the item.
 * @param modifier The Compose modifier applied to the component.
 */
@Composable
internal fun SharedPrivateNoteCard(
    item: SharedPrivateEntry,
    ownerDisplayName: String,
    formatter: DateTimeFormatter,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = item.entry
    val isIncident = entry.kind == PrivateEntryKind.Incident
    val container = if (isIncident) IncidentPaper else PaleOlive
    val accent = if (isIncident) IncidentAccent else DarkOlive
    val sharedByLabel = stringResource(R.string.notes_shared_by, ownerDisplayName)
    val timestampLabel = formatter.format(Instant.ofEpochMilli(entry.updatedAt))
    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .testTag("shared_private_entry_${entry.id}")
            .semantics { stateDescription = "$sharedByLabel, $timestampLabel" },
        border = BorderStroke(1.dp, accent),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = Ink),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.FolderShared, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (isIncident) R.string.incident else R.string.note),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (entry.title.isNotBlank()) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                entry.body,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sharedByLabel,
                style = MaterialTheme.typography.labelMedium,
                color = OliveGreen,
                fontWeight = FontWeight.Medium,
            )
            Text(
                timestampLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/**
 * Lets a note owner grant or revoke access to a private note or incident for accepted friends and safe contacts.
 *
 * @param entry The private entry to save, display, or share.
 * @param currentRecipientUids The accounts that currently hold access.
 * @param friends The accepted friends available for the operation.
 * @param contacts The contact details available for this operation.
 * @param onDismiss Runs when the user dismisses the dialog.
 * @param onSave Runs when the user saves the edited information.
 */
@Composable
internal fun NoteSharingDialog(
    entry: PrivateEntry,
    currentRecipientUids: List<String>,
    friends: List<Friend>,
    contacts: List<SafeContact>,
    onDismiss: () -> Unit,
    onSave: (List<Friend>) -> Unit,
) {
    var selected by remember(entry.id, currentRecipientUids) {
        mutableStateOf(currentRecipientUids.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.notes_share_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (friends.isEmpty()) {
                    Text(stringResource(R.string.location_no_friends), color = Muted)
                }
                friends.forEach { friend ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val trusted = contacts.firstOrNull { it.linkedUid == friend.uid }
                        Checkbox(
                            checked = friend.uid in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + friend.uid else selected - friend.uid
                            },
                            enabled = friend.uid in selected || selected.size < 10,
                            modifier = Modifier.testTag("note_share_friend_${friend.uid}"),
                        )
                        Column {
                            Text(trusted?.name ?: friend.username)
                            if (trusted != null) {
                                Text(
                                    "@${friend.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(friends.filter { it.uid in selected }) },
                modifier = Modifier.testTag("note_share_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
