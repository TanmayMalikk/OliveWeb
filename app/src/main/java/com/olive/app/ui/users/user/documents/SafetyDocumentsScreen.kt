// Safe-app users: lists the user’s saved safety documents.
package com.olive.app.ui.users.user.documents

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.MediaTransferState
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.SafetyDocument
import java.io.File
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.pickerMediaKind
import com.olive.app.data.messaging.Friend

private enum class DocumentFilter { All, Photos, Videos, Documents }

/**
 * Lists encrypted documents and provides upload, filter, open, and share actions.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun SafetyDocumentsScreen(session: SessionViewModel) {
    val uid = session.coverAccountId ?: return
    val controller = session.documents
    val state = controller.state
    val mediaController = session.media
    val uploads = mediaController?.uploads.orEmpty().filterNot(MediaUploadPresentation::chatOnly)
    val preparation = mediaController?.state as? MediaTransferState.Preparing
    val preparing = preparation != null && preparation.stage != "downloading"
    var filter by rememberSaveable { mutableStateOf(DocumentFilter.All) }
    var sharing by remember { mutableStateOf<SafetyDocument?>(null) }
    var opened by remember { mutableStateOf<Pair<SafetyDocument, File>?>(null) }
    var choosingUpload by rememberSaveable { mutableStateOf(false) }
    var wtUploadOpen by remember { mutableStateOf(false) }
    val wtDirective = LocalWalkthroughDirective.current
    LaunchedEffect(wtDirective?.stepId, wtDirective?.action) {
        if (wtDirective?.action == "docs_open_upload") {
            choosingUpload = true
            wtUploadOpen = true
        } else if (wtUploadOpen) {
            choosingUpload = false
            wtUploadOpen = false
        }
    }
    val listState = rememberLazyGridState()
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(uid, session.coverUsername, session.localMode) {
        controller.load(uid, session.coverUsername, session.localMode)
    }
    opened?.let { (document, localFile) ->
        SafetyDocumentViewer(document, localFile) {
            localFile.delete()
            opened = null
        }
        return
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("documents_back")) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.ui_previous_safe_page)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.support_documents),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.documents_storage_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
        Button(
            onClick = { choosingUpload = true },
            enabled = !preparing && !session.localMode,
            modifier = Modifier.fillMaxWidth().walkthroughTarget("documents_upload").testTag("documents_upload")
        ) {
            Icon(
                Icons.Outlined.UploadFile,
                null
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.documents_add))
        }
        if (session.localMode) Text(
            stringResource(R.string.documents_account_required),
            color = Muted
        )
        if (!session.localMode && state.source in setOf(
                DocumentListSource.LOCAL_INDEX,
                DocumentListSource.FIRESTORE_CACHE,
            )) Row(
            Modifier.fillMaxWidth().testTag("documents_offline_index").semantics {
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = Muted)
            Text(stringResource(R.string.documents_offline_index),
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(Modifier.fillMaxWidth().walkthroughTarget("documents_filter_chips"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DocumentFilter.entries.toList().chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = {
                                Text(
                                    stringResource(
                                        // Opens the protected file picker only after private entry is confirmed.
                                        when (option) {
                                            DocumentFilter.All -> R.string.documents_all
                                            DocumentFilter.Photos -> R.string.safety_storage_photos
                                            DocumentFilter.Videos -> R.string.safety_storage_videos
                                            DocumentFilter.Documents -> R.string.safety_storage_documents
                                        }
                                    ), maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        state.error?.let { AccessibleMessage(it) }
        val shown = (if (state.loading) emptyList() else state.files).filter { file ->
            // Opens the protected file picker only after private entry is confirmed.
            when (filter) {
                DocumentFilter.All -> true
                DocumentFilter.Photos -> file.mimeType.startsWith("image/")
                DocumentFilter.Videos -> file.mimeType.startsWith("video/")
                DocumentFilter.Documents -> !file.mimeType.startsWith("image/") && !file.mimeType.startsWith(
                    "video/"
                )
            }
        }
        if (!state.loading && state.error == null && !preparing && uploads.isEmpty() && shown.isEmpty()) Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = LightSage
        ) {
            Column(
                Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(36.dp), tint = OliveGreen)
                Text(
                    stringResource(R.string.documents_empty),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.documents_empty_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth().oliveScrollbar(listState),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) item(key = "loading_documents", span = { GridItemSpan(maxLineSpan) }) {
                LoadingSkeleton(SkeletonLayout.Documents)
            }
            if (preparing) item(key = "preparing_upload", span = { GridItemSpan(maxLineSpan) }) {
                PreparingUploadCard()
            }
            gridItems(uploads, key = { "upload_${it.jobId}" }, span = { GridItemSpan(maxLineSpan) }) { upload ->
                PendingUploadCard(
                    upload = upload,
                    onOpen = {
                        mediaController?.openPendingUpload(upload.jobId, uid)?.let { (doc, local) ->
                            opened = doc to local
                        }
                    },
                    onRetry = { mediaController?.retryUpload(upload.jobId) }
                )
            }
            gridItems(shown, key = { it.id }) { file ->
                LaunchedEffect(file.id, file.objectKey) { controller.requestPreview(file) }
                SafetyDocumentCard(
                    file = file,
                    currentUid = uid,
                    friends = session.messages.friends,
                    onOpen = {
                        controller.download(file) { resolved, local ->
                            opened = resolved to local
                        }
                    },
                    offline = mediaController?.offlineCopies?.get(file.id)
                        ?: OfflineMediaAvailability.NOT_CACHED,
                    onShare = { sharing = file },
                    onDelete = { controller.delete(file, uid) },
                    onMakeOffline = { controller.makeAvailableOffline(file) },
                    onRemoveOffline = { controller.removeOfflineCopy(file) },
                    opening = file.id in state.openingDocumentIds,
                    evidenceUpdating = file.id in state.evidenceUpdates,
                    onEvidenceChange = { evidence -> controller.setEvidence(file, uid, evidence) },
                    preview = controller.previewFor(file.id),
                    previewLoading = controller.isPreviewLoading(file.id),
                )
            }
        }
    }
    sharing?.let { file ->
        DocumentSharingDialog(
            file,
            uid,
            session.messages.friends,
            session.profile?.contacts.orEmpty(),
            onDismiss = { sharing = null }) { recipients ->
            controller.share(file, uid, recipients) { sharing = null }
        }
    }
    SafetyDocumentUploadFlow(
        session = session,
        visible = choosingUpload,
        inWindowSheet = wtUploadOpen,
        onDismiss = {
            choosingUpload = false
            wtUploadOpen = false
        },
    )
}

/**
 * Keeps every document entry point on the same protected camera/media/file upload workflow.
 *
 * @param session Shared session view model owning application state and actions.
 * @param visible Whether the UI component should be visible.
 * @param inWindowSheet Whether to render as an in-window sheet so the walkthrough overlays on top.
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param tags Collection of tags values to process.
 * @param onSelectExisting Callback invoked when select existing changes or occurs.
 * @param onUploadStarted Callback invoked when upload started changes or occurs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafetyDocumentUploadFlow(
    session: SessionViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
    tags: Set<String> = emptySet(),
    onSelectExisting: ((SafetyDocument) -> Unit)? = null,
    onUploadStarted: (() -> Unit)? = null,
    inWindowSheet: Boolean = false,
) {
    val controller = session.documents
    val unableUpload = stringResource(R.string.documents_upload_failed)
    var pickerTags by remember { mutableStateOf(tags) }
    var showOliveStorage by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible, tags) {
        if (visible) {
            pickerTags = tags
            showOliveStorage = false
        }
    }
    /**
     * Finishes selection.
     *
     * @param uri Content or file URI identifying the selected local item, or null when not provided.
     */
    fun finishSelection(uri: android.net.Uri?) {
        session.endExternalFilePicker()
        val uid = session.coverAccountId
        if (uri != null && uid != null) controller.upload(
            uri = uri,
            identity = AccountIdentity(uid, session.coverUsername),
            fallbackError = unableUpload,
            tags = pickerTags,
        )
    }
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
        ::finishSelection,
    )
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        ::finishSelection,
    )
    /**
     * Launches protected picker.
     *
     * @param action Action or callback executed for action.
     */
    fun launchProtectedPicker(action: () -> Unit) {
        onUploadStarted?.invoke()
        onDismiss()
        session.beginExternalFilePicker()
        runCatching(action).onFailure {
            session.endExternalFilePicker()
            controller.reportError(unableUpload)
        }
    }
    if (visible) {
        val sheetContent: @Composable ColumnScope.() -> Unit = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = if (inWindowSheet) 18.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(
                        if (showOliveStorage) R.string.olive_storage
                        else R.string.documents_choose_source
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(
                        if (showOliveStorage) R.string.olive_storage_help
                        else R.string.documents_choose_source_help
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                if (showOliveStorage) {
                    val ownerUid = session.coverAccountId
                    LaunchedEffect(showOliveStorage, ownerUid) {
                        if (ownerUid != null && !session.localMode && controller.state.files.isEmpty()) {
                            controller.load(ownerUid, session.coverUsername, false)
                        }
                    }
                    val available = controller.state.files.filter { it.ownerUid == ownerUid }
                    if (available.isEmpty()) {
                        Text(
                            stringResource(R.string.attachments_empty_help),
                            color = Muted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        OliveStoragePreviewGrid(
                            documents = available,
                            controller = controller,
                            onSelect = { document ->
                                onSelectExisting?.invoke(document)
                                onDismiss()
                            },
                        )
                    }
                    TextButton(
                        onClick = { showOliveStorage = false },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.back)) }
                    return@Column
                }
                Button(
                    onClick = {
                        onUploadStarted?.invoke()
                        session.pendingCameraUploadTags = pickerTags
                        onDismiss()
                        session.navigation.openSafePage(SafePage.Camera)
                    },
                    modifier = Modifier.fillMaxWidth().walkthroughTarget("documents_choose_camera").testTag("documents_choose_camera")
                ) {
                    Icon(Icons.Outlined.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.documents_camera)
                    )
                }
                OutlinedButton(onClick = {
                    launchProtectedPicker {
                        mediaPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    }
                }, modifier = Modifier.fillMaxWidth().walkthroughTarget("documents_choose_media").testTag("documents_choose_media")) {
                    Icon(Icons.Outlined.PermMedia, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.documents_choose_media)
                    )
                }
                OutlinedButton(onClick = {
                    launchProtectedPicker {
                        documentPicker.launch(
                            arrayOf(
                                "application/pdf", "text/plain", "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            )
                        )
                    }
                }, modifier = Modifier.fillMaxWidth().walkthroughTarget("documents_choose_document").testTag("documents_choose_document")) {
                    Icon(Icons.Outlined.Description, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.documents_choose_document)
                    )
                }
                if (onSelectExisting != null) OutlinedButton(
                    onClick = { showOliveStorage = true },
                    modifier = Modifier.fillMaxWidth().testTag("documents_choose_olive_storage"),
                ) {
                    Icon(Icons.Outlined.FolderSpecial, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.olive_storage))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
        if (inWindowSheet) {
            WalkthroughInWindowBottomSheet(
                sheetTargetId = "documents_upload_sheet",
                onDismiss = onDismiss,
                content = sheetContent,
            )
        } else {
            ModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = true, content = sheetContent)
        }
    }
}

