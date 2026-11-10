// All safe-app roles: holds camera view state and converts document captures to PDF.
package com.olive.app.ui.users.shared.camera

import android.app.Application
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.olive.app.data.CameraManager
import com.olive.app.ocr.OcrResult
import com.olive.app.ocr.PaddleOcrManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

