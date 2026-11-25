package com.olive.app.data.media

import android.app.Application
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.olive.app.R
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.TestAndroidKeyStore
import com.olive.app.ui.users.user.documents.DocumentPreviewCache
import com.olive.app.ui.users.user.documents.decryptCiphertextForPreview
import com.olive.app.ui.users.user.documents.preparationFailureMessage
import com.olive.app.ui.users.user.documents.preparationFailureResource
import com.olive.app.ui.users.user.documents.renderBlurredDocumentPreview
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaPreprocessorAndPreviewDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun tempBitmapFile(format: Bitmap.CompressFormat, ext: String): File =
        File.createTempFile("media-test", ext).apply {
            val bmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
            FileOutputStream(this).use { out -> bmp.compress(format, 90, out) }
        }

    @Test
    fun imageCompressionPolicyAndPreprocessorHandleImagesExifAndEdgeCases() = runBlocking {
        assertEquals("image/jpeg", ImageCompressionPolicy.normalizedMimeType("image/jpg; charset=utf-8"))
        assertEquals("image/png", ImageCompressionPolicy.normalizedMimeType("image/x-png"))
        assertTrue(ImageCompressionPolicy.isSupportedStillMime("image/png"))
        assertFalse(ImageCompressionPolicy.isSupportedStillMime("application/pdf"))

        val pngFile = tempBitmapFile(Bitmap.CompressFormat.PNG, ".png")
        assertEquals("image/png", ImageCompressionPolicy.detectMimeType(pngFile))

        val jpgFile = tempBitmapFile(Bitmap.CompressFormat.JPEG, ".jpg")
        assertEquals("image/jpeg", ImageCompressionPolicy.detectMimeType(jpgFile))

        val webpHeader = File.createTempFile("anim", ".webp").apply {
            writeBytes(
                "RIFF".toByteArray() + byteArrayOf(24, 0, 0, 0) + "WEBP".toByteArray() +
                    "VP8X".toByteArray() + byteArrayOf(4, 0, 0, 0, 0x02, 0, 0, 0),
            )
        }
        assertEquals("image/webp", ImageCompressionPolicy.detectMimeType(webpHeader))

        val heicHeader = File.createTempFile("photo", ".heic").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(8))
        }
        assertEquals("image/heic", ImageCompressionPolicy.detectMimeType(heicHeader))

        val heifHeader = File.createTempFile("photo", ".heif").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypmif1".toByteArray() + ByteArray(8))
        }
        assertEquals("image/heif", ImageCompressionPolicy.detectMimeType(heifHeader))

        val compressor = ImageMediaCompressor()
        val outDir = File(context.cacheDir, "img-comp-out").apply { mkdirs() }
        listOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        ).forEach { orientation ->
            ExifInterface(jpgFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val result = compressor.compress(jpgFile, outDir, "image/jpeg")
            assertEquals("image/jpeg", result.mimeType)
        }

        val preprocessor = OnDeviceMediaPreprocessor(context)
        val processedPng = preprocessor.process(pngFile.inputStream(), "image/png", MediaKind.IMAGE)
        processedPng.use {
            assertEquals("photo.png", it.displayName("photo.heic"))
        }

        val passThrough = PassThroughMediaPreprocessor.process(
            ByteArrayInputStream("plain".toByteArray()),
            "text/plain",
            MediaKind.DOCUMENT,
        )
        passThrough.use {
            assertEquals("notes.txt", it.displayName("notes.txt"))
        }

        var rejected = false
        try {
            compressor.compress(jpgFile, outDir, "application/pdf")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun documentPreviewCacheAndFailureHelpersCoverBranches() = runBlocking {
        TestAndroidKeyStore.install().use {
        assertEquals(R.string.documents_video_memory_failed, preparationFailureResource(OutOfMemoryError(), MediaKind.VIDEO))
        assertEquals(R.string.documents_media_memory_failed, preparationFailureResource(MediaPreparationMemoryException(), MediaKind.IMAGE))
        assertNull(preparationFailureResource(IllegalStateException("io"), MediaKind.DOCUMENT))
        assertTrue(preparationFailureMessage(context, IllegalStateException("Disk full"), MediaKind.IMAGE).contains("Disk full"))

        val emptyFile = File.createTempFile("empty", ".jpg")
        assertNull(renderBlurredDocumentPreview(emptyFile, "image/jpeg", "empty.jpg").bitmap)

        val badPdf = File.createTempFile("bad", ".pdf").apply { writeText("not a pdf") }
        assertNull(renderBlurredDocumentPreview(badPdf, "application/pdf", "bad.pdf").bitmap)

        val fakeAudio = File.createTempFile("aud", ".mp3").apply { writeText("mp3") }
        assertEquals(MediaKind.AUDIO, renderBlurredDocumentPreview(fakeAudio, "audio/mpeg", "a.mp3").mediaKind)

        val garbageCipher = File.createTempFile("enc", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val doc = SafetyDocument("d-1", "owner", "p", "doc.png", "image/png", listOf("owner"), 1000L)
        assertNull(decryptCiphertextForPreview(garbageCipher, doc, "owner", context.cacheDir))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = DocumentPreviewCache(scope)
        val img = tempBitmapFile(Bitmap.CompressFormat.PNG, ".png")
        cache.request("owner", doc) { img }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (cache.preview("owner", "d-1") == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(cache.isLoading("owner", "missing"))
        assertNotNull(cache.preview("owner", "d-1"))
        cache.clear()
        assertNull(cache.preview("owner", "d-1"))
        scope.cancel()
        }
    }
}