/**
 * User safety documents screen shows this preparing upload card view so the user can view its details and use its actions.
 */
@Composable
private fun PreparingUploadCard() {
    val title = stringResource(R.string.documents_preparing_upload)
    val help = stringResource(R.string.documents_preparing_upload_help)
    OutlinedCard(Modifier.fillMaxWidth().testTag("document_upload_preparing")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "$title. $help"
                    liveRegion = LiveRegionMode.Polite
                })
            LinearProgressIndicator(Modifier.fillMaxWidth().clearAndSetSemantics {})
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

/**
 * Shows upload progress, errors, and retry controls for one pending file.
 *
 * @param upload Upload supplied to [PendingUploadCard].
 * @param onRetry Callback invoked when retry changes or occurs.
 * @param onOpen Runs when the user opens the item.
 */
@Composable
internal fun PendingUploadCard(
    upload: MediaUploadPresentation,
    onOpen: (() -> Unit)? = null,
    onRetry: () -> Unit,
) {
    val failed = upload.status == MediaTransferStatus.FAILED
    val waitingToRetry = upload.status == MediaTransferStatus.QUEUED && upload.error != null
    val retryable = failed || waitingToRetry
    val status = when (upload.status) {
        MediaTransferStatus.QUEUED -> stringResource(
            if (upload.error == null) R.string.documents_upload_queued
            else R.string.documents_upload_waiting_retry
        )
        MediaTransferStatus.CREATING_SESSION -> stringResource(R.string.documents_upload_starting)
        MediaTransferStatus.UPLOADING -> stringResource(
            R.string.documents_upload_progress,
            upload.progress?.times(100)?.toInt() ?: 0
        )
        MediaTransferStatus.FINALIZING -> stringResource(R.string.documents_upload_finalizing)
        MediaTransferStatus.COMPLETE -> stringResource(R.string.documents_upload_complete)
        MediaTransferStatus.FAILED -> stringResource(R.string.documents_upload_failed)
        MediaTransferStatus.CANCELLED -> stringResource(R.string.documents_upload_cancelled)
    }
    val summary = listOfNotNull(upload.name, status, upload.error?.takeIf { retryable }).joinToString(". ")
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("document_upload_${upload.jobId}")
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = summary
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    null,
                    tint = if (retryable) MaterialTheme.colorScheme.error else OliveGreen
                )
                Column(Modifier.weight(1f)) {
                    Text(upload.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        status + if (onOpen != null && !retryable) " • Tap to preview" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (retryable) MaterialTheme.colorScheme.error else Muted
                    )
                }
            }
            if (!failed && upload.status != MediaTransferStatus.CANCELLED) {
                val progress = upload.progress
                if (progress != null && upload.status == MediaTransferStatus.UPLOADING) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clearAndSetSemantics {})
                }
            }
            upload.error?.takeIf { retryable }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clearAndSetSemantics {})
            }
            if (retryable) OutlinedButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/**
 * User safety-document card used to view details and actions.
 *
 * @param file Local file to read, hash, process, or write.
 * @param currentUid Current user ID supplied to [SafetyDocumentCard].
 * @param friends List of friend records.
 * @param onOpen Callback invoked when open changes or occurs.
 * @param offline Offline supplied to [SafetyDocumentCard].
 * @param onShare Callback invoked when share changes or occurs.
 * @param onDelete Callback invoked when delete changes or occurs.
 * @param onMakeOffline Callback invoked when make offline changes or occurs.
 * @param onRemoveOffline Callback invoked when remove offline changes or occurs.
 * @param opening Whether to apply opening.
 * @param evidenceUpdating Whether to apply evidence updating.
 * @param onEvidenceChange Callback invoked when evidence change changes or occurs.
 */
