package com.olive.app.ui

import android.app.Application
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.olive.app.data.CameraManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application:Application):AndroidViewModel(application) {
    private val camera=CameraManager(application)
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    private val _recording=MutableStateFlow(false)
    val recording=_recording.asStateFlow()
    private val _ready=MutableStateFlow(false)
    val ready=_ready.asStateFlow()
    private val _error=MutableStateFlow<String?>(null)
    val error=_error.asStateFlow()
    private val _frontFacing=MutableStateFlow(false)

    fun bind(owner:LifecycleOwner,preview:PreviewView) {
        viewModelScope.launch {
            _ready.value=false
            runCatching { camera.bindCamera(owner,preview,_frontFacing.value) }.onSuccess { _ready.value=true }
                .onFailure { _error.value=it.localizedMessage ?: "Camera unavailable" }
        }
    }

    fun flip(owner:LifecycleOwner,preview:PreviewView) {
        if(_busy.value || _recording.value) return
        viewModelScope.launch {
            val previous=_frontFacing.value
            _ready.value=false;_error.value=null
            runCatching { camera.bindCamera(owner,preview,!previous) }
                .onSuccess { _frontFacing.value=!previous;_ready.value=true }
                .onFailure {
                    _frontFacing.value=previous
                    runCatching { camera.bindCamera(owner,preview,previous) }
                    _ready.value=true;_error.value=it.localizedMessage ?: "Camera unavailable"
                }
        }
    }

    fun capturePhoto(file:File,onCaptured:(File)->Unit) {
        viewModelScope.launch {
            _busy.value=true;_error.value=null
            runCatching { camera.capturePhoto(file) }.onSuccess(onCaptured)
                .onFailure { _error.value=it.localizedMessage ?: "Photo capture failed" }
            _busy.value=false
        }
    }

    fun startVideo(file:File,includeAudio:Boolean,onCaptured:(File)->Unit) {
        _error.value=null
        runCatching {
            camera.startVideo(file,includeAudio,onStarted={ _recording.value=true }) { result ->
                _recording.value=false;_busy.value=false
                result.onSuccess(onCaptured).onFailure { _error.value=it.localizedMessage ?: "Video capture failed" }
            }
            _busy.value=true
        }.onFailure { _busy.value=false;_error.value=it.localizedMessage ?: "Video capture failed" }
    }

    fun stopVideo()=camera.stopVideo()
    fun clearError() { _error.value=null }
    fun leaveCamera() { camera.unbind();_ready.value=false;_recording.value=false;_busy.value=false }
    override fun onCleared() { camera.release() }
}
