// All safe-app roles: lets chat participants choose a photo, video, or document.
package com.olive.app.ui.users.shared.camera

import android.app.Application
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.olive.app.R
import com.olive.app.data.CameraManager
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ocr.OcrResult
import com.olive.app.ocr.PaddleOcrManager
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import com.olive.app.ui.users.user.location.ShareDurationDialog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


private enum class AttachmentCategory { Notes, Incidents, Media, SafetyPlan, Location }

// Shows photo, video, document, and camera choices for a chat attachment.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPicker(
    session: SessionViewModel,
    // mediaViewModel: MediaViewModel
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
                if (session.accountRole == com.olive.app.data.AccountRole.USER &&
                    session.messages.activeConversation?.type == "SUPPORT") ListItem(
                    headlineContent = { Text(stringResource(R.string.shared_safety_plan)) },
                    supportingContent = { Text(stringResource(R.string.share_safety_plan_support_chat)) },
                    leadingContent = { Icon(Icons.Outlined.HealthAndSafety, null) },
                    modifier = Modifier.clickable { session.messages.shareSafetyPlanInChat(); onDismiss() },
                )
                if (session.accountRole == com.olive.app.data.AccountRole.USER) ListItem(
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

// Holds camera view state across photo, video, and document capture modes.
internal class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val camera = CameraManager(application)
    private val ocr = PaddleOcrManager(application)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _frontFacing = MutableStateFlow(false)
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio = _zoomRatio.asStateFlow()
    private var documentJob: Job? = null
    private var pendingDocumentFile: File? = null
    private var documentGeneration = 0

    // Connects CameraX to this camera screen.
    fun bind(owner: LifecycleOwner, preview: PreviewView) {
        viewModelScope.launch {
            _ready.value = false
            runCatching { camera.bindCamera(owner, preview, _frontFacing.value) }
                .onSuccess { _zoomRatio.value = 1f; _ready.value = true }
                .onFailure { _error.value = it.localizedMessage ?: "Camera unavailable" }
        }
    }

    // Switches between the front and back camera.
    fun flip(owner: LifecycleOwner, preview: PreviewView) {
        if (_busy.value || _recording.value) return
        viewModelScope.launch {
            val previous = _frontFacing.value
            _ready.value = false
            _error.value = null
            runCatching { camera.bindCamera(owner, preview, !previous) }
                .onSuccess {
                    _frontFacing.value = !previous
                    _zoomRatio.value = 1f
                    _ready.value = true
                }
                .onFailure {
                    _frontFacing.value = previous
                    runCatching { camera.bindCamera(owner, preview, previous) }
                    _ready.value = true
                    _error.value = it.localizedMessage ?: "Camera unavailable"
                }
        }
    }

    // Moves the camera one zoom step closer while keeping the preview stable.
    fun zoomIn() = changeZoom(1.25f)

    // Moves the camera one zoom step farther away without going below its normal view.
    fun zoomOut() = changeZoom(0.8f)

    // Uses the two-finger camera gesture to smoothly change CameraX's real optical zoom ratio.
    fun zoomBy(multiplier: Float) {
        if (!multiplier.isFinite()) return
        changeZoom(multiplier.coerceIn(0.8f, 1.25f))
    }

    private fun changeZoom(multiplier: Float) {
        if (!_ready.value || (_busy.value && !_recording.value)) return
        runCatching { camera.changeZoom(multiplier) }
            .onSuccess { _zoomRatio.value = it }
            .onFailure { _error.value = it.localizedMessage ?: "Camera zoom is unavailable" }
    }

    // Takes a photo and sends it to the capture preview.
    fun capturePhoto(file: File, onCaptured: (File) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            runCatching { camera.capturePhoto(file) }.onSuccess(onCaptured)
                .onFailure { _error.value = it.localizedMessage ?: "Photo capture failed" }
            _busy.value = false
        }
    }

    // Takes a document photo and runs OCR before previewing it.
    fun captureDocument(file: File, onCaptured: (File, File, OcrResult) -> Unit) {
        if (documentJob?.isActive == true) return
        val generation = ++documentGeneration
        pendingDocumentFile = file
        documentJob = viewModelScope.launch {
            _busy.value = true
            _error.value = null
            // Use the fallback when capture document is unavailable.
            try {
                val captured = camera.capturePhoto(file)
                val result = ocr.recognizeDocument(captured.readBytes())
                if (generation != documentGeneration) {
                    captured.delete()
                    return@launch
                }
                val pdfFile = File(captured.parentFile, "${captured.nameWithoutExtension}.pdf")
                convertDocumentPhotoToPdf(captured, pdfFile)
                if (generation != documentGeneration) {
                    captured.delete()
                    pdfFile.delete()
                    return@launch
                }
                pendingDocumentFile = null
                documentJob = null
                onCaptured(pdfFile, captured, result)
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (failure: Exception) {
                file.delete()
                _error.value = failure.localizedMessage ?: getApplication<Application>().getString(
                    com.olive.app.R.string.camera_document_capture_failed
                )
            } finally {
                if (generation == documentGeneration) {
                    pendingDocumentFile = null
                    documentJob = null
                    _busy.value = false
                }
            }
        }
    }

    // Starts or stops video recording for the Safe App camera.
    fun startVideo(file: File, includeAudio: Boolean, onCaptured: (File) -> Unit) {
        _error.value = null
        runCatching {
            camera.startVideo(
                file,
                includeAudio,
                onStarted = { _recording.value = true }) { result ->
                _recording.value = false
                _busy.value = false
                result.onSuccess(onCaptured)
                    .onFailure { _error.value = it.localizedMessage ?: "Video capture failed" }
            }
            _busy.value = true
        }.onFailure {
            _busy.value = false
            _error.value = it.localizedMessage ?: "Video capture failed"
        }
    }

    // Stops video recording and sends the saved clip to the preview.
    fun stopVideo() = camera.stopVideo()
    // Dismisses the current camera error after the user acknowledges or retries it.
    fun clearError() {
        _error.value = null
    }

    // Stops camera work and removes temporary files when the screen closes.
    fun leaveCamera() {
        documentGeneration++
        documentJob?.cancel()
        documentJob = null
        pendingDocumentFile?.delete()
        pendingDocumentFile = null
        camera.unbind()
        _ready.value = false
        _recording.value = false
        _zoomRatio.value = 1f
        _busy.value = false
    }

    // Releases camera and OCR work when the camera screen closes.
    override fun onCleared() {
        leaveCamera()
        camera.release()
        CoroutineScope(NonCancellable + Dispatchers.IO).launch { ocr.release() }
    }
}

