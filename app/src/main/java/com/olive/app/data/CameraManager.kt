package com.olive.app.data

import android.content.Context
import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import android.util.Size
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** CameraX adapter that writes only to app-private files supplied by the caller. */
class CameraManager(private val context:Context) {
    private val cameraExecutor:ExecutorService=Executors.newSingleThreadExecutor()
    private var provider:ProcessCameraProvider?=null
    private var imageCapture:ImageCapture?=null
    private var videoCapture:VideoCapture<Recorder>?=null
    private var recording:Recording?=null

    suspend fun bindCamera(lifecycleOwner:LifecycleOwner,previewView:PreviewView,frontFacing:Boolean=false) {
        val cameraProvider=ProcessCameraProvider.awaitInstance(context)
        val rotation=previewView.display?.rotation
        val previewBuilder=Preview.Builder()
        val imageResolution=ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1920,1080),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val imageBuilder=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(imageResolution)
        val recorderBuilder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD))
        if(rotation!=null) { previewBuilder.setTargetRotation(rotation);imageBuilder.setTargetRotation(rotation) }
        val preview=previewBuilder.build().also { it.surfaceProvider=previewView.surfaceProvider }
        imageCapture=imageBuilder.build()
        videoCapture=VideoCapture.withOutput(recorderBuilder.build())
        cameraProvider.unbindAll()
        val selector=if(frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.bindToLifecycle(lifecycleOwner,selector,preview,imageCapture,videoCapture)
        provider=cameraProvider
    }

    suspend fun capturePhoto(file:File):File = suspendCancellableCoroutine { continuation ->
        val capture=imageCapture
        if(capture==null) { continuation.resumeWithException(IllegalStateException("Camera not ready"));return@suspendCancellableCoroutine }
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),cameraExecutor,
            object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                    if(continuation.isActive) continuation.resume(file)
                }
                override fun onError(error:ImageCaptureException) {
                    file.delete()
                    if(continuation.isActive) continuation.resumeWithException(error)
                }
            })
        continuation.invokeOnCancellation { file.delete() }
    }

    @SuppressLint("MissingPermission")
    fun startVideo(file:File,includeAudio:Boolean,onStarted:()->Unit,onFinished:(Result<File>)->Unit) {
        check(recording==null) { "A video is already recording" }
        val capture=checkNotNull(videoCapture) { "Camera not ready" }
        val pending=capture.output.prepareRecording(context,FileOutputOptions.Builder(file).build())
        recording=(if(includeAudio) pending.withAudioEnabled() else pending)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if(event is VideoRecordEvent.Start) onStarted()
                else if(event is VideoRecordEvent.Finalize) {
                    recording=null
                    if(event.hasError()) {
                        file.delete()
                        onFinished(Result.failure(event.cause ?: IllegalStateException("Video capture failed (${event.error})")))
                    } else onFinished(Result.success(file))
                }
            }
    }

    fun stopVideo() { recording?.stop() }
    fun cancelVideo() { recording?.close();recording=null }
    fun unbind() { cancelVideo();provider?.unbindAll();provider=null;imageCapture=null;videoCapture=null }
    fun release() { unbind();cameraExecutor.shutdown() }
}
