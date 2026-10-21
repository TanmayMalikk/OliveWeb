package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.olive.app.ocr.PaddleOcrManager
import com.olive.app.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
   OcrTestScreen: A dedicated, isolated testing screen for PaddleOCR document checking.
   Tests strictly against actual user-provided document photos (.png / .jpg) in assets and displays extracted identity fields.
 */
@Composable
fun OcrTestScreen(session: SessionViewModel, onBack: () -> Unit = session::back) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ocrManager = remember { PaddleOcrManager(context) }

    var isRunning by remember { mutableStateOf(false) }
    var ocrResult by remember { mutableStateOf<OcrResult?>(null) }
    var statusMessage by remember { mutableStateOf("Ready to test PaddleOCR on your actual document.") }

    fun runOcrOnBytes(bytes: ByteArray) {
        isRunning = true
        statusMessage = "Analyzing document with PaddleOCR..."
        coroutineScope.launch(Dispatchers.IO) {
            val result = ocrManager.recognizeDocument(bytes)
            withContext(Dispatchers.Main) {
                ocrResult = result
                isRunning = false
                statusMessage = if (result.success) "OCR Test Successful!" else "OCR Test Failed."
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
                Text("PaddleOCR Testing", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }

            Text("Place your actual document image (e.g. test_doc.png or test_doc.jpg) into your project's assets/ or assets/OCRmodels/ folder, then click below to test.", style = MaterialTheme.typography.bodyMedium)

            Button(
                onClick = {
                    val sampleBytes = runCatching {
                        context.assets.open("models/test_doc.png").use { it.readBytes() }
                    }.getOrNull() ?: runCatching {
                        context.assets.open("test_doc.png").use { it.readBytes() }
                    }.getOrNull() ?: runCatching {
                        context.assets.open("models/test_doc.jpg").use { it.readBytes() }
                    }.getOrNull() ?: runCatching {
                        context.assets.open("test_doc.jpg").use { it.readBytes() }
                    }.getOrNull()

                    if (sampleBytes != null) {
                        runOcrOnBytes(sampleBytes)
                    } else {
                        statusMessage = "Error: No test image found. Please add test_doc.png or test_doc.jpg to your assets/ or assets/OCRmodels/ folder."
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Running OCR..." else "Run OCR on Actual Test Document")
            }

            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

            if (ocrResult != null) {
                val res = ocrResult!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OCR Results Output", style = MaterialTheme.typography.titleMedium)

                        // Prominently display Name and ID Number extracted from document
                        val holderName = res.detectedFields["holder_name"] ?: "Not detected"
                        val docNumber = res.detectedFields["document_number"] ?: "Not detected"

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("👤 Holder Name: $holderName", style = MaterialTheme.typography.titleSmall)
                                Text("🆔 ID / Passport Number: $docNumber", style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Success: ${res.success}")
                        Text("Confidence: ${(res.confidence * 100).toInt()}%")
                        HorizontalDivider()
                        Text("Extracted Text:", style = MaterialTheme.typography.labelMedium)
                        Text(res.extractedText, style = MaterialTheme.typography.bodySmall)
                        if (res.detectedFields.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Detected Fields:", style = MaterialTheme.typography.labelMedium)
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
