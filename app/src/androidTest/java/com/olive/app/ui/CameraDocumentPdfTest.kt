package com.olive.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.ui.users.shared.camera.convertDocumentPhotoToPdf
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameraDocumentPdfTest {
    @Test
    fun conversionHonorsCameraExifOrientationsAndProducesAnUprightSinglePagePdf() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
        )
        val photo = File.createTempFile("orientation-test-", ".jpg", cache)
        val output = File.createTempFile("orientation-test-", ".pdf", cache)
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            for (orientation in orientations) {
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ExifInterface(photo).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                assertEquals(output, convertDocumentPhotoToPdf(photo, output))
                PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    assertEquals(1, pdf.pageCount)
                    pdf.openPage(0).use { page ->
                        assertEquals(orientation >= ExifInterface.ORIENTATION_TRANSPOSE, page.height > page.width)
                    }
                }
            }
        } finally {
            bitmap.recycle()
            photo.delete()
            output.delete()
        }
    }

    @Test
    fun corruptCameraPhotoIsRejectedWithoutCreatingPdf() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val photo = File.createTempFile("corrupt-camera-", ".jpg", cache).apply { writeText("invalid image bytes") }
        val output = File(photo.parentFile, photo.nameWithoutExtension + ".pdf")
        try {
            val failure = runCatching { convertDocumentPhotoToPdf(photo, output) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(output.exists())
        } finally {
            photo.delete()
            output.delete()
        }
    }
}
