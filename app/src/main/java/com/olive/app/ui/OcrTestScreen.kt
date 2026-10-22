package com.olive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ocr.PaddleOcrManager
import com.olive.app.ocr.OrientationPreprocessor
import com.olive.app.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
   OcrTestScreen: A dedicated, isolated testing screen for PaddleOCR document checking
   and visual orientation preprocessing feedback.
 */
@Composable
fun OcrTestScreen(session: SessionViewModel, onBack: () -> Unit = session::back) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ocrManager = remember { PaddleOcrManager(context) }
    val orientationPreprocessor = remember { OrientationPreprocessor(context) }

    var isRunning by remember { mutableStateOf(value = false) }
    var ocrResult by remember { mutableStateOf<OcrResult?>(value = null) }
    var statusMessage by remember { mutableStateOf(value = "Ready to test orientation preprocessor and PaddleOCR on test_doc.png.") }
    var rawPreviewBitmap by remember { mutableStateOf<Bitmap?>(value = null) }
    var processedPreviewBitmap by remember { mutableStateOf<Bitmap?>(value = null) }

    fun runOcrPipeline() {
        isRunning = true
        statusMessage = "Loading test document..."
        coroutineScope.launch(Dispatchers.IO) {
            val sampleBytes = runCatching {
                context.assets.open("models/test_doc.png").use { it.readBytes() }
            }.getOrNull() ?: runCatching {
                context.assets.open("test_doc.png").use { it.readBytes() }
            }.getOrNull() ?: runCatching {
                context.assets.open("models/test_doc.jpg").use { it.readBytes() }
            }.getOrNull() ?: runCatching {
                context.assets.open("test_doc.jpg").use { it.readBytes() }
            }.getOrNull()

            if (sampleBytes == null) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Error: No test image found in assets."
                    isRunning = false
                }
                return@launch
            }

            // Decode raw bitmap for preview
            val rawBmp = BitmapFactory.decodeByteArray(sampleBytes, 0, sampleBytes.size)

            withContext(Dispatchers.Main) {
                rawPreviewBitmap = rawBmp
                statusMessage = "Preprocessing orientation (detecting & straightening rotation)..."
            }

            // Run orientation preprocessor
            val processedBytes = orientationPreprocessor.process(sampleBytes)
            val processedBmp = BitmapFactory.decodeByteArray(processedBytes, 0, processedBytes.size)

            withContext(Dispatchers.Main) {
                processedPreviewBitmap = processedBmp
                statusMessage = "Running PaddleOCR on reoriented image..."
            }

            // Run OCR
            val result = ocrManager.recognizeDocument(processedBytes)

            withContext(Dispatchers.Main) {
                ocrResult = result
                isRunning = false
                statusMessage = if (result.success) "OCR & Orientation Pipeline Successful!" else "OCR Failed."
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ocr_test_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }

            Text(stringResource(R.string.ocr_test_description), style = MaterialTheme.typography.bodyMedium)

            Button(
                onClick = ::runOcrPipeline,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Processing Pipeline..." else "Run Preprocessor & PaddleOCR")
            }

            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

            // Visual Feedback Section for Raw vs Processed (Reoriented) Images
            val rawBmp = rawPreviewBitmap
            val processedBmp = processedPreviewBitmap
            if ((rawBmp != null) || (processedBmp != null)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("🖼️ Visual Orientation Feedback", style = MaterialTheme.typography.titleMedium)

                        if (rawBmp != null) {
                            Text("1. Original Raw Image (Loaded from Assets):", style = MaterialTheme.typography.labelMedium)
                            Card(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = rawBmp.asImageBitmap(),
                                        contentDescription = "Raw test document",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        if (processedBmp != null) {
                            Text("2. Processed & Straightened Image (Fed to Detector):", style = MaterialTheme.typography.labelMedium)
                            Card(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = processedBmp.asImageBitmap(),
                                        contentDescription = "Processed upright document",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (ocrResult != null) {
                val res = ocrResult!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ocr_raw_output), style = MaterialTheme.typography.titleMedium)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("📄 Full Extracted Text:", style = MaterialTheme.typography.labelMedium)
                                Text(res.extractedText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.ocr_success,res.success))
                        Text(stringResource(R.string.ocr_confidence,(res.confidence * 100).toInt()))
                        
                        if (res.detectedFields.isNotEmpty()) {
                            HorizontalDivider()
                            Text(stringResource(R.string.ocr_parsed_fields), style = MaterialTheme.typography.labelMedium)
                            res.detectedFields.forEach { (k, v) ->
                                Text("• $k: $v", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
