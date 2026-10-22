package com.olive.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
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
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preprocessor = OrientationPreprocessor(context)
        
        // Load test_doc.png from assets
        testDocBytes = context.assets.open("test_doc.png").readBytes()
        assertTrue("test_doc.png should not be empty", testDocBytes.isNotEmpty())
    }

    @Test
    fun testProcessUprightDocument() {
        // Process the original test_doc.png
        val processedBytes = preprocessor.process(testDocBytes)
        assertNotNull(processedBytes)
        assertTrue(processedBytes.isNotEmpty())

        // Verify output can be decoded back into a valid Bitmap
        val resultBitmap = BitmapFactory.decodeByteArray(processedBytes, 0, processedBytes.size)
        assertNotNull("Result should be a valid bitmap", resultBitmap)
        resultBitmap.recycle()
    }

    @Test
    fun testProcessRotatedDocuments() {
        val originalBitmap = BitmapFactory.decodeByteArray(testDocBytes, 0, testDocBytes.size)
        assertNotNull(originalBitmap)

        val rotations = listOf(0f, 90f, 180f, 270f)
        for (rotation in rotations) {
            // Rotate the original bitmap artificially
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, 
                originalBitmap.width, originalBitmap.height, 
                matrix, true,
            )

            val outputStream = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val rotatedBytes = outputStream.toByteArray()
            rotatedBitmap.recycle()

            // Process through OrientationPreprocessor
            val correctedBytes = preprocessor.process(rotatedBytes)
            assertNotNull(correctedBytes)
            assertTrue(correctedBytes.isNotEmpty())

            // Verify corrected output is a valid decodable image
            val correctedBitmap = BitmapFactory.decodeByteArray(correctedBytes, 0, correctedBytes.size)
            assertNotNull("Corrected bitmap for rotation $rotation should be valid", correctedBitmap)
            assertTrue(correctedBitmap.width > 0)
            assertTrue(correctedBitmap.height > 0)
            correctedBitmap.recycle()
        }
        originalBitmap.recycle()
    }
}
