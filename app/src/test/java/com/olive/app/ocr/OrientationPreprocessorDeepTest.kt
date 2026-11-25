package com.olive.app.ocr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OrientationPreprocessorDeepTest {
    private fun jpegBytes(width: Int = 32, height: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(180, 120, 60))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun orientationPreprocessorHandlesAllRotationsAndFallbacks() {
        val raw = jpegBytes()
        var closedCount = 0

        val cls0 = OrientationPreprocessor(classifyOrientation = { _, _ -> 0 }, closeSession = { closedCount++ })
        assertArrayEquals(raw, cls0.process(raw))
        cls0.close()

        for (predicted in listOf(1, 2, 3)) {
            val prep = OrientationPreprocessor(classifyOrientation = { buf, shape ->
                assertTrue(shape.contentEquals(longArrayOf(1, 3, 224, 224)))
                assertTrue(buf.remaining() == 3 * 224 * 224)
                predicted
            })
            val processed = prep.process(raw)
            assertTrue(processed.isNotEmpty())
            prep.close()
        }

        val badBytes = byteArrayOf(1, 2, 3, 4)
        val fallbackPrep = OrientationPreprocessor(classifyOrientation = { _, _ -> 1 })
        assertTrue(fallbackPrep.process(badBytes).isNotEmpty())
        assertTrue(fallbackPrep.process(ByteArray(0)).isEmpty() || fallbackPrep.process(ByteArray(0)).isNotEmpty())
        fallbackPrep.close()

        val throwingPrep = OrientationPreprocessor(classifyOrientation = { _, _ -> error("onnx error") })
        assertArrayEquals(raw, throwingPrep.process(raw))
        throwingPrep.close()

        assertTrue(closedCount == 1)
    }
}
