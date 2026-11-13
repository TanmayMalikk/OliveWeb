// Safe-app users: lists the user’s saved safety documents.
package com.olive.app.ui.users.user.documents

import com.olive.app.ui.shared.*


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.SafeContact
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument


// User safety-document card used to view details and actions.
@Composable
internal fun SafetyDocumentCard(
    file: SafetyDocument, currentUid: String, friends: List<Friend>, onOpen: () -> Unit,
    offline: OfflineMediaAvailability, onShare: () -> Unit, onDelete: () -> Unit,
    onMakeOffline: () -> Unit, onRemoveOffline: () -> Unit,
    opening: Boolean,
    evidenceUpdating: Boolean,
    onEvidenceChange: (Boolean) -> Unit,
) {
    val own = file.ownerUid == currentUid
    val owner = friends.firstOrNull { it.uid == file.ownerUid }?.username
    OutlinedCard(Modifier.fillMaxWidth().testTag("document_${file.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    when {
                        file.mimeType.startsWith("image/") -> Icons.Outlined.Image
                        file.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                        else -> Icons.Outlined.Description
                    }, null,
                    Modifier.size(30.dp), tint = OliveGreen
                )
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        if (own) stringResource(R.string.documents_yours) else stringResource(
                            R.string.documents_shared_by,
                            owner ?: stringResource(R.string.safe_contact_shared_user)
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                    file.ocrConfidence?.let { confidence ->
                        Text(
                            stringResource(
                                R.string.camera_ocr_confidence,
                                (confidence * 100).toInt()
                            ),
                            style = MaterialTheme.typography.bodySmall, color = Muted
                        )
                    }
                }
            }
            file.ocrText?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    modifier = Modifier.testTag("document_ocr_${file.id}")
                )
            }
            // Only the owner may change evidence status; shared viewers do not see this action.
            if (own) Row(
                Modifier.fillMaxWidth().testTag("document_evidence_${file.id}")
                    .toggleable(
                        value = file.evidence,
                        enabled = !evidenceUpdating,
                        role = Role.Checkbox,
                        onValueChange = onEvidenceChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = file.evidence,
                    onCheckedChange = null,
                    enabled = !evidenceUpdating,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.evidence), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (file.evidence) R.string.evidence_tagged else R.string.evidence_normal_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                if (evidenceUpdating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpen,
                    enabled = !opening,
                    modifier = Modifier.weight(1f).testTag("document_open_${file.id}"),
                ) {
                    if (opening) SecureFileOpeningIndicator()
                    else {
                        Icon(Icons.Outlined.Visibility, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open))
                    }
                }
                // Owners can share the document; recipients only get the open action.
                if (own) OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Outlined.Share,
                        null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share))
                }
                if (own) IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        stringResource(R.string.remove)
                    )
                }
            }
            if (file.objectKey.isNotBlank()) {
                val cached = offline in setOf(
                    OfflineMediaAvailability.AVAILABLE,
                    OfflineMediaAvailability.REVALIDATION_REQUIRED,
                )
                OutlinedButton(
                    onClick = if (cached) onRemoveOffline else onMakeOffline,
                    enabled = offline != OfflineMediaAvailability.SAVING,
                    modifier = Modifier.fillMaxWidth().testTag("document_offline_${file.id}"),
                ) {
                    Icon(if (cached) Icons.Outlined.Delete else Icons.Outlined.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(when {
                        offline == OfflineMediaAvailability.SAVING -> R.string.documents_saving_offline
                        cached -> R.string.documents_remove_offline_copy
                        else -> R.string.documents_make_available_offline
                    }))
                }
                when (offline) {
                    OfflineMediaAvailability.REVALIDATION_REQUIRED,
                    OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED -> Text(
                        stringResource(R.string.documents_offline_revalidation_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OfflineMediaAvailability.AVAILABLE -> Text(
                        stringResource(R.string.documents_available_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = OliveGreen,
                    )
                    OfflineMediaAvailability.RECENT_AVAILABLE -> Text(
                        stringResource(R.string.documents_recently_cached),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    else -> Unit
                }
            }
        }
    }
}
// Lets the user choose which trusted contacts may open a document.
@Composable
internal fun DocumentSharingDialog(
    file: SafetyDocument,
    currentUid: String,
    friends: List<Friend>,
    contacts: List<SafeContact>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    var selected by remember(file.id) {
        mutableStateOf(file.allowedUsers.filterNot { it == currentUid }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.documents_share_help),
                    style = MaterialTheme.typography.bodySmall
                )
                if (friends.isEmpty()) Text(
                    stringResource(R.string.location_no_friends),
                    color = Muted
                )
                friends.forEach { friend ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val trusted = contacts.firstOrNull { it.linkedUid == friend.uid }
                        Checkbox(
                            friend.uid in selected,
                            { checked ->
                                selected =
                                    if (checked) selected + friend.uid else selected - friend.uid
                            },
                            enabled = friend.uid in selected || selected.size < 10
                        )
                        Column {
                            Text(trusted?.name ?: friend.username)
                            if (trusted != null) Text(
                                "@${friend.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val namedRecipients = friends.filter { it.uid in selected }.associate { it.uid to it.username }
                val existingHiddenRecipients = selected
                    .filter { it in file.allowedUsers && it !in namedRecipients }
                    .associateWith { "" }
                onSave(existingHiddenRecipients + namedRecipients)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
