package com.olive.app.ui.users.user.documents

import android.graphics.Bitmap
import android.graphics.Color
import com.olive.app.data.media.MediaKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentPreviewLoaderTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun blurBitmapPreservesDimensionsAndSoftensSharpEdges() {
        val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 10 until 30) {
                for (x in 10 until 30) {
                    setPixel(x, y, Color.BLACK)
                }
            }
        }
        val blurred = blurBitmap(source, radius = 6)
        assertEquals(40, blurred.width)
        assertEquals(40, blurred.height)
        val edgePixel = blurred.getPixel(9, 20)
        // Edge just outside the sharp black square is softened to gray by the blur passes.
        assertTrue(Color.red(edgePixel) in 20..240)
        assertEquals(Color.red(edgePixel), Color.green(edgePixel))
    }

    @Test
    fun blurBitmapHandlesTinySinglePixelBitmaps() {
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val blurred = blurBitmap(tiny, radius = 6)
        assertEquals(1, blurred.width)
        assertEquals(1, blurred.height)
    }

    @Test
    fun renderBlurredDocumentPreviewSupportsTextFilesAndSkipsAudio() {
        val textFile = tempFolder.newFile("note.txt").apply {
            writeText("Important appointment notes for Tuesday morning.")
        }
        val textPreview = renderBlurredDocumentPreview(textFile, "text/plain", "note.txt")
        assertEquals(MediaKind.DOCUMENT, textPreview.mediaKind)
        assertNotNull(textPreview.bitmap)

        val audioFile = File(tempFolder.root, "voice.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val audioPreview = renderBlurredDocumentPreview(audioFile, "audio/mp4", "voice.m4a")
        assertEquals(MediaKind.AUDIO, audioPreview.mediaKind)
        assertNull(audioPreview.bitmap)
    }
}