// All safe-app roles: turns a document photo into the PDF stored by the Documents feature.

private const val PORTRAIT_PAGE_WIDTH = 1240
private const val PORTRAIT_PAGE_HEIGHT = 1754
private const val PAGE_MARGIN = 48f

// Creates a real one-page PDF while keeping the camera photo upright and inside the page edges.
internal fun convertDocumentPhotoToPdf(photo: File, pdfFile: File): File {
    val source = decodeUprightDocumentPhoto(photo)
        ?: error("The captured document image could not be read")
    val landscape = source.width > source.height
    val pageWidth = if (landscape) PORTRAIT_PAGE_HEIGHT else PORTRAIT_PAGE_WIDTH
    val pageHeight = if (landscape) PORTRAIT_PAGE_WIDTH else PORTRAIT_PAGE_HEIGHT
    val document = PdfDocument()

    // Draws the document against a white page and scales it without stretching the text.
    try {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create(),
        )
        page.canvas.drawColor(Color.WHITE)
        val availableWidth = pageWidth - PAGE_MARGIN * 2f
        val availableHeight = pageHeight - PAGE_MARGIN * 2f
        val scale = minOf(availableWidth / source.width, availableHeight / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (pageWidth - drawnWidth) / 2f
        val top = (pageHeight - drawnHeight) / 2f
        page.canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + drawnWidth, top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        document.finishPage(page)
        FileOutputStream(pdfFile).use(document::writeTo)
        return pdfFile
    } catch (failure: Exception) {
        pdfFile.delete()
        throw failure
    } finally {
        document.close()
        source.recycle()
    }
}

// Reads CameraX's EXIF flag and rotates or flips the pixels before they enter the PDF.
private fun decodeUprightDocumentPhoto(file: File): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

// All safe-app roles: gives built-in camera captures clear, unique file names.

internal enum class BuiltInCameraFile(
    val prefix: String,
    val extension: String,
) {
    IMAGE("Oliveimage", "jpg"),
    VIDEO("Olivevideo", "mp4"),
    DOCUMENT("OliveDoc", "pdf"),
}

// Adds the capture date and time so a new camera file never replaces an older one.
internal fun builtInCameraFileName(
    type: BuiltInCameraFile,
    timestampMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).apply {
        this.timeZone = timeZone
    }
    return "${type.prefix}${formatter.format(Date(timestampMillis))}.${type.extension}"
}

// Draws the shutter button and gives TalkBack its action label.
@Composable
internal fun CameraShutter(
    mode: CameraMode,
    recording: Boolean,
    busy: Boolean,
    ready: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = ready && (!busy || recording),
        modifier = modifier.size(84.dp).testTag("camera_shutter"),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (recording) ComposeColor.Red else ComposeColor.White
        )
    ) {
        Icon(
            when (mode) {
                CameraMode.Photo -> Icons.Outlined.PhotoCamera
                CameraMode.Video -> Icons.Outlined.Videocam
                CameraMode.Document -> Icons.Outlined.Description
            },
            stringResource(
                when {
                    recording -> R.string.camera_stop_recording
                    mode == CameraMode.Photo -> R.string.camera_take_photo
                    mode == CameraMode.Document -> R.string.camera_scan_document
                    else -> R.string.camera_start_recording
                }
            ),
            tint = if (recording) ComposeColor.White else ComposeColor.Black,
            modifier = Modifier.size(38.dp)
        )
    }
}
