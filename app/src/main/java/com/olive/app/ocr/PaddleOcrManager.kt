package com.olive.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrResult(
    val success: Boolean,
    val extractedText: String,
    val confidence: Float,
    val detectedFields: Map<String, String> = emptyMap()
)

/*
Class PaddleOcrManager
    Purpose: Delegates directly to the official com.paddle.ocr.PaddleOCR SDK module,
    initializing OpenCV native libraries in init to prevent UnsatisfiedLinkError.
 */
class PaddleOcrManager(private val context: Context) {

    private var paddleOcr: PaddleOCR? = null

    init {
        try {
            // Initialize OpenCV native libraries immediately upon instantiation
            OpenCVUtils.init(context.applicationContext)
        } catch (e: Exception) {
            Log.e("PaddleOcrManager", "Failed to initialize OpenCV in init", e)
        }
    }

    private suspend fun ensureInitialized(): PaddleOCR {
        if (paddleOcr == null) {
            try {
                OpenCVUtils.init(context.applicationContext)
                paddleOcr = PaddleOCR.create(context)
            } catch (e: Exception) {
                Log.e("PaddleOcrManager", "Failed to initialize PaddleOCR instance", e)
                throw e
            }
        }
        return paddleOcr!!
    }

    suspend fun release() {
        try {
            paddleOcr?.release()
        } catch (e: Exception) {
            Log.e("PaddleOcrManager", "Error releasing SDK", e)
        }
        paddleOcr = null
    }

    suspend fun recognizeDocument(imageBytes: ByteArray): OcrResult = withContext(Dispatchers.IO) {
        try {
            val ocr = ensureInitialized()
            val runResult = ocr.recognize(imageBytes)

            val extractedText = runResult.results.joinToString("\n") { it.text }
            val avgConfidence = if (runResult.results.isNotEmpty()) {
                runResult.results.map { it.confidence }.average().toFloat()
            } else {
                0.95f
            }

            val fields = parseFields(extractedText)

            OcrResult(
                success = true,
                extractedText = extractedText.ifBlank { "No text recognized from document." },
                confidence = avgConfidence,
                detectedFields = fields
            )
        } catch (e: Exception) {
            Log.e("PaddleOcrManager", "PaddleOCR SDK recognition failed", e)
            OcrResult(false, "OCR Error: ${e.localizedMessage ?: "Unknown exception"}", 0f)
        }
    }

    suspend fun recognizeBitmap(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        try {
            val ocr = ensureInitialized()
            val runResult = ocr.recognize(bitmap)

            val extractedText = runResult.results.joinToString("\n") { it.text }
            val avgConfidence = if (runResult.results.isNotEmpty()) {
                runResult.results.map { it.confidence }.average().toFloat()
            } else {
                0.95f
            }

            val fields = parseFields(extractedText)

            OcrResult(
                success = true,
                extractedText = extractedText.ifBlank { "No text recognized from bitmap." },
                confidence = avgConfidence,
                detectedFields = fields
            )
        } catch (e: Exception) {
            Log.e("PaddleOcrManager", "PaddleOCR SDK recognition failed", e)
            OcrResult(false, "OCR Error: ${e.localizedMessage ?: "Unknown exception"}", 0f)
        }
    }

    private fun parseFields(text: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        
        for (line in lines) {
            val upper = line.uppercase()
            if (upper.contains("NO") || upper.contains("ID") || upper.contains("DL") || upper.contains("LICENSE") || upper.any { it.isDigit() }) {
                if (!fields.containsKey("document_number") && line.length >= 5) {
                    fields["document_number"] = line
                }
            }
            if (upper.contains("NAME") || upper.contains("HOLDER") || upper.contains("FULL")) {
                if (!fields.containsKey("holder_name")) {
                    fields["holder_name"] = line
                }
            }
        }

        if (!fields.containsKey("document_number")) fields["document_number"] = lines.firstOrNull { it.any { c -> c.isDigit() } } ?: "Not detected"
        if (!fields.containsKey("holder_name")) fields["holder_name"] = lines.firstOrNull { it != fields["document_number"] && !it.any { c -> c.isDigit() } } ?: "Not detected"

        return fields
    }
}
