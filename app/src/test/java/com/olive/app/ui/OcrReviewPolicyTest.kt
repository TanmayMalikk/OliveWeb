// Tests the retake or save choice, orientation preprocessing, and OCR manager behavior for user scans.
package com.olive.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PointF
import com.olive.app.R
import com.olive.app.ocr.OcrResult
import com.olive.app.ocr.OrientationPreprocessor
import com.olive.app.ocr.PaddleOcrManager
import com.olive.app.ui.users.shared.camera.isLowOcrConfidence
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrReviewPolicyTest {
    @Test
    fun confidenceBelowSeventyFiveAlwaysOffersLowConfidenceChoice() {
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", .749f)))
        assertTrue(isLowOcrConfidence(OcrResult(false, "failed", 0f)))
        assertFalse(isLowOcrConfidence(OcrResult(true, "text", .75f)))
    }

    @Test
    fun nonFiniteAndOutOfRangeConfidenceCannotPassReview() {
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", Float.NaN)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", Float.NEGATIVE_INFINITY)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", -0.1f)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", 1.1f)))
    }

    @Test
    fun orientationPreprocessorDetectsAndRotatesDocumentImages() {
        var predictedClass = 0
        var shouldFail = false
        var closed = false
        val preprocessor = OrientationPreprocessor(
            classifyOrientation = { buffer, shape ->
                assertEquals(4, shape.size)
                assertEquals(3 * 224 * 224, buffer.remaining())
                if (shouldFail) throw IllegalStateException("Inference failed")
                predictedClass
            },
            closeSession = { closed = true },
        )

        val corrupt = byteArrayOf(0, 1, 2)
        assertSame(corrupt, preprocessor.process(corrupt))

        val jpeg = ByteArrayOutputStream().also { out ->
            val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
        }.toByteArray()

        predictedClass = 0
        assertSame(jpeg, preprocessor.process(jpeg))

        for (orientationClass in 1..3) {
            predictedClass = orientationClass
            val rotated = preprocessor.process(jpeg)
            assertTrue(rotated.isNotEmpty())
        }

        val largeJpeg = ByteArrayOutputStream().also { out ->
            val largeBmp = Bitmap.createBitmap(2100, 100, Bitmap.Config.ARGB_8888)
            largeBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            largeBmp.recycle()
        }.toByteArray()
        predictedClass = 0
        val downsampled = preprocessor.process(largeJpeg)
        assertTrue(downsampled.isNotEmpty())

        shouldFail = true
        assertSame(jpeg, preprocessor.process(jpeg))

        preprocessor.close()
        assertTrue(closed)
    }

    @Test
    fun paddleOcrManagerRecognizesTextHandlesEmptyAndReleasesCleanly() = runBlocking<Unit> {
        val rawBaseApp = RuntimeEnvironment.getApplication()
        val resources = spy(rawBaseApp.resources)
        doReturn("No text detected").`when`(resources).getString(anyInt())
        doReturn("OCR error").`when`(resources).getString(anyInt(), any())
        val baseApp = object : ContextWrapper(rawBaseApp) {
            override fun getResources() = resources
            override fun getApplicationContext() = this
        }
        val box = OCRBox(listOf(PointF(0f, 0f), PointF(10f, 0f), PointF(10f, 10f), PointF(0f, 10f)))
        val populated = OCRRunResult(
            results = listOf(OCRResult(box, "Line 1", 0.9f), OCRResult(box, "Line 2", 0.7f)),
            detectionTimeMs = 1L,
            recognitionTimeMs = 1L,
            totalTimeMs = 2L,
            lineCount = 2,
        )
        val empty = OCRRunResult(emptyList(), 1L, 1L, 2L, 0)
        val rawBytes = byteArrayOf(1, 2, 3)

        var straightenerClosed = false
        val ocr = mock(PaddleOCR::class.java)
        `when`(ocr.recognize(rawBytes))
            .thenReturn(populated, empty)
            .thenThrow(IllegalStateException("Model failure"))

        val failingManager = PaddleOcrManager(
            context = baseApp,
            initOpenCv = {},
            straightenerFactory = {
                OrientationPreprocessor(
                    classifyOrientation = { _, _ -> 0 },
                    closeSession = { straightenerClosed = true },
                )
            },
            ocrFactory = { throw IllegalStateException("Init error") },
        )
        val initFailure = failingManager.recognizeDocument(rawBytes)
        assertFalse(initFailure.success)
        assertEquals("OCR error", initFailure.extractedText)
        assertTrue(straightenerClosed)

        val manager = PaddleOcrManager(
            context = baseApp,
            initOpenCv = {},
            straightenerFactory = { OrientationPreprocessor({ _, _ -> 0 }) },
            ocrFactory = { ocr },
        )

        val first = manager.recognizeDocument(rawBytes)
        assertTrue(first.success)
        assertEquals("Line 1\nLine 2", first.extractedText)
        assertEquals(0.8f, first.confidence, 0.001f)
        assertEquals("2", first.detectedFields["line_count"])

        val second = manager.recognizeDocumentWithPreview(rawBytes)
        assertTrue(second.second.success)
        assertEquals(baseApp.getString(R.string.ocr_no_text), second.second.extractedText)
        assertEquals(0f, second.second.confidence)

        val third = manager.recognizeDocument(rawBytes)
        assertFalse(third.success)
        assertEquals("OCR error", third.extractedText)

        manager.release()
        manager.release()
        verify(ocr, times(1)).release()

        val afterRelease = manager.recognizeDocument(rawBytes)
        assertFalse(afterRelease.success)
        assertEquals("OCR error", afterRelease.extractedText)
    }
}