@Composable
internal fun SafetyDocumentCard(
    file: SafetyDocument, currentUid: String, friends: List<Friend>, onOpen: () -> Unit,
    offline: OfflineMediaAvailability, onShare: () -> Unit, onDelete: () -> Unit,
    onMakeOffline: () -> Unit, onRemoveOffline: () -> Unit,
    opening: Boolean,
    evidenceUpdating: Boolean,
    onEvidenceChange: (Boolean) -> Unit,
    preview: DocumentPreview? = null,
    previewLoading: Boolean = false,
) {
    val own = file.ownerUid == currentUid
    val owner = friends.firstOrNull { it.uid == file.ownerUid }?.username
    val isAudio = pickerMediaKind(file.mimeType) == MediaKind.AUDIO
    val cached = offline in setOf(
        OfflineMediaAvailability.AVAILABLE,
        OfflineMediaAvailability.REVALIDATION_REQUIRED,
    )
    OutlinedCard(Modifier.fillMaxWidth().testTag("document_${file.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BlurredDocumentPreviewSurface(
                document = file,
                preview = preview,
                loading = previewLoading,
                offlineCached = cached,
                opening = opening,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.15f).let { base ->
                    if (isAudio) base
                    else base.testTag("document_open_${file.id}").clickable(
                        enabled = !opening,
                        onClickLabel = stringResource(R.string.open),
                        onClick = onOpen,
                    )
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (own) stringResource(R.string.documents_yours) else stringResource(
                        R.string.documents_shared_by,
                        owner ?: stringResource(R.string.safe_contact_shared_user)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Muted,
                )
                file.ocrConfidence?.let { confidence ->
                    Text(
                        stringResource(
                            R.string.camera_ocr_confidence,
                            (confidence * 100).toInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
            file.ocrText?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("document_ocr_${file.id}"),
                )
            }
            // Only the owner may change evidence status; shared viewers do not see this action.
            if (own) Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("document_evidence_${file.id}")
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
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.evidence),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (evidenceUpdating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            // Owners can share or remove the document via compact icon actions.
            if (own) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedIconButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, OliveOutline),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.share), Modifier.size(18.dp))
                }
                OutlinedIconButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, OliveOutline),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.remove), Modifier.size(18.dp))
                }
            }
            if (file.objectKey.isNotBlank()) {
                OutlinedButton(
                    onClick = if (cached) onRemoveOffline else onMakeOffline,
                    enabled = offline != OfflineMediaAvailability.SAVING,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp)
                        .testTag("document_offline_${file.id}"),
                ) {
                    Icon(if (cached) Icons.Outlined.Delete else Icons.Outlined.Download, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(when {
                            offline == OfflineMediaAvailability.SAVING -> R.string.documents_saving_offline
                            cached -> R.string.documents_remove_offline_copy
                            else -> R.string.documents_make_available_offline
                        }),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (offline) {
                    OfflineMediaAvailability.REVALIDATION_REQUIRED,
                    OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED -> Text(
                        stringResource(R.string.documents_offline_revalidation_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OfflineMediaAvailability.AVAILABLE -> Text(
                        stringResource(R.string.documents_available_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = OliveGreen,
                    )
                    OfflineMediaAvailability.RECENT_AVAILABLE -> Text(
                        stringResource(R.string.documents_recently_cached),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    else -> Unit
                }
            }
        }
    }
}
