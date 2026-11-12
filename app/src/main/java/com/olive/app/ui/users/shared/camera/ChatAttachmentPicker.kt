// All safe-app roles: lets chat participants choose a photo, video, or document.
package com.olive.app.ui.users.shared.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import com.olive.app.ui.users.user.location.ShareDurationDialog


private enum class AttachmentCategory { Notes, Incidents, Media, SafetyPlan, Location }

// Shows photo, video, document, and camera choices for a chat attachment.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPicker(
    session: SessionViewModel,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<AttachmentCategory?>(null) }
    val uid = session.coverAccountId
    val documentState = session.documents.state
    val requestLocationPermission = LocalLocationPermissionRequest.current
    var locationDuration by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetTitle = if (selectedCategory == null) stringResource(R.string.share_attachment) else stringResource(
        R.string.select_attachment_category,
        stringResource(
            //TODO: This when branch is unreachable

            // Opens the selected photo, video, document, or camera source.
            when (selectedCategory) {
                AttachmentCategory.Notes -> R.string.private_notes
                AttachmentCategory.Incidents -> R.string.incident_reports
                AttachmentCategory.Media -> R.string.saved_media
                AttachmentCategory.SafetyPlan -> R.string.shared_safety_plan
                AttachmentCategory.Location -> R.string.share_live_location_with
                null -> R.string.share_attachment
            }
        )
    )
    // Move TalkBack focus to the heading when a new attachment section opens.
    LaunchedEffect(selectedCategory) {
        // The sheet takes focus while its opening animation creates the dialog window. Wait until
        // that transition finishes, then move focus to the heading for the newly displayed step.
        while (sheetState.currentValue != SheetValue.Expanded) withFrameNanos { }
        withFrameNanos { }
        titleFocus.requestFocus()
    }
    LaunchedEffect(selectedCategory, uid) {
        if (selectedCategory != AttachmentCategory.Media || uid == null || session.localMode) return@LaunchedEffect
        session.documents.load(uid, session.coverUsername, false)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).semantics { paneTitle = sheetTitle }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (selectedCategory != null) {
                    IconButton(onClick = { selectedCategory = null }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
                Text(
                    text = sheetTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.focusRequester(titleFocus).focusable()
                        .semantics { heading() }.testTag("attachment_picker_title")
                )
            }
            HorizontalDivider()

            // STEP 1: Show Categories
            if (selectedCategory == null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.private_notes)) },
                    leadingContent = { Icon(Icons.Outlined.Edit, null) },
                    modifier = Modifier.testTag("attachment_category_notes")
                        .clickable { selectedCategory = AttachmentCategory.Notes }
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
                if (session.accountRole == AccountRole.USER &&
                    session.messages.activeConversation?.type == "SUPPORT") ListItem(
                    headlineContent = { Text(stringResource(R.string.shared_safety_plan)) },
                    supportingContent = { Text(stringResource(R.string.share_safety_plan_support_chat)) },
                    leadingContent = { Icon(Icons.Outlined.HealthAndSafety, null) },
                    modifier = Modifier.clickable { session.messages.shareSafetyPlanInChat(); onDismiss() },
                )
                if (session.accountRole == AccountRole.USER) ListItem(
                    headlineContent = { Text(stringResource(R.string.start_sharing_location)) },
                    supportingContent = { Text(stringResource(R.string.location_share_in_chat_help)) },
                    leadingContent = { Icon(Icons.Outlined.LocationOn, null) },
                    modifier = Modifier.clickable { locationDuration = true }
                )
            } else {
                LazyColumn {
                    // Opens the selected photo, video, document, or camera source.
                    when (selectedCategory) {
                        AttachmentCategory.Notes -> {
                            val notes = session.privateEntries.filter { it.deletedAt == null && it.kind == PrivateEntryKind.Note }
                            if (notes.isEmpty()) item {
                                Text(stringResource(R.string.no_private_notes_found), Modifier.padding(16.dp))
                            }
                            items(notes) { note ->
                                ListItem(
                                    headlineContent = { Text(note.title.ifBlank { stringResource(R.string.untitled_note) }) },
                                    modifier = Modifier.clickable { session.messages.sharePrivateEntryInChat(note); onDismiss() }
                                )
                            }
                        }

                        AttachmentCategory.Incidents -> {
                            val incidents = session.privateEntries.filter { it.deletedAt == null && it.kind == PrivateEntryKind.Incident }
                            if (incidents.isEmpty()) item {
                                Text(stringResource(R.string.no_incident_reports_found), Modifier.padding(16.dp))
                            }
                            items(incidents) { incident ->
                                ListItem(
                                    headlineContent = { Text(incident.title) },
                                    modifier = Modifier.clickable { session.messages.sharePrivateEntryInChat(incident); onDismiss() }
                                )
                            }
                        }

                        AttachmentCategory.Media -> {
                            val media = documentState.files.filter { it.ownerUid == uid }
                            if (documentState.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (documentState.error != null) item {
                                Text(stringResource(R.string.documents_load_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                            }
                            if (!documentState.loading && documentState.error == null && media.isEmpty()) item {
                                Text(
                                    stringResource(if (session.localMode) R.string.documents_account_required else R.string.documents_empty),
                                    Modifier.padding(16.dp)
                                )
                            }
                            items(media, key = { it.id }) { document ->
                                ListItem(
                                    headlineContent = { Text(document.name, maxLines = 2) },
                                    supportingContent = { Text(document.mimeType) },
                                    leadingContent = {
                                        Icon(
                                            when {
                                                document.mimeType.startsWith("image/") -> Icons.Outlined.Image
                                                document.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                                                else -> Icons.Outlined.Description
                                            }, null
                                        )
                                    },
                                    modifier = Modifier.clickable { session.messages.shareSafetyDocumentInChat(document); onDismiss() }
                                )
                            }
                        }

                        AttachmentCategory.Location, AttachmentCategory.SafetyPlan, null -> Unit
                    }
                }
            }
        }
    }
    // Show duration choices only when the user is sharing live location.
    if (locationDuration) ShareDurationDialog(onDismiss = { locationDuration = false }) { duration ->
        locationDuration = false
        session.shareLiveLocationInChat(duration, requestLocationPermission)
        onDismiss()
    }
}
