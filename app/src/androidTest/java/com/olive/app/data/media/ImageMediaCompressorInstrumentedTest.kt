package com.olive.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.MediaCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageMediaCompressorInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeJpegIsBoundedCompressedEncryptedAndCleaned() = runBlocking {
        val bytes=noisyBitmap(2_600,1_600).useBitmap { it.encoded(Bitmap.CompressFormat.JPEG,100) }
        val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
        val before=folder.listFiles().orEmpty().toSet()
        val result=OnDeviceMediaPreprocessor(context).process(
            ByteArrayInputStream(bytes),"image/jpeg",MediaKind.IMAGE)
        val processed=result.use { it.input.readBytes() }
        assertTrue(processed.size<bytes.size)
        assertEquals("image/jpeg",result.mimeType)
        assertEquals("photo.jpg",result.displayName("photo.heic"))
        val decoded=BitmapFactory.decodeByteArray(processed,0,processed.size)
        assertNotNull(decoded)
        assertTrue(maxOf(decoded.width,decoded.height)<=2_048)
        decoded.recycle()
        assertEquals(before,folder.listFiles().orEmpty().toSet())
        assertArrayEquals(processed,encryptedRoundTrip(processed))
    }

    @Test
    fun transparentPngAndSpoofedDeclaredMimeKeepTruthfulMetadata() = runBlocking {
        val png=Bitmap.createBitmap(700,500,Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.eraseColor(Color.argb(96,20,120,220))
            bitmap.encoded(Bitmap.CompressFormat.PNG,100)
        }
        OnDeviceMediaPreprocessor(context).process(
            ByteArrayInputStream(png),"image/jpeg",MediaKind.DOCUMENT).use { result ->
            val output=result.input.readBytes()
            assertEquals("image/png",result.mimeType)
            assertEquals("scan.png",result.displayName("scan.jpg"))
            val decoded=BitmapFactory.decodeByteArray(output,0,output.size)
            assertNotNull(decoded)
            assertTrue(decoded.hasAlpha())
            decoded.recycle()
        }
    }

    @Test
    fun corruptDeclaredImageFailsClosedAndDeletesTemporaryFiles() = runBlocking {
        val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
        val before=folder.listFiles().orEmpty().toSet()
        try {
            OnDeviceMediaPreprocessor(context).process(
                ByteArrayInputStream(ByteArray(8_192) { 0x5a }),"image/jpeg",MediaKind.IMAGE)
            fail("Corrupt image should not pass into encryption")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        assertEquals(before,folder.listFiles().orEmpty().toSet())
    }

    private fun encryptedRoundTrip(bytes:ByteArray):ByteArray {
        val key=EncryptionUtils.generateSymmetricKey()
        val encrypted=ByteArrayOutputStream().also {
            MediaCipher.encrypt(ByteArrayInputStream(bytes),it,key)
        }.toByteArray()
        return ByteArrayOutputStream().also {
            MediaCipher.decrypt(ByteArrayInputStream(encrypted),it,key)
        }.toByteArray()
    }

    private fun noisyBitmap(width:Int,height:Int)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
        val row=IntArray(width)
        for(y in 0 until height) {
            for(x in row.indices) {
                val value=(x*31+y*17+(x*y)%251) and 255
                row[x]=Color.rgb(value,(value*3) and 255,(value*7) and 255)
            }
            setPixels(row,0,width,0,y,width,1)
        }
    }

    private fun Bitmap.encoded(format:Bitmap.CompressFormat,quality:Int)=ByteArrayOutputStream().use {
        check(compress(format,quality,it));it.toByteArray()
    }

    private inline fun <T> Bitmap.useBitmap(block:(Bitmap)->T):T=try { block(this) } finally { recycle() }
}
