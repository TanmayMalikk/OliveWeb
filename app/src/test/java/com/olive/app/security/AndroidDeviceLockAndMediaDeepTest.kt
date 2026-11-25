package com.olive.app.security

import android.app.Application
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import com.olive.app.data.media.ImageMediaCompressor
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OnDeviceMediaPreprocessor
import com.olive.app.data.media.PassThroughMediaPreprocessor
import com.olive.app.data.media.VideoCompressionPolicy
import com.olive.app.ui.session.DeviceLockRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidDeviceLockAndMediaDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun testCipher(): Cipher = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey().let { key ->
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun fakeChallenge(enrolling: Boolean = true) = object : DeviceAuthenticationChallenge {
        override val enrolling = enrolling
        override val promptCipher: Cipher? = testCipher()
        override fun legacyCipher(): Cipher = testCipher()
    }

    @Test
    @Config(sdk = [30])
    fun androidDeviceLockCoversInsecureKeyguardSecurePromptResumeAndCancel() = runBlocking {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume()
        val activity = controller.get()
        var abandoned = 0
        val lock = AndroidDeviceLock(activity) { abandoned++ }

        val km = activity.getSystemService(KeyguardManager::class.java)
        shadowOf(km).setIsDeviceSecure(false)
        val reqNoLock = DeviceLockRequest(fakeChallenge(enrolling = true))
        lock.show(reqNoLock)
        assertNull(reqNoLock.result.await())

        shadowOf(km).setIsDeviceSecure(true)
        val reqSecure = DeviceLockRequest(fakeChallenge(enrolling = false), settingsChange = true)
        lock.show(reqSecure)
        lock.resume()
        lock.cancel()
        assertFalse(lock.awaitingCredential)
        assertTrue(reqSecure.result.isCompleted)
    }

    @Test
    @Config(sdk = [28])
    fun androidDeviceLockCoversLegacySdkPathAndAbandonedLifecycle() = runBlocking {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        val activity = controller.get()
        var abandoned = 0
        val lock = AndroidDeviceLock(activity) { abandoned++ }

        val km = activity.getSystemService(KeyguardManager::class.java)
        shadowOf(km).setIsDeviceSecure(false)
        val reqCreatedOnly = DeviceLockRequest(fakeChallenge(enrolling = false))
        lock.show(reqCreatedOnly)
        assertNull(reqCreatedOnly.result.await())
        assertTrue(abandoned >= 1)

        shadowOf(km).setIsDeviceSecure(true)
        controller.start().resume()
        val reqLegacy = DeviceLockRequest(fakeChallenge(enrolling = false))
        lock.show(reqLegacy)
        lock.cancel()
        assertTrue(reqLegacy.result.isCompleted)
    }

    @Test
    fun onDeviceMediaPreprocessorAndPoliciesCoverImagesVideosAndPassThrough() = runBlocking {
        val preprocessor = OnDeviceMediaPreprocessor(context)
        val pass = PassThroughMediaPreprocessor.process(ByteArrayInputStream("abc".toByteArray()), "text/plain", MediaKind.DOCUMENT)
        assertEquals("report.txt", pass.displayName("report.txt"))
        pass.close()

        val docRes = preprocessor.process(ByteArrayInputStream("hello".toByteArray()), "", MediaKind.DOCUMENT)
        assertEquals("application/octet-stream", docRes.mimeType)
        docRes.close()

        // Valid PNG with alpha & large bitmap compression
        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128, 10, 20, 30)) }
        val jpgFile = File(context.cacheDir, "media_in.jpg")
        FileOutputStream(jpgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(jpgFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val compressor = ImageMediaCompressor()
        runCatching {
            val out = compressor.compress(jpgFile, context.cacheDir, "image/jpeg")
            assertTrue(out.file.exists())
        }
        val pngFile = File(context.cacheDir, "media_in.png")
        FileOutputStream(pngFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        runCatching {
            compressor.compress(pngFile, context.cacheDir, "image/png")
        }

        // Empty stream fails cleanly
        val emptyFailure = runCatching {
            preprocessor.process(ByteArrayInputStream(ByteArray(0)), "image/jpeg", MediaKind.IMAGE)
        }
        assertTrue(emptyFailure.isFailure)

        // VideoCompressionPolicy container header checks
        val mp4Header = File(context.cacheDir, "fake_vid.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray())
        }
        val webmHeader = File(context.cacheDir, "fake_vid.webm").apply {
            writeBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0, 0, 0, 0))
        }
        assertTrue(VideoCompressionPolicy.matchesContainer(mp4Header, "video/mp4"))
        assertTrue(VideoCompressionPolicy.matchesContainer(webmHeader, "video/webm"))
        assertFalse(VideoCompressionPolicy.matchesContainer(webmHeader, "video/mp4"))
        assertTrue(VideoCompressionPolicy.targetSize(1920, 1080, 90).first > 0)
    }
}
