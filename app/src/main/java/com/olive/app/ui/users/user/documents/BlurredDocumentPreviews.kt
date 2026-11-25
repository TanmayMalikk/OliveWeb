// Reusable 50%-blurred live preview surfaces and grids for Documents, Notes, Chat, and Pickers.
package com.olive.app.ui.users.user.documents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.SafeContact
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.pickerMediaKind
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.shared.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

private fun previewBadgeLabel(mimeType: String, name: String): String {
    val clean = mimeType.substringBefore(';').trim().lowercase()
    return when {
        clean.startsWith("image/") -> "Photo"
        clean.startsWith("video/") -> "Video"
        clean == "application/pdf" || name.endsWith(".pdf", true) -> "PDF"
        clean.startsWith("audio/") -> "Audio"
        else -> "Doc"
    }
}

private fun previewFallbackIcon(mimeType: String, name: String) = when {
    mimeType.startsWith("image/") -> Icons.Outlined.Image
    mimeType.startsWith("video/") -> Icons.Outlined.Videocam
    mimeType == "application/pdf" || name.endsWith(".pdf", true) -> Icons.Outlined.PictureAsPdf
    mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
    else -> Icons.Outlined.Description
}

/**
 * Renders a rounded preview container showing the 50%-blurred live content of a document, video, or photo,
 * falling back cleanly to a type icon when loading or unrendered.
 */
