package com.olive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olive.app.data.CameraManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    val cameraManager = CameraManager(application)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun capturePhotoForProcessing(onImageCaptured: (ByteArray) -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null
            try {
                // Photo exists ONLY in memory as a ByteArray
                val imageBytes = cameraManager.captureToMemory()

                // Perform your upload / processing here
                onImageCaptured(imageBytes)

            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Capture failed"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
    }
}
