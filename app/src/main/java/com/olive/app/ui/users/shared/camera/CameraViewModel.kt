// All safe-app roles: holds camera view state.
package com.olive.app.ui.users.shared.camera

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*


import android.app.Application
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.olive.app.data.CameraManager
import com.olive.app.ocr.OcrResult
import com.olive.app.ocr.PaddleOcrManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private var documentJob: Job? = null
    private var pendingDocumentFile: File? = null
    private var documentGeneration = 0

    fun bind(owner: LifecycleOwner, preview: PreviewView) {
        viewModelScope.launch {
            _ready.value = false
            runCatching {
                camera.bindCamera(
                    owner,
                    preview,
                    _frontFacing.value
                )
            }.onSuccess { _ready.value = true }
                .onFailure { _error.value = it.localizedMessage ?: "Camera unavailable" }
        }
    }

    fun flip(owner: LifecycleOwner, preview: PreviewView) {
        if (_busy.value || _recording.value) return
        viewModelScope.launch {
            val previous = _frontFacing.value
            _ready.value = false
            _error.value = null
            runCatching { camera.bindCamera(owner, preview, !previous) }
                .onSuccess {
                    _frontFacing.value = !previous
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

    fun capturePhoto(file: File, onCaptured: (File) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            runCatching { camera.capturePhoto(file) }.onSuccess(onCaptured)
                .onFailure { _error.value = it.localizedMessage ?: "Photo capture failed" }
            _busy.value = false
        }
    }

    fun captureDocument(file: File, onCaptured: (File, OcrResult) -> Unit) {
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
                pendingDocumentFile = null
                documentJob = null
                onCaptured(captured, result)
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

    fun stopVideo() = camera.stopVideo()
    // Clears error.
    fun clearError() {
        _error.value = null
    }

    fun leaveCamera() {
        documentGeneration++
        documentJob?.cancel()
        documentJob = null
        pendingDocumentFile?.delete()
        pendingDocumentFile = null
        camera.unbind()
        _ready.value = false
        _recording.value = false
        _busy.value = false
    }

    override fun onCleared() {
        leaveCamera()
        camera.release()
        CoroutineScope(NonCancellable + Dispatchers.IO).launch { ocr.release() }
    }
}
