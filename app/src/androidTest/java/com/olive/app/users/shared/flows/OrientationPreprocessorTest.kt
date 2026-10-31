// Tests rotation correction before OCR reads user documents.
package com.olive.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class OrientationPreprocessorTest {

    private lateinit var context: Context
    private lateinit var preprocessor: OrientationPreprocessor
    private lateinit var testDocBytes: ByteArray

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        preprocessor = OrientationPreprocessor(context)

        // The fixture belongs to the test APK and is not shipped to users.
        testDocBytes = instrumentation.context.assets.open("test_doc.png").readBytes()
        assertTrue("test_doc.png should not be empty", testDocBytes.isNotEmpty())
    }

    @After
    fun tearDown() {
        preprocessor.close()
    }

    @Test
    fun testProcessUprightDocument() {
        val processedBytes = preprocessor.process(testDocBytes)
        assertNotNull(processedBytes)
        assertTrue(processedBytes.isNotEmpty())

        val resultBitmap = BitmapFactory.decodeByteArray(processedBytes, 0, processedBytes.size)
        assertNotNull("Result should be a valid bitmap", resultBitmap)
        resultBitmap.recycle()
    }

    @Test
    fun testProcessRotatedDocuments() {
        val originalBitmap = BitmapFactory.decodeByteArray(testDocBytes, 0, testDocBytes.size)
        assertNotNull(originalBitmap)
        val uprightReferenceBytes = preprocessor.process(testDocBytes)
        val uprightReference =
            BitmapFactory.decodeByteArray(uprightReferenceBytes, 0, uprightReferenceBytes.size)
        assertNotNull(uprightReference)

        val rotations = listOf(0f, 90f, 180f, 270f)
        for (rotation in rotations) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0,
                originalBitmap.width, originalBitmap.height,
                matrix, true,
            )

            val outputStream = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val rotatedBytes = outputStream.toByteArray()
            // Bitmap.createBitmap may return its input unchanged for a zero-degree transform.
            if (rotatedBitmap !== originalBitmap) rotatedBitmap.recycle()

            val correctedBytes = preprocessor.process(rotatedBytes)
            assertNotNull(correctedBytes)
            assertTrue(correctedBytes.isNotEmpty())

            val correctedBitmap =
                BitmapFactory.decodeByteArray(correctedBytes, 0, correctedBytes.size)
            assertNotNull(
                "Corrected bitmap for rotation $rotation should be valid",
                correctedBitmap
            )
            // Camera images are intentionally subsampled above the OCR working-size bound.
            // Every rotated capture must normalize to the model's upright reference geometry.
            assertEquals(
                "Corrected width for rotation $rotation",
                uprightReference.width,
                correctedBitmap.width
            )
            assertEquals(
                "Corrected height for rotation $rotation",
                uprightReference.height,
                correctedBitmap.height
            )
            assertTrue(
                "Corrected document for rotation $rotation should respect the memory bound",
                maxOf(correctedBitmap.width, correctedBitmap.height) <= 2048
            )
            val sourceRatio = uprightReference.width.toDouble() / uprightReference.height
            val correctedRatio = correctedBitmap.width.toDouble() / correctedBitmap.height
            assertEquals(
                "Corrected aspect ratio for rotation $rotation",
                sourceRatio,
                correctedRatio,
                0.03
            )
            assertTrue(
                "Corrected pixels for rotation $rotation should match the upright source",
                meanPixelDifference(uprightReference, correctedBitmap) < 35.0
            )
            correctedBitmap.recycle()
        }
        uprightReference.recycle()
        originalBitmap.recycle()
    }

    private fun meanPixelDifference(first: Bitmap, second: Bitmap): Double {
        val width = 64
        val height = 64
        val a = Bitmap.createScaledBitmap(first, width, height, true)
        val b = Bitmap.createScaledBitmap(second, width, height, true)
        val ap = IntArray(width * height)
        val bp = IntArray(width * height)
        a.getPixels(ap, 0, width, 0, 0, width, height)
        b.getPixels(
            bp,
            0,
            width,
            0,
            0,
            width,
            height
        )
        a.recycle()
        b.recycle()
        return ap.indices.sumOf { index ->
            val x = ap[index]
            val y = bp[index]
            (kotlin.math.abs((x shr 16 and 255) - (y shr 16 and 255)) +
                    kotlin.math.abs((x shr 8 and 255) - (y shr 8 and 255)) +
                    kotlin.math.abs((x and 255) - (y and 255))).toDouble() / 3
        } / ap.size
    }
}