@Composable
internal fun BlurredDocumentPreviewSurface(
    document: SafetyDocument?,
    preview: DocumentPreview?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    fallbackMimeType: String = "",
    fallbackName: String = "",
    showTypeBadge: Boolean = true,
    showEvidenceBadge: Boolean = false,
    offlineCached: Boolean = false,
    extraCountBadge: Int = 0,
) {
    val mimeType = document?.mimeType ?: fallbackMimeType
    val name = document?.name ?: fallbackName
    val isVideo = mimeType.substringBefore(';').trim().lowercase().startsWith("video/")
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        color = LightSage,
        border = BorderStroke(1.dp, OliveOutline.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            val bitmap = preview?.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.22f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                        ),
                    ),
                )
            } else {
                Icon(
                    imageVector = previewFallbackIcon(mimeType, name),
                    contentDescription = null,
                    tint = OliveGreen.copy(alpha = if (loading) 0.55f else 0.85f),
                    modifier = Modifier.size(34.dp).align(Alignment.Center),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).align(Alignment.BottomEnd).padding(6.dp),
                        strokeWidth = 2.dp,
                        color = OliveGreen,
                    )
                }
            }
            if (isVideo) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.48f),
                    contentColor = Color.White,
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (showTypeBadge) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.56f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    Text(
                        text = previewBadgeLabel(mimeType, name),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if ((showEvidenceBadge && document?.evidence == true) || offlineCached) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (offlineCached) {
                        Surface(
                            shape = CircleShape,
                            color = OliveGreen,
                            contentColor = Color.White,
                        ) {
                            Icon(
                                Icons.Outlined.OfflinePin,
                                contentDescription = null,
                                modifier = Modifier.padding(3.dp).size(14.dp),
                            )
                        }
                    }
                    if (showEvidenceBadge && document?.evidence == true) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = OliveGreen,
                            contentColor = Color.White,
                        ) {
                            Text(
                                text = stringResource(R.string.evidence),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (extraCountBadge > 0) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$extraCountBadge",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Displays saved OLIVE Storage files in a 2-column blurred preview grid for selection.
 */
@Composable
internal fun OliveStoragePreviewGrid(
    documents: List<SafetyDocument>,
    controller: SafetyDocumentsController,
    onSelect: (SafetyDocument) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(documents, key = SafetyDocument::id) { document ->
            LaunchedEffect(document.id, document.objectKey) {
                controller.requestPreview(document)
            }
            OliveOutlinedCard(
                Modifier.fillMaxWidth().testTag("olive_storage_${document.id}")
                    .clickable { onSelect(document) },
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlurredDocumentPreviewSurface(
                        document = document,
                        preview = controller.previewFor(document.id),
                        loading = controller.isPreviewLoading(document.id),
                        showEvidenceBadge = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
                    )
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Renders a compact 1-to-4 tile blurred media preview strip for note cards and chat shared-note cards.
 */
@Composable
internal fun NoteAttachmentPreviewStrip(
    attachmentIds: List<String>,
    viewerUid: String?,
    controller: SafetyDocumentsController,
    modifier: Modifier = Modifier,
) {
    val distinctIds = attachmentIds.filter(String::isNotBlank).distinct()
    if (distinctIds.isEmpty()) return
    val shownIds = distinctIds.take(4)
    val overflow = (distinctIds.size - 4).coerceAtLeast(0)
    LaunchedEffect(viewerUid, shownIds) {
        val uid = viewerUid ?: return@LaunchedEffect
        shownIds.forEach { controller.requestPreviewById(uid, it) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val rows = if (shownIds.size <= 2) listOf(shownIds) else shownIds.chunked(2)
        val tileHeight = when {
            shownIds.size == 1 -> 88.dp
            shownIds.size == 2 -> 72.dp
            else -> 60.dp
        }
        rows.forEachIndexed { rowIndex, rowIds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowIds.forEachIndexed { colIndex, docId ->
                    val isLastOverflow = overflow > 0 && rowIndex == rows.lastIndex && colIndex == rowIds.lastIndex
                    val doc = controller.documentById(docId)
                    BlurredDocumentPreviewSurface(
                        document = doc,
                        preview = controller.previewFor(docId),
                        loading = controller.isPreviewLoading(docId),
                        showTypeBadge = shownIds.size <= 2,
                        extraCountBadge = if (isLastOverflow) overflow else 0,
                        modifier = Modifier.weight(1f).height(tileHeight),
                    )
                }
                if (rowIds.size == 1 && shownIds.size > 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Displays attached files inside the note editor as a 2-column blurred preview grid where tapping
 * opens the document in the secure viewer and tapping detach removes it from the note.
 */
@Composable
internal fun NoteEditorAttachmentsSection(
    selectedAttachments: Set<String>,
    documents: List<SafetyDocument>,
    controller: SafetyDocumentsController,
    onOpenDocument: (SafetyDocument) -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    val attached = selectedAttachments.mapNotNull { id ->
        documents.firstOrNull { it.id == id } ?: controller.documentById(id)
    }
    if (attached.isEmpty()) return
    LaunchedEffect(attached.map { it.id }) {
        attached.forEach { controller.requestPreview(it) }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        attached.chunked(2).forEach { rowDocs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowDocs.forEach { document ->
                    OliveOutlinedCard(
                        Modifier.weight(1f).testTag("note_attachment_card_${document.id}"),
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BlurredDocumentPreviewSurface(
                                document = document,
                                preview = controller.previewFor(document.id),
                                loading = controller.isPreviewLoading(document.id),
                                showEvidenceBadge = true,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1.2f)
                                    .clickable { onOpenDocument(document) },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = document.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).clickable { onOpenDocument(document) },
                                )
                                OliveIconButton(
                                    onClick = { onRemoveAttachment(document.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.remove),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowDocs.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Displays all attachments on a shared note or incident inside [SharedEntryViewer] with blurred previews
 * and secure opening on tap.
 */
@Composable
internal fun SharedEntryAttachmentsSection(
    attachmentIds: List<String>,
    viewerUid: String?,
    controller: SafetyDocumentsController,
    onOpenMedia: (SafetyDocument, File) -> Unit,
) {
    val distinctIds = attachmentIds.filter(String::isNotBlank).distinct()
    if (distinctIds.isEmpty()) return
    LaunchedEffect(viewerUid, distinctIds) {
        val uid = viewerUid ?: return@LaunchedEffect
        distinctIds.forEach { controller.requestPreviewById(uid, it) }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "${stringResource(R.string.attachments)} (${distinctIds.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = OliveGreen,
            modifier = Modifier.semantics { heading() },
        )
        distinctIds.chunked(2).forEach { rowIds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowIds.forEach { docId ->
                    val document = controller.documentById(docId)
                    val opening = docId in controller.state.openingDocumentIds
                    val error = controller.state.attachmentErrors[docId]
                    val playable = document == null || pickerMediaKind(document.mimeType) != MediaKind.AUDIO
                    OliveOutlinedCard(
                        Modifier.weight(1f).testTag("shared_entry_attachment_$docId")
                            .then(
                                if (playable && viewerUid != null && !opening) {
                                    Modifier.clickable {
                                        controller.openShared(viewerUid, docId, onOpenMedia)
                                    }
                                } else Modifier,
                            ),
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                BlurredDocumentPreviewSurface(
                                    document = document,
                                    preview = controller.previewFor(docId),
                                    loading = controller.isPreviewLoading(docId),
                                    showEvidenceBadge = true,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
                                )
                                if (opening) {
                                    Box(
                                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Color.White)
                                    }
                                }
                            }
                            Text(
                                text = document?.name ?: stringResource(R.string.encrypted_document),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (error != null) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (rowIds.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Lets the user choose which trusted contacts may open a document.
 */
@Composable
internal fun DocumentSharingDialog(
    file: SafetyDocument,
    currentUid: String,
    friends: List<Friend>,
    contacts: List<SafeContact>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var selected by remember(file.id) {
        mutableStateOf(file.allowedUsers.filterNot { it == currentUid }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.documents_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.documents_share_help), style = MaterialTheme.typography.bodySmall)
                if (friends.isEmpty()) Text(stringResource(R.string.location_no_friends), color = Muted)
                friends.forEach { friend ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val trusted = contacts.firstOrNull { it.linkedUid == friend.uid }
                        Checkbox(
                            friend.uid in selected,
                            { checked -> selected = if (checked) selected + friend.uid else selected - friend.uid },
                            enabled = friend.uid in selected || selected.size < 10,
                        )
                        Column {
                            Text(trusted?.name ?: friend.username)
                            if (trusted != null) {
                                Text("@${friend.username}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OliveButton(onClick = {
                val namedRecipients = friends.filter { it.uid in selected }.associate { it.uid to it.username }
                val existingHidden = selected.filter { it in file.allowedUsers && it !in namedRecipients }.associateWith { "" }
                onSave(existingHidden + namedRecipients)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { OliveTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
