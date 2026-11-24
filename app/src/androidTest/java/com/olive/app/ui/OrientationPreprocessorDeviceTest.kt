package com.olive.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.ocr.OrientationPreprocessor
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrientationPreprocessorDeviceTest {
    @Test
    fun malformedInputIsPreservedAndLargeDocumentsAreBoundedBeforeOcr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        val image = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                repeat(12) { row -> drawText("Synthetic document line ${row + 1}", 120f, 120f + row * 67, ink) }
            }
            ByteArrayOutputStream().use { bytes ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }

        OrientationPreprocessor(context).use { preprocessor ->
            val invalid = byteArrayOf(0, 1, 2)
            assertSame(invalid, preprocessor.process(invalid))
            val output = preprocessor.process(image)
            val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
            assertNotNull(decoded)
            try {
                assertTrue(maxOf(decoded.width, decoded.height) in 1..2048)
                assertTrue(minOf(decoded.width, decoded.height) >= 600)
            } finally {
                decoded.recycle()
            }
        }
    }
}
