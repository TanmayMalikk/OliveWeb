package com.olive.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Cameraswitch
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
import java.io.File
import java.util.UUID

private enum class CameraMode { Photo, Video }
private data class CapturedMedia(val file:File,val mimeType:String,val name:String)

/** Embedded camera whose temporary media stays inside the app cache until it reaches safe storage. */
@Composable internal fun CameraScreen(session:SessionViewModel,viewModel:CameraViewModel=viewModel()) {
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val uid=session.coverAccountId ?: return
    val controller=session.documents
    val uploadError=stringResource(R.string.documents_upload_failed)
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val audioGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    var captured by remember { mutableStateOf<CapturedMedia?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val busy by viewModel.busy.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val cameraError by viewModel.error.collectAsState()
    val preview=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FILL_CENTER } }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted=it }
    val captureFolder=remember { File(context.cacheDir,"captured-media").apply { mkdirs() } }

    fun newCapture(extension:String)=File(captureFolder,"${UUID.randomUUID()}.$extension")
    fun accept(file:File,mime:String,name:String) {
        pendingFile=null;viewModel.leaveCamera();captured=CapturedMedia(file,mime,name)
    }
    fun discardCapture() {
        captured?.file?.delete();pendingFile?.delete();captured=null;pendingFile=null;viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        captureFolder.listFiles()?.forEach(File::delete)
        controller.prepare(uid)
    }
    LaunchedEffect(permissionGranted,captured) {
        if(permissionGranted && captured==null) viewModel.bind(lifecycleOwner,preview)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.leaveCamera();captured?.file?.delete();pendingFile?.delete() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_screen")) {
        when {
            !permissionGranted -> CameraPermissionPrompt({permissionLauncher.launch(Manifest.permission.CAMERA)},session::back)
            captured!=null -> CameraReview(captured!!,controllerLoading=controller.state.loading,
                uploadError=controller.state.error,onRetake=::discardCapture,onSave={ media ->
                controller.uploadCaptured(media.file,media.name,media.mimeType,AccountIdentity(uid,session.coverUsername),uploadError) {
                    captured=null;session.back()
                }
            })
            else -> {
                AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=150.dp)
                    .background(Color.Black.copy(alpha=.5f),RoundedCornerShape(24.dp)).padding(horizontal=8.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=mode==CameraMode.Photo,onClick={if(!recording) mode=CameraMode.Photo},
                        label={Text(stringResource(R.string.camera_photo))},leadingIcon={Icon(Icons.Outlined.PhotoCamera,null)})
                    FilterChip(selected=mode==CameraMode.Video,onClick={if(!recording) mode=CameraMode.Video},modifier=Modifier.testTag("camera_mode_video"),
                        label={Text(stringResource(R.string.camera_video))},leadingIcon={Icon(Icons.Outlined.Videocam,null)})
                }
                IconButton(onClick=session::back,Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                    .background(Color.Black.copy(alpha=.55f),CircleShape).testTag("camera_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.back),tint=Color.White)
                }
                IconButton(onClick={viewModel.flip(lifecycleOwner,preview)},enabled=ready && !busy && !recording,
                    modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                        .background(Color.Black.copy(alpha=.55f),CircleShape).testTag("camera_flip")) {
                    Icon(Icons.Outlined.Cameraswitch,stringResource(R.string.camera_flip),tint=Color.White)
                }
                cameraError?.let { Text(it,Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha=.75f)).padding(12.dp),color=Color.White) }
                if(ready) Spacer(Modifier.size(1.dp).align(Alignment.Center).testTag("camera_ready"))
                else if(cameraError==null) CircularProgressIndicator(Modifier.align(Alignment.Center),color=Color.White)
                CameraShutter(mode,recording,busy,ready,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=52.dp)) {
                    if(mode==CameraMode.Photo) {
                        val file=newCapture("jpg");pendingFile=file
                        viewModel.capturePhoto(file) { accept(it,"image/jpeg","Olive photo.jpg") }
                    } else if(recording) viewModel.stopVideo()
                    else {
                        val file=newCapture("mp4");pendingFile=file
                        viewModel.startVideo(file,audioGranted) { accept(it,"video/mp4","Olive video.mp4") }
                    }
                }
                if(recording) Text(stringResource(R.string.camera_recording),Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom=132.dp).background(Color.Black.copy(alpha=.55f),RoundedCornerShape(16.dp))
                    .padding(horizontal=12.dp,vertical=6.dp).testTag("camera_recording_active"),color=Color.White)
            }
        }
    }
}

@Composable private fun CameraPermissionPrompt(request:()->Unit,back:()->Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(stringResource(R.string.camera_permission_required),color=Color.White)
        Spacer(Modifier.height(16.dp));Button(onClick=request) { Text(stringResource(R.string.grant_camera_permission)) }
        TextButton(onClick=back) { Text(stringResource(R.string.cancel),color=Color.White) }
    }
}

@Composable private fun CameraShutter(mode:CameraMode,recording:Boolean,busy:Boolean,ready:Boolean,modifier:Modifier,onClick:()->Unit) {
    FilledIconButton(onClick=onClick,enabled=ready && (!busy || recording),modifier=modifier.size(84.dp).testTag("camera_shutter"),
        shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(recording) Color.Red else Color.White)) {
        Icon(if(mode==CameraMode.Photo) Icons.Outlined.PhotoCamera else Icons.Outlined.Videocam,
            stringResource(if(recording) R.string.camera_stop_recording else if(mode==CameraMode.Photo) R.string.camera_take_photo else R.string.camera_start_recording),
            tint=if(recording) Color.White else Color.Black,modifier=Modifier.size(38.dp))
    }
}

@Composable private fun CameraReview(media:CapturedMedia,controllerLoading:Boolean,uploadError:String?,onRetake:()->Unit,onSave:(CapturedMedia)->Unit) {
    Box(Modifier.fillMaxSize()) {
        if(media.mimeType.startsWith("image/")) AsyncImage(model=media.file,contentDescription=stringResource(R.string.captured_photo_review),
            contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
        else AndroidView(factory={ context -> VideoView(context).apply {
            setVideoPath(media.file.absolutePath);setOnPreparedListener { player -> player.isLooping=true;start() }
        } },modifier=Modifier.fillMaxSize(),onRelease={it.stopPlayback()})
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(start=24.dp,end=24.dp,bottom=104.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(controllerLoading) Text(stringResource(R.string.camera_saving),color=Color.White,
                modifier=Modifier.align(Alignment.CenterHorizontally).testTag("camera_saving"))
            uploadError?.let { AccessibleMessage(it,color=MaterialTheme.colorScheme.error,
                modifier=Modifier.background(Color.Black.copy(alpha=.72f),MaterialTheme.shapes.small).padding(10.dp).testTag("camera_upload_error")) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick=onRetake,enabled=!controllerLoading,modifier=Modifier.weight(1f).testTag("camera_retake"),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text(stringResource(R.string.take_again)) }
                Button(onClick={onSave(media)},enabled=!controllerLoading,modifier=Modifier.weight(1f).testTag("camera_save")) {
                    if(controllerLoading) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                    else Text(stringResource(R.string.camera_save))
                }
            }
        }
    }
}
