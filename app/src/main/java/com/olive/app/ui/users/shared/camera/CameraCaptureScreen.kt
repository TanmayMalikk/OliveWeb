// All safe-app roles: captures photos, video, and OCR documents.
package com.olive.app.ui.users.shared.camera

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*


import android.Manifest
import android.content.pm.PackageManager
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.media.MediaKind
import com.olive.app.ocr.OcrResult
import java.io.File
import java.util.UUID

private enum class CameraMode { Photo, Video, Document }
private data class CapturedMedia(
    val file: File,
    val mimeType: String,
    val name: String,
    val kind: MediaKind,
    val ocrResult: OcrResult? = null
)

// Shared safe app camera capture screen: Checks whether low ocr confidence. It prepares the picture before the app reads or saves it.
internal fun isLowOcrConfidence(result: OcrResult) =
    !result.success || !result.confidence.isFinite() || result.confidence !in .75f..1f

/** Embedded camera whose temporary media stays inside the app cache until it reaches safe storage. */
@Composable
internal fun CameraScreen(session: SessionViewModel, viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uid = session.coverAccountId ?: return
    val controller = session.documents
    val uploadError = stringResource(R.string.documents_upload_failed)
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    var captured by remember { mutableStateOf<CapturedMedia?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val busy by viewModel.busy.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val cameraError by viewModel.error.collectAsState()
    val preview =
        remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionGranted = it
        }
    val captureFolder = remember { File(context.cacheDir, "captured-media").apply { mkdirs() } }

    // Shared safe app camera capture screen: returns the new capture.
    fun newCapture(extension: String) = File(captureFolder, "${UUID.randomUUID()}.$extension")
    // returns the accept for shared safe app camera capture screen. It prepares the picture before the app reads or saves it.
    fun accept(file: File, mime: String, name: String, kind: MediaKind, ocrResult: OcrResult? = null) {
        pendingFile = null
        viewModel.leaveCamera()
        captured =
            CapturedMedia(file, mime, name, kind, ocrResult)
    }

    // returns the discard capture for shared safe app camera capture screen.
    fun discardCapture() {
        captured?.file?.delete()
        pendingFile?.delete()
        captured = null
        pendingFile =
            null
        viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        captureFolder.listFiles()?.forEach(File::delete)
        controller.prepare(uid)
    }
    LaunchedEffect(permissionGranted, captured) {
        if (permissionGranted && captured == null) viewModel.bind(lifecycleOwner, preview)
    }
    // Update discard capture when information used by this screen changes.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.leaveCamera()
            captured?.file?.delete()
            pendingFile?.delete()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_screen")) {
        // Choose what discard capture should do for the current value.
        when {
            !permissionGranted -> CameraPermissionPrompt(
                { permissionLauncher.launch(Manifest.permission.CAMERA) },
                session.navigation::back
            )

            captured != null -> CameraReview(
                captured!!,
                controllerLoading = controller.state.loading,
                uploadError = controller.state.error,
                onRetake = ::discardCapture,
                onSave = { media ->
                    val ocr = media.ocrResult?.let {
                        DocumentOcrMetadata(
                            it.extractedText,
                            it.confidence,
                            it.success
                        )
                    }
                    controller.uploadCaptured(
                        media.file,
                        media.name,
                        media.mimeType,
                        AccountIdentity(uid, session.coverUsername),
                        uploadError,
                        media.kind,
                        ocr
                    ) {
                        captured = null
                        session.navigation.back()
                    }
                })

            else -> {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = 150.dp)
                        .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == CameraMode.Photo,
                        onClick = { mode = CameraMode.Photo },
                        enabled = !busy && !recording,
                        modifier = Modifier.weight(1f).testTag("camera_mode_photo"),
                        label = { Text(stringResource(R.string.camera_photo)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) })
                    FilterChip(
                        selected = mode == CameraMode.Video,
                        onClick = { mode = CameraMode.Video },
                        enabled = !busy && !recording,
                        modifier = Modifier.weight(1f).testTag("camera_mode_video"),
                        label = { Text(stringResource(R.string.camera_video)) },
                        leadingIcon = { Icon(Icons.Outlined.Videocam, null) })
                    FilterChip(
                        selected = mode == CameraMode.Document,
                        onClick = { mode = CameraMode.Document },
                        enabled = !busy && !recording,
                        modifier = Modifier.weight(1f).testTag("camera_mode_document"),
                        label = { Text(stringResource(R.string.camera_document)) },
                        leadingIcon = { Icon(Icons.Outlined.Description, null) })
                }
                IconButton(
                    onClick = session.navigation::back,
                    Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                        .background(Color.Black.copy(alpha = .55f), CircleShape)
                        .testTag("camera_back")
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        stringResource(R.string.back),
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { viewModel.flip(lifecycleOwner, preview) },
                    enabled = ready && !busy && !recording,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                        .background(Color.Black.copy(alpha = .55f), CircleShape)
                        .testTag("camera_flip")
                ) {
                    Icon(
                        Icons.Outlined.Cameraswitch,
                        stringResource(R.string.camera_flip),
                        tint = Color.White
                    )
                }
                cameraError?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.Center).padding(24.dp)
                            .background(Color.Black.copy(alpha = .75f)).padding(12.dp),
                        color = Color.White
                    )
                }
                if (ready) Spacer(
                    Modifier.size(1.dp).align(Alignment.Center).testTag("camera_ready")
                )
                else if (cameraError == null) CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = Color.White
                )
                CameraShutter(
                    mode,
                    recording,
                    busy,
                    ready,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(bottom = 52.dp)
                ) {
                    // Choose what discard capture should do for the current value.
                    when {
                        mode == CameraMode.Photo -> {
                            val file = newCapture("jpg")
                            pendingFile = file
                            viewModel.capturePhoto(file) {
                                accept(
                                    it,
                                    "image/jpeg",
                                    "Olive photo.jpg",
                                    MediaKind.IMAGE,
                                )
                            }
                        }

                        mode == CameraMode.Document -> {
                            val file = newCapture("jpg")
                            pendingFile = file
                            viewModel.captureDocument(file) { capturedFile, result ->
                                accept(
                                    capturedFile,
                                    "image/jpeg",
                                    "Olive document.jpg",
                                    MediaKind.DOCUMENT,
                                    result,
                                )
                            }
                        }

                        recording -> viewModel.stopVideo()
                        else -> {
                            val file = newCapture("mp4")
                            pendingFile = file
                            viewModel.startVideo(file, audioGranted) {
                                accept(
                                    it,
                                    "video/mp4",
                                    "Olive video.mp4",
                                    MediaKind.VIDEO,
                                )
                            }
                        }
                    }
                }
                if (busy && mode == CameraMode.Document) Surface(
                    Modifier.align(Alignment.Center).testTag("camera_ocr_processing"),
                    color = Color.Black.copy(alpha = .78f), shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Text(stringResource(R.string.camera_scanning_document), color = Color.White)
                    }
                }
                if (recording) Text(
                    stringResource(R.string.camera_recording),
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding().padding(bottom = 132.dp)
                        .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("camera_recording_active"),
                    color = Color.White
                )
            }
        }
    }
}
// Shows camera permission prompt for shared safe app camera capture screen.
@Composable
private fun CameraPermissionPrompt(request: () -> Unit, back: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.camera_permission_required), color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = request) { Text(stringResource(R.string.grant_camera_permission)) }
        TextButton(onClick = back) { Text(stringResource(R.string.cancel), color = Color.White) }
    }
}

