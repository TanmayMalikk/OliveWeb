package com.olive.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImageMediaCompressorTest {
    private val context:Context=RuntimeEnvironment.getApplication()

    @Test
    fun largeJpegCompressesThenSurvivesAuthenticatedEncryption() = runBlocking {
        val source=noisyBitmap(2_200,1_100).useBitmap { it.encoded(Bitmap.CompressFormat.JPEG,100) }
        val processed=OnDeviceMediaPreprocessor(context).process(
            ByteArrayInputStream(source),"image/jpeg",MediaKind.IMAGE).use { result ->
            assertEquals("image/jpeg",result.mimeType)
            assertEquals("photo.jpg",result.displayName("photo.heic"))
            result.input.readBytes()
        }
        assertTrue(processed.size<source.size)
        val bitmap=BitmapFactory.decodeByteArray(processed,0,processed.size)
        assertNotNull(bitmap)
        assertTrue(maxOf(bitmap.width,bitmap.height)<=2_048)
        bitmap.recycle()

        val key=EncryptionUtils.generateSymmetricKey()
        val encrypted=ByteArrayOutputStream().also {
            MediaCipher.encrypt(ByteArrayInputStream(processed),it,key)
        }.toByteArray()
        val decrypted=ByteArrayOutputStream().also {
            MediaCipher.decrypt(ByteArrayInputStream(encrypted),it,key)
        }.toByteArray()
        assertArrayEquals(processed,decrypted)
    }

    @Test
    fun transparentPngKeepsAlphaAndTruthfulOutputFormat() = runBlocking {
        val source=Bitmap.createBitmap(500,350,Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            bitmap.eraseColor(Color.argb(90,40,100,210))
            bitmap.encoded(Bitmap.CompressFormat.PNG,100)
        }
        OnDeviceMediaPreprocessor(context).process(
            ByteArrayInputStream(source),"image/png",MediaKind.DOCUMENT).use { result ->
            assertEquals("image/png",result.mimeType)
            val output=result.input.readBytes()
            assertArrayEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47),output.copyOfRange(0,4))
            val decoded=BitmapFactory.decodeByteArray(output,0,output.size)
            assertNotNull(decoded)
            decoded.recycle()
        }
    }

    @Test
    fun corruptAndSpoofedImagesFailWithoutPlaintextRemnants() = runBlocking {
        val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
        val baseline=folder.listFiles().orEmpty().toSet()
        val failures=listOf(
            ByteArray(2_048) { 0x42 },
            "GIF89a".encodeToByteArray()+ByteArray(2_048),
        )
        failures.forEach { bytes ->
            try {
                OnDeviceMediaPreprocessor(context).process(
                    ByteArrayInputStream(bytes),"image/jpeg",MediaKind.IMAGE)
                fail("Invalid still image must fail")
            } catch(_:IllegalArgumentException) { }
            assertEquals(baseline,folder.listFiles().orEmpty().toSet())
        }
    }

    @Test
    fun nonCompressibleClassesRemainByteExactAcrossRepeatedInputs() = runBlocking {
        val cases=listOf(
            "application/pdf" to MediaKind.DOCUMENT,
            "video/x-msvideo" to MediaKind.VIDEO,
            "audio/ogg" to MediaKind.AUDIO,
            "application/octet-stream" to MediaKind.OTHER,
        )
        repeat(50) { repetition -> cases.forEachIndexed { index,(mime,kind) ->
            val bytes=ByteArray(512+repetition+index) { ((it+repetition)%251).toByte() }
            OnDeviceMediaPreprocessor(context).process(ByteArrayInputStream(bytes),mime,kind).use {
                assertArrayEquals(bytes,it.input.readBytes())
            }
        } }
    }

    @Test
    fun eligiblePreprocessingPurgesStalePlaintextRemnants() = runBlocking {
        val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
        val stale=File(folder,"olive-image-stale.tmp").apply {
            writeBytes(byteArrayOf(1,2,3))
            setLastModified(System.currentTimeMillis()-48L*60L*60L*1000L)
        }
        val source=noisyBitmap(80,60).useBitmap { it.encoded(Bitmap.CompressFormat.JPEG,80) }
        OnDeviceMediaPreprocessor(context).process(
            ByteArrayInputStream(source),"image/jpeg",MediaKind.IMAGE).use { it.input.readBytes() }
        assertTrue(!stale.exists())
    }

    private fun noisyBitmap(width:Int,height:Int)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
        val row=IntArray(width)
        for(y in 0 until height) {
            for(x in row.indices) {
                val value=(x*29+y*43+(x*y)%239) and 255
                row[x]=Color.rgb(value,(value*5) and 255,(value*11) and 255)
            }
            setPixels(row,0,width,0,y,width,1)
        }
    }

    private fun Bitmap.encoded(format:Bitmap.CompressFormat,quality:Int)=ByteArrayOutputStream().use {
        check(compress(format,quality,it));it.toByteArray()
    }

    private inline fun <T> Bitmap.useBitmap(block:(Bitmap)->T):T=try { block(this) } finally { recycle() }
}
