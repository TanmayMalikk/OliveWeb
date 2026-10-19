package com.olive.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.R

/**
 * ============================================================================
 * CameraScreen Composable
 * ============================================================================
 * Purpose: Provides a secure in-app camera interface for taking photos into memory
 * without saving them to the device's public gallery.
 * 
 * Key Features:
 * 1. Runtime Permission Check & Request: Prompts the user for camera permission if not already granted.
 * 2. Realistic Camera UI: Live preview with a circular white shutter button in the bottom center.
 * 3. Corrected Orientation: Automatically corrects the 90-degree portrait orientation rotation on review.
 * 4. Photo Review Mode: Once captured, the camera closes/hides and the photo is presented on screen 
 *    with options to "Take Again" or "Done".
 * 5. Localized Strings: Uses R.string resource strings for all user-facing text.
 * ============================================================================
 */
@Composable
fun CameraScreen(
    session: SessionViewModel,
    viewModel: CameraViewModel = viewModel(),
) {
    // 1. Context: Needed for accessing system resources and checking permissions.
    val context = LocalContext.current

    // 2. LifecycleOwner: Required by CameraX to tie camera lifecycle to the current UI component.
    val lifecycleOwner = LocalLifecycleOwner.current

    // 3. Processing State: Observes whether the camera manager is currently capturing/processing.
    val isProcessing by viewModel.isProcessing.collectAsState()

    // 4. Camera Permission State: Tracks whether CAMERA permission is granted.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 5. Permission Launcher: Handles the system permission request dialog callback.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Request camera permission automatically when the screen opens if not yet granted.
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 6. Captured Photo State: Holds raw image bytes in memory.
    // - If null: We show the live camera preview.
    // - If not null: The camera is closed and we show the Photo Review screen.
    var capturedImageBytes by remember { mutableStateOf<ByteArray?>(null) }

    // 7. PreviewView: Remembers a single instance of CameraX PreviewView across UI recompositions.
    val previewView = remember { PreviewView(context) }

    // 8. Bind Camera: Binds camera when permission is granted AND we are in live preview mode (capturedImageBytes == null).
    LaunchedEffect(hasCameraPermission, capturedImageBytes) {
        if (hasCameraPermission && (capturedImageBytes == null)) {
            viewModel.cameraManager.bindCamera(lifecycleOwner, previewView)
        }
    }

    // Outer container filling the entire screen with a black background.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ========================================================================
        // SCENARIO A: CAMERA PERMISSION NOT GRANTED
        // ========================================================================
        if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text(stringResource(R.string.grant_camera_permission))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { session.navigate(SafePage.Home) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } 
        // ========================================================================
        // SCENARIO B: LIVE CAMERA PREVIEW & SHUTTER BUTTON (capturedImageBytes == null)
        // ========================================================================
        else if (capturedImageBytes == null) {
            // AndroidView embeds the traditional CameraX PreviewView inside Jetpack Compose.
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Top-left back button to return to Safe Home screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.TopStart
            ) {
                IconButton(
                    onClick = { session.navigate(SafePage.Home) },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
            }

            // Bottom control layout reflecting real phone cameras: circular white shutter button.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Circular White Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White, shape = CircleShape)
                        .clickable(enabled = !isProcessing) {
                            // Trigger photo capture into RAM as ByteArray.
                            viewModel.capturePhotoForProcessing { imageBytes ->
                                // Once captured, update state. This closes/hides the camera 
                                // and transitions to the Photo Review screen below.
                                capturedImageBytes = imageBytes
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isProcessing) {
                        // Show loading indicator inside shutter button while capturing
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.Black,
                            strokeWidth = 3.dp
                        )
                    } else {
                        // Inner circle styling for realistic camera shutter appearance
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .background(Color.White, shape = CircleShape)
                                .padding(4.dp)
                                .background(Color.LightGray.copy(alpha = 0.3f), shape = CircleShape)
                        )
                    }
                }
            }
        } 
        // ========================================================================
        // SCENARIO C: PHOTO REVIEW SCREEN (Camera closed, photo presented)
        // ========================================================================
        else {
            // Decode the captured raw ByteArray into an Android Bitmap and correct portrait orientation (90 deg rotation).
            val bitmap = remember(capturedImageBytes) {
                capturedImageBytes?.let { bytes ->
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val matrix = android.graphics.Matrix().apply {
                        postRotate(90f)
                    }
                    android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                }
            }

            // Convert Android Bitmap into Compose ImageBitmap for rendering.
            val imageBitmap = remember(bitmap) {
                bitmap?.asImageBitmap()
            }

            if (imageBitmap != null) {
                // Display the captured photo filling the screen.
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.captured_photo_review),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Fallback message if decoding fails.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.error_loading_photo_preview), color = Color.White)
                }
            }

            // Bottom action bar for reviewing the photo with "Take Again" or "Done" options.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Option 1: Take Again -> Clears captured photo and re-opens live camera preview.
                    Button(
                        onClick = {
                            capturedImageBytes = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text(stringResource(R.string.take_again), color = Color.White)
                    }

                    // Option 2: Done -> Needs no functionality right now (left intentionally empty).
                    Button(
                        onClick = {
                            // As requested: "done needs no functionality right now"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}