// This shows the camera shutter view for shared safe app camera capture screen. It fills the view with the latest details and actions.
@Composable
private fun CameraShutter(
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
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) Color.Red else Color.White)
    ) {
        Icon(
            when (mode) {
                CameraMode.Photo -> Icons.Outlined.PhotoCamera
                CameraMode.Video -> Icons.Outlined.Videocam
                CameraMode.Document -> Icons.Outlined.Description
            },
            stringResource(
                // Choose what camera shutter should do for the current value.
                when {
                    recording -> R.string.camera_stop_recording
                    mode == CameraMode.Photo -> R.string.camera_take_photo
                    mode == CameraMode.Document -> R.string.camera_scan_document
                    else -> R.string.camera_start_recording
                }
            ),
            tint = if (recording) Color.White else Color.Black, modifier = Modifier.size(38.dp)
        )
    }
}
// Shows camera review for shared safe app camera capture screen.
@Composable
private fun CameraReview(
    media: CapturedMedia,
    controllerLoading: Boolean,
    uploadError: String?,
    onRetake: () -> Unit,
    onSave: (CapturedMedia) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (media.mimeType.startsWith("image/")) AsyncImage(
            model = media.file, contentDescription = stringResource(R.string.captured_photo_review),
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
        )
        else AndroidView(factory = { context ->
            VideoView(context).apply {
                setVideoPath(media.file.absolutePath)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            }
        }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            media.ocrResult?.let { result ->
                Surface(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("camera_ocr_result"),
                    color = Color.Black.copy(alpha = .78f), shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(if (result.success) R.string.camera_ocr_complete else R.string.camera_ocr_failed),
                            style = MaterialTheme.typography.titleMedium, color = Color.White
                        )
                        Text(
                            stringResource(
                                R.string.camera_ocr_confidence,
                                (result.confidence * 100).toInt()
                            ),
                            style = MaterialTheme.typography.labelMedium, color = Color.White
                        )
                        if (isLowOcrConfidence(result)) Text(
                            stringResource(R.string.camera_ocr_low_confidence),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.testTag("camera_ocr_low_confidence")
                        )
                        Text(
                            result.extractedText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.testTag("camera_ocr_text")
                        )
                    }
                }
            }
            if (controllerLoading) Text(
                stringResource(R.string.camera_saving), color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("camera_saving")
            )
            uploadError?.let {
                AccessibleMessage(
                    it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.background(
                        Color.Black.copy(alpha = .72f),
                        MaterialTheme.shapes.small
                    ).padding(10.dp).testTag("camera_upload_error")
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onRetake,
                    enabled = !controllerLoading,
                    modifier = Modifier.weight(1f).testTag("camera_retake"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(stringResource(R.string.take_again)) }
                Button(
                    onClick = { onSave(media) },
                    enabled = !controllerLoading,
                    modifier = Modifier.weight(1f).testTag("camera_save")
                ) {
                    if (controllerLoading) CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    else Text(
                        stringResource(
                            if (media.ocrResult?.let(::isLowOcrConfidence) == true)
                                R.string.camera_save_anyway else R.string.camera_save
                        )
                    )
                }
            }
        }
    }
}
