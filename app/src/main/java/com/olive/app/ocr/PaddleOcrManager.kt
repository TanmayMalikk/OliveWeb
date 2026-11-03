// Users scanning documents: reads text from scanned document images.
package com.olive.app.ocr

import android.content.Context
import android.util.Log
import com.olive.app.R
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var straightener: OrientationPreprocessor? = null
    private val mutex = Mutex()
    private var released = false

    // Starts the OCR engine once and reuses that initialized instance for later scans.
    private suspend fun ensureInitialized(): PaddleOCR {
        check(!released) { "OCR engine has been released" }
        // Create the OCR engine only once, the first time a document needs it.
        if (paddleOcr == null) {
            // Turn an unexpected ensure initialized failure into a readable message.
            try {
                OpenCVUtils.init(context.applicationContext)
                straightener = OrientationPreprocessor(context.applicationContext)
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
                runCatching { straightener?.close() }
                straightener = null
                Log.e("PaddleOcrManager", "Failed to initialize PaddleOCR instance", e)
                throw e
            }
        }
        return paddleOcr!!
    }

    // Paddle ocr manager: Reads text from the selected document image. It prepares the picture before the app reads or saves it.
    suspend fun recognizeDocument(imageBytes: ByteArray): OcrResult =
        recognizeDocumentWithPreview(imageBytes).second

    // Paddle ocr manager: Reads text and builds a preview for the selected document image. It prepares the picture before the app reads or saves it.
    suspend fun recognizeDocumentWithPreview(imageBytes: ByteArray): Pair<ByteArray, OcrResult> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Turn an unexpected recognize document with preview failure into a readable message.
                try {
                    val ocr = ensureInitialized()
                    val uprightBytes = checkNotNull(straightener).process(imageBytes)
                    val runResult = ocr.recognize(uprightBytes)

                    // Return pure raw text lines without confidence annotations
                    val extractedText = runResult.results.joinToString("\n") { it.text }
                        .ifBlank { context.getString(R.string.ocr_no_text) }

                    val avgConfidence = if (runResult.results.isNotEmpty()) {
                        runResult.results.map { it.confidence }.average().toFloat()
                    } else {
                        0f
                    }

                    uprightBytes to OcrResult(
                        success = true,
                        extractedText = extractedText,
                        confidence = avgConfidence,
                        rawResults = runResult.results,
                        detectedFields = mapOf("line_count" to runResult.lineCount.toString())
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.e("PaddleOcrManager", "PaddleOCR SDK recognition failed", e)
                    imageBytes to OcrResult(
                        false,
                        context.getString(
                            R.string.ocr_error_detail,
                            e.localizedMessage ?: e.javaClass.simpleName
                        ),
                        0f
                    )
                }
            }
        }

    // Releases the resources used by the feature for paddle ocr manager. It prepares the picture before the app reads or saves it.
    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (released) return@withLock
            released = true
            runCatching { paddleOcr?.release() }
            paddleOcr = null
            runCatching { straightener?.close() }
            straightener = null
        }
    }
}
