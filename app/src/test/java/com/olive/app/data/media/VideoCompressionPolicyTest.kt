package com.olive.app.data.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionPolicyTest {
    @Test
    fun supportedVideoSubclassesAreEligibleOnlyForVideo() {
        val supported=listOf("video/mp4","video/quicktime","video/webm","video/3gpp","video/3gpp2",
            "video/x-matroska")
        supported.forEach { mime ->
            assertTrue(VideoCompressionPolicy.isEligible("  ${mime.uppercase()}; codecs=test",MediaKind.VIDEO))
            assertFalse(VideoCompressionPolicy.isEligible(mime,MediaKind.DOCUMENT))
        }
        listOf("video/x-msvideo","application/mp4","image/jpeg","application/octet-stream","")
            .forEach { assertFalse(VideoCompressionPolicy.isEligible(it,MediaKind.VIDEO)) }
    }

    @Test
    fun targetSizePreservesOrientationAndBoundsWideInputClasses() {
        assertEquals(1_920 to 1_080,VideoCompressionPolicy.targetSize(3_840,2_160,0))
        assertEquals(1_080 to 1_920,VideoCompressionPolicy.targetSize(3_840,2_160,90))
        assertEquals(1_080 to 1_080,VideoCompressionPolicy.targetSize(2_160,2_160,0))
        assertEquals(1_920 to 540,VideoCompressionPolicy.targetSize(3_840,1_080,0))
        assertEquals(640 to 480,VideoCompressionPolicy.targetSize(640,480,0))
        assertEquals(480 to 640,VideoCompressionPolicy.targetSize(640,480,270))
    }

    @Test
    fun repeatedDimensionAndBitrateStressRemainsEncoderSafe() {
        repeat(10_000) { index ->
            val width=2+(index*7_919%8_190)
            val height=2+(index*15_413%8_190)
            val rotation=listOf(0,90,180,270)[index%4]
            val target=VideoCompressionPolicy.targetSize(width,height,rotation)
            assertTrue(target.first%2==0 && target.second%2==0)
            assertTrue(maxOf(target.first,target.second)<=1_920)
            assertTrue(minOf(target.first,target.second)<=1_080)
            assertTrue(VideoCompressionPolicy.targetBitrate(target.first,target.second,
                listOf(23.976f,24f,30f,60f,120f)[index%5]) in 1_000_000..10_000_000)
        }
    }

    @Test
    fun isoAndEbmlSignaturesRejectSpoofedContainers() {
        val iso=temporary(ByteArray(24).apply { "ftyp".encodeToByteArray().copyInto(this,4) })
        val ebml=temporary(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte())+ByteArray(20))
        val spoof=temporary("not a video".encodeToByteArray())
        try {
            assertTrue(VideoCompressionPolicy.matchesContainer(iso,"video/mp4"))
            assertTrue(VideoCompressionPolicy.matchesContainer(iso,"video/quicktime"))
            assertFalse(VideoCompressionPolicy.matchesContainer(iso,"video/webm"))
            assertTrue(VideoCompressionPolicy.matchesContainer(ebml,"video/webm"))
            assertTrue(VideoCompressionPolicy.matchesContainer(ebml,"video/x-matroska"))
            assertFalse(VideoCompressionPolicy.matchesContainer(spoof,"video/mp4"))
        } finally { listOf(iso,ebml,spoof).forEach(File::delete) }
    }

    @Test
    fun outputExtensionsRemainTruthfulWhenOriginalIsRetained() {
        assertEquals("mp4",VideoCompressionPolicy.extensionFor("video/mp4"))
        assertEquals("mov",VideoCompressionPolicy.extensionFor("video/quicktime"))
        assertEquals("webm",VideoCompressionPolicy.extensionFor("video/webm"))
        assertEquals("3gp",VideoCompressionPolicy.extensionFor("video/3gpp"))
        assertEquals("3g2",VideoCompressionPolicy.extensionFor("video/3gpp2"))
        assertEquals("mkv",VideoCompressionPolicy.extensionFor("video/x-matroska"))
    }

    private fun temporary(bytes:ByteArray)=File.createTempFile("video-policy-",".tmp").apply {
        writeBytes(bytes)
    }
}
