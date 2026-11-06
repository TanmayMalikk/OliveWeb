// Users scanning documents: straightens camera images before OCR.
package com.olive.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.graphics.scale
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
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


/*
   OrientationPreprocessor: Runs an isolated ultra-lightweight ONNX model (224x224)
   to detect document rotation and automatically stand the byte stream upright.
 */
class OrientationPreprocessor(context: Context) : AutoCloseable {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        // Load the 6.78MB doc_orientation.onnx file directly out of assets
        val modelBytes = context.assets.open("models/doc_ori_infer.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    /**
     * Inspects document orientation, rotates pixels if needed,
     * and returns clean, right-side-up image bytes.
     */
    fun process(imageBytes: ByteArray): ByteArray {
        var rawBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var uprightBitmap: Bitmap? = null

        return try {
            // Bound the working bitmap before decoding it. Camera-native images can otherwise require
            // hundreds of MB while the original and rotated canvases coexist.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            var sample = 1
            while (maxOf(
                    bounds.outWidth,
                    bounds.outHeight
                ) / sample > MAX_WORKING_DIMENSION
            ) sample *= 2
            val decodeOptions =
                BitmapFactory.Options().apply {
                    inMutable = true
                    inSampleSize = sample
                }
            rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return imageBytes // Fallback to original bytes if decoding completely fails

            val currentBitmap = rawBitmap

            // 2. Downscale a separate small copy to match the ONNX model input constraint (224x224)
            scaledBitmap = currentBitmap.scale(224, 224, true)
            val floatBuffer = bitmapToFloatBuffer(scaledBitmap)

            // 3. Fire ONNX Runtime inference pass
            val inputShape = longArrayOf(1, 3, 224, 224)
            val predictedClass =
                OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape).use { inputTensor ->
                    ortSession.run(mapOf("x" to inputTensor)).use { outputs ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (outputs[0] as OnnxTensor).value as Array<FloatArray>
                        logits[0].indices.maxByOrNull { logits[0][it] } ?: 0
                    }
                }


            // Map layout classes to correction angles needed to stand upright
            val rotationDegrees = when (predictedClass) {
                1 -> 270f  // Correct 90° rotation
                2 -> 180f  // Correct 180° inversion
                3 -> 90f   // Correct 270° (90° CCW) rotation
                else -> 0f // Already Upright (0°)
            }

            Log.d(
                "OrientationPrep",
                "Detected layout class: $predictedClass -> Rotating: $rotationDegrees°"
            )

            // Preserve untouched bytes only when they already fit the OCR memory bound.
            if (rotationDegrees == 0f && sample == 1) return imageBytes
            uprightBitmap = if (rotationDegrees == 0f) currentBitmap else {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(
                    currentBitmap,
                    0,
                    0,
                    currentBitmap.width,
                    currentBitmap.height,
                    matrix,
                    true
                )
            }

            // 6. Re-compress the upright canvas back into a clean JPEG byte stream for Paddle
            val outputStream = ByteArrayOutputStream()
            uprightBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                95,
                outputStream
            ) // 95 keeps lines razor sharp
            val processedBytes = outputStream.toByteArray()

            processedBytes

        } catch (e: Exception) {
            Log.e(
                "OrientationPrep",
                "Failed to preprocess orientation, skipping layer: ${e.message}",
                e
            )

            imageBytes
        } finally {
            listOfNotNull(rawBitmap, scaledBitmap, uprightBitmap).distinct()
                .forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    // Closes the active feature for orientation preprocessor.
    override fun close() {
        ortSession.close()
    }

    /**
     * Packs standard 32-bit pixel integers into normalized floating point math streams
     * utilizing planar CHW ordering required by Paddle architecture.
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Allocate direct buffer capacity for floating calculations (3 channels * 224 * 224)
        val buffer = FloatBuffer.allocate(3 * width * height)
        for (channel in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val byteValue = when (channel) {
                        0 -> (pixel shr 16 and 0xFF) // Red Channel
                        1 -> (pixel shr 8 and 0xFF)  // Green Channel
                        2 -> (pixel and 0xFF)         // Blue Channel
                        else -> 0
                    }
                    // Standard Paddle normalization range adjustments [-1.0 .. 1.0]
                    buffer.put((byteValue / 255.0f - 0.5f) / 0.5f)
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val MAX_WORKING_DIMENSION = 2048
    }
}

// Users scanning documents: reads text from scanned document images.

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
