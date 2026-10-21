package com.olive.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.olive.app.data.PrivateEntryKind
import androidx.compose.ui.res.stringResource
import com.olive.app.R

enum class AttachmentCategory { Notes, Incidents, Media }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPicker(
    session: SessionViewModel,
    // mediaViewModel: MediaViewModel
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<AttachmentCategory?>(null) }
    val uid=session.coverAccountId
    val documentState=session.documents.state
    LaunchedEffect(selectedCategory,uid) {
        if(selectedCategory!=AttachmentCategory.Media || uid==null || session.localMode) return@LaunchedEffect
        session.documents.load(uid,false)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (selectedCategory != null) {
                    IconButton(onClick = { selectedCategory = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
                Text(
                    text = if (selectedCategory == null) stringResource(R.string.share_attachment)
                        else stringResource(R.string.select_attachment_category,
                            stringResource(if(selectedCategory==AttachmentCategory.Notes) R.string.private_notes
                                else if(selectedCategory==AttachmentCategory.Incidents) R.string.incident_reports
                                else R.string.saved_media)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider()

            // STEP 1: Show Categories
            if (selectedCategory == null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.private_notes)) },
                    leadingContent = { Icon(Icons.Outlined.Edit, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Notes }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.incident_reports)) },
                    leadingContent = { Icon(Icons.Outlined.Warning, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Incidents }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.saved_media)) },
                    leadingContent = { Icon(Icons.Outlined.PhotoCamera, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Media }
                )
            }
            // STEP 2: Show Specific Items based on the selection
            else {
                LazyColumn {
                    when (selectedCategory) {
                        AttachmentCategory.Notes -> {
                            val notes = session.privateEntries.filter { it.deletedAt==null && (it.kind == PrivateEntryKind.Note || it.kind == null) }
                            if (notes.isEmpty()) item { Text(stringResource(R.string.no_private_notes_found), Modifier.padding(16.dp)) }
                            items(notes) { note ->
                                ListItem(
                                    headlineContent = { Text(note.title.ifBlank{ stringResource(R.string.untitled_note) }) },
                                    modifier = Modifier.clickable {
                                        session.sharePrivateEntryInChat(note)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        AttachmentCategory.Incidents -> {
                            val incidents = session.privateEntries.filter { it.deletedAt==null && it.kind == PrivateEntryKind.Incident }
                            if (incidents.isEmpty()) item { Text(stringResource(R.string.no_incident_reports_found), Modifier.padding(16.dp)) }
                            items(incidents) { incident ->
                                ListItem(
                                    headlineContent = { Text(incident.title) },
                                    modifier = Modifier.clickable {
                                        session.sharePrivateEntryInChat(incident)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        AttachmentCategory.Media -> {
                            val media=documentState.files.filter { it.ownerUid==uid }
                            if(documentState.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if(documentState.error!=null) item { Text(stringResource(R.string.documents_load_failed),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error) }
                            if(!documentState.loading && documentState.error==null && media.isEmpty()) item {
                                Text(stringResource(if(session.localMode) R.string.documents_account_required else R.string.documents_empty),
                                    Modifier.padding(16.dp))
                            }
                            items(media,key={it.id}) { document ->
                                ListItem(
                                    headlineContent={Text(document.name,maxLines=2)},
                                    supportingContent={Text(document.mimeType)},
                                    leadingContent={Icon(when {
                                        document.mimeType.startsWith("image/")->Icons.Outlined.Image
                                        document.mimeType.startsWith("video/")->Icons.Outlined.Videocam
                                        else->Icons.Outlined.Description
                                    },null)},
                                    modifier=Modifier.clickable {
                                        session.shareSafetyDocumentInChat(document)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        null -> {}
                    }
                }
            }
        }
    }
}
