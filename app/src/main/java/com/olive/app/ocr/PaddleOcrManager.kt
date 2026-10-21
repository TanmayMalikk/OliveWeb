package com.olive.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrResult(
    val success: Boolean,
    val extractedText: String,
    val confidence: Float,
    val rawResults: List<OCRResult> = emptyList(),
    val detectedFields: Map<String, String> = emptyMap()
)

/*
Class PaddleOcrManager
    Purpose: Delegates directly to the official com.paddle.ocr.PaddleOCR SDK module,
    returning pure raw text output without per-line confidence annotations.
 */
class PaddleOcrManager(private val context: Context) {

    private var paddleOcr: PaddleOCR? = null

    private suspend fun ensureInitialized(): PaddleOCR {
        if (paddleOcr == null) {
            try {
                OpenCVUtils.init(context.applicationContext)
                paddleOcr = PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(
                        detImgMode = "BGR",
                        detLimitSideLen = 960,
                        detLimitType = "max",
                        detMaxSideLimit = 960,
                        detThresh = 0.3f,
                        detBoxThresh = 0.6f,
                        detUnclipRatio = 1.5f,
                        detMaxCandidates = 3000,
                        detUseDilation = false,
                        detScoreMode = "fast",
                        detBoxType = "quad",
                        recScoreThresh = 0.0f,
                        recBatchSize = 1,
                    ),
                    engineConfig = EngineConfig(
                        numThreads = 4
                    ),
                    detModelAssetPath = "models/det/inference.onnx",
                    recModelAssetPath = "models/rec/inference.onnx",
                    recConfigAssetPath = "models/rec/inference.yml"
                )
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

            // Return pure raw text lines without confidence annotations
            val extractedText = runResult.results.joinToString("\n") { it.text }
                .ifBlank { "No text recognized from document." }

            val avgConfidence = if (runResult.results.isNotEmpty()) {
                runResult.results.map { it.confidence }.average().toFloat()
            } else {
                0.95f
            }

            OcrResult(
                success = true,
                extractedText = extractedText,
                confidence = avgConfidence,
                rawResults = runResult.results,
                detectedFields = mapOf("line_count" to runResult.lineCount.toString())
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
                .ifBlank { "No text recognized from bitmap." }

            val avgConfidence = if (runResult.results.isNotEmpty()) {
                runResult.results.map { it.confidence }.average().toFloat()
            } else {
                0.95f
            }

            OcrResult(
                success = true,
                extractedText = extractedText,
                confidence = avgConfidence,
                rawResults = runResult.results,
                detectedFields = mapOf("line_count" to runResult.lineCount.toString())
            )
        } catch (e: Exception) {
            Log.e("PaddleOcrManager", "PaddleOCR SDK recognition failed", e)
            OcrResult(false, "OCR Error: ${e.localizedMessage ?: "Unknown exception"}", 0f)
        }
    }
}
