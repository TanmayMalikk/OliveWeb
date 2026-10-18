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

enum class AttachmentCategory { Notes, Incidents, Media }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPicker(
    session: SessionViewModel,
    // mediaViewModel: MediaViewModel
    onDismiss: () -> Unit
) {
    // This state controls the "Drill Down" UI
    var selectedCategory by remember { mutableStateOf<AttachmentCategory?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            
            // Dynamic Header with a Back Button if a category is selected
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (selectedCategory != null) {
                    IconButton(onClick = { selectedCategory = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    text = if (selectedCategory == null) "Share Attachment" else "Select ${selectedCategory?.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            HorizontalDivider()

            // STEP 1: Show Categories
            if (selectedCategory == null) {
                ListItem(
                    headlineContent = { Text("Private Notes") },
                    leadingContent = { Icon(Icons.Outlined.Edit, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Notes }
                )
                ListItem(
                    headlineContent = { Text("Incident Reports") },
                    leadingContent = { Icon(Icons.Outlined.Warning, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Incidents }
                )
                ListItem(
                    headlineContent = { Text("Saved Media") },
                    leadingContent = { Icon(Icons.Outlined.PhotoCamera, null) },
                    modifier = Modifier.clickable { selectedCategory = AttachmentCategory.Media }
                )
            } 
            // STEP 2: Show Specific Items based on the selection
            else {
                LazyColumn {
                    when (selectedCategory) {
                        AttachmentCategory.Notes -> {
                            // Only include entries explicitly marked as Note, or older entries with a null kind
                            val notes = session.privateEntries.filter { it.kind == PrivateEntryKind.Note || it.kind == null }
                            if (notes.isEmpty()) item { Text("No notes found.", Modifier.padding(16.dp)) }
                            items(notes) { note ->
                                ListItem(
                                    headlineContent = { Text(note.title.ifBlank{ "Untitled Note"}) },
                                    modifier = Modifier.clickable {
                                        session.sharePrivateEntryInChat(note) // Automatically adds SHARED_NOTE prefix
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        AttachmentCategory.Incidents -> {
                            val incidents = session.privateEntries.filter { it.kind == PrivateEntryKind.Incident }
                            if (incidents.isEmpty()) item { Text("No incident reports found.", Modifier.padding(16.dp)) }
                            items(incidents) { incident ->
                                ListItem(
                                    headlineContent = { Text(incident.title) },
                                    modifier = Modifier.clickable {
                                        // You will add shareIncidentReportInChat to handle the SHARED_INCIDENT prefix
                                        session.sharePrivateEntryInChat(incident) 
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        AttachmentCategory.Media -> {
                            //ToDO change after implementing media uploads
                            item { Text("Media uploads are not yet configured.", Modifier.padding(16.dp)) }
                            // In Phase 2, this will iterate through mediaViewModel.savedMedia
                        }
                        null -> {}
                    }
                }
            }
        }
    }
}