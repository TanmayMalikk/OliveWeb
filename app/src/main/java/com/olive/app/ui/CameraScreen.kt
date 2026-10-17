package com.olive.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    onUploadComplete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isProcessing by viewModel.isProcessing.collectAsState()

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        viewModel.cameraManager.bindCamera(lifecycleOwner, previewView)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Button(
            onClick = {
                viewModel.capturePhotoForProcessing { imageBytes ->
                    // TODO: Send imageBytes to your backend API or ML processor
                    // Memory is garbage-collected automatically once this lambda finishes.
                    onUploadComplete()
                }
            },
            enabled = !isProcessing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text(if (isProcessing) "Processing..." else "Capture & Process")
        }
    }
}