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

/*
   OrientationPreprocessor: Runs an isolated ultra-lightweight ONNX model (224x224)
   to detect document rotation and automatically stand the byte stream upright.
 */
class OrientationPreprocessor(context: Context) {

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
            // 1. Decode raw compressed bytes into an in-memory working Bitmap canvas
            val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
            rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return imageBytes // Fallback to original bytes if decoding completely fails

            val currentBitmap = rawBitmap

            // 2. Downscale a separate small copy to match the ONNX model input constraint (224x224)
            scaledBitmap = currentBitmap.scale(224, 224, true)
            val floatBuffer = bitmapToFloatBuffer(scaledBitmap)

            // 3. Fire ONNX Runtime inference pass
            val inputShape = longArrayOf(1, 3, 224, 224)
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)

        // Execute session and auto-close the result block safely to prevent memory leaks
            val outputs: OrtSession.Result = ortSession.run(mapOf("x" to inputTensor))

        // Access the first output node dynamically and cast its value
            val outputTensor = outputs[0] as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val logits = outputTensor.value as Array<FloatArray>

        // Always close individual tensor resources when done reading them
            outputTensor.close()
            outputs.close()

            val predictedClass = logits[0].indices.maxByOrNull { logits[0][it] } ?: 0


            // Map layout classes to correction angles needed to stand upright
            val rotationDegrees = when (predictedClass) {
                1 -> 270f  // Correct 90° rotation
                2 -> 180f  // Correct 180° inversion
                3 -> 90f   // Correct 270° (90° CCW) rotation
                else -> 0f // Already Upright (0°)
            }

            Log.d("OrientationPrep", "Detected layout class: $predictedClass -> Rotating: $rotationDegrees°")

            // Clean up the small tensor thumbnail allocation right away
            scaledBitmap.recycle()

            // 4. If the page is already perfectly upright, return original untouched bytes
            if (rotationDegrees == 0f) {
                currentBitmap.recycle()
                return imageBytes
            }

            // 5. Spin the massive original resolution canvas using a hardware Matrix
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            uprightBitmap = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)

            // 6. Re-compress the upright canvas back into a clean JPEG byte stream for Paddle
            val outputStream = ByteArrayOutputStream()
            uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream) // 95 keeps lines razor sharp
            val processedBytes = outputStream.toByteArray()

            // Clean up large remaining canvases to avoid Out-Of-Memory (OOM) heap overflows
            currentBitmap.recycle()
            uprightBitmap.recycle()

            processedBytes

        } catch (e: Exception) {
            Log.e("OrientationPrep", "Failed to preprocess orientation, skipping layer: ${e.message}", e)

            // Safe cleanup fallback if an error spikes mid-flight
            rawBitmap?.recycle()
            scaledBitmap?.recycle()
            uprightBitmap?.recycle()

            imageBytes
        }
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
}