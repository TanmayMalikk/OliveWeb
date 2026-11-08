package com.olive.app.data.media

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaPreprocessorTest {
    @Test
    fun eligibleMimeTypesCoverStillImageInputsOnly() {
        listOf("image/jpeg","image/png","image/webp","image/heic","image/heif").forEach { mime ->
            assertTrue(ImageCompressionPolicy.isEligible(mime,MediaKind.IMAGE))
            assertTrue(ImageCompressionPolicy.isEligible("  ${mime.uppercase()}; charset=binary",MediaKind.DOCUMENT))
        }
        listOf("image/gif","image/svg+xml","application/pdf","video/mp4","audio/mpeg",
            "application/octet-stream").forEach { mime ->
            assertFalse(ImageCompressionPolicy.isEligible(mime,MediaKind.IMAGE))
        }
        assertFalse(ImageCompressionPolicy.isEligible("image/jpeg",MediaKind.VIDEO))
        assertTrue(ImageCompressionPolicy.isEligible("image/jpg",MediaKind.IMAGE))
        assertTrue(ImageCompressionPolicy.isEligible("image/x-png",MediaKind.DOCUMENT))
    }

    @Test
    fun targetSizePreservesAspectRatioAcrossWideInputClasses() {
        assertEquals(2_048 to 1_024,ImageCompressionPolicy.targetSize(4_000,2_000))
        assertEquals(1_024 to 2_048,ImageCompressionPolicy.targetSize(2_000,4_000))
        assertEquals(2_048 to 2_048,ImageCompressionPolicy.targetSize(8_000,8_000))
        assertEquals(640 to 480,ImageCompressionPolicy.targetSize(640,480))
        assertEquals(1 to 2_048,ImageCompressionPolicy.targetSize(1,50_000))
    }

    @Test
    fun samplingAndResizePolicyRemainBoundedUnderRepeatedStress() {
        repeat(5_000) { index ->
            val width=1+(index*7_919%65_535)
            val height=1+(index*15_413%65_535)
            val sample=ImageCompressionPolicy.sampleSize(width,height)
            val target=ImageCompressionPolicy.targetSize(width,height)
            assertTrue(sample>0 && sample.countOneBits()==1)
            assertTrue(maxOf(width,height)/sample<=4_096)
            assertTrue(target.first in 1..2_048)
            assertTrue(target.second in 1..2_048)
        }
    }

    @Test
    fun processedNameTracksOutputFormatWithoutTouchingPassThroughNames() {
        val renamed=ProcessedMedia(ByteArrayInputStream(byteArrayOf()),"image/jpeg","jpg")
        assertEquals("scan.jpg",renamed.displayName("scan.heic"))
        assertEquals("archive.jpg",renamed.displayName("archive"))
        assertEquals("Document.jpg",renamed.displayName(""))
        assertEquals(160,renamed.displayName("a".repeat(200)+".heic").length)
        val unchanged=ProcessedMedia(ByteArrayInputStream(byteArrayOf()),"application/pdf")
        assertEquals("report.final.pdf",unchanged.displayName("report.final.pdf"))
    }

    @Test
    fun passThroughRetainsBytesAndStreamIdentity() = runBlocking {
        val bytes=ByteArray(4_096) { (it%251).toByte() }
        val input=ByteArrayInputStream(bytes)
        PassThroughMediaPreprocessor.process(input,"application/pdf",MediaKind.DOCUMENT).use { result ->
            assertSame(input,result.input)
            assertEquals("application/pdf",result.mimeType)
            assertArrayEquals(bytes,result.input.readBytes())
        }
    }

    @Test
    fun processedMediaAlwaysClosesAndCleansUpWhenConsumerFails() {
        var inputClosed=false
        var cleaned=false
        val input=object:ByteArrayInputStream(ByteArray(32)) {
            override fun close() { inputClosed=true;super.close() }
        }
        try {
            ProcessedMedia(input,"image/jpeg","jpg") { cleaned=true }.use {
                error("simulated encryption failure")
            }
            fail("Consumer failure should escape")
        } catch(_:IllegalStateException) { }
        assertTrue(inputClosed)
        assertTrue(cleaned)
    }

    @Test
    fun animatedWebpIsDetectedForLosslessPassThrough() {
        val file=File.createTempFile("animated-",".webp")
        try {
            val bytes=ByteArray(32)
            "RIFF".encodeToByteArray().copyInto(bytes,0)
            "WEBP".encodeToByteArray().copyInto(bytes,8)
            "VP8X".encodeToByteArray().copyInto(bytes,12)
            bytes[20]=0x02
            file.writeBytes(bytes)
            assertEquals("image/webp",ImageCompressionPolicy.detectMimeType(file))
            assertTrue(ImageCompressionPolicy.isAnimatedWebp(file))
        } finally { file.delete() }
    }
}
