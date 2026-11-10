package com.olive.app.data.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoMediaCompressorInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun highBitrateMp4CompressesAndSurvivesAuthenticatedEncryption() = runBlocking {
        val source=File.createTempFile("video-source-",".mp4",context.cacheDir)
        try {
            createAvcFixture(source,640,360,60,8_000_000)
            val original=source.readBytes()
            val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
            val before=folder.listFiles().orEmpty().toSet()
            val result=OnDeviceMediaPreprocessor(context).process(
                ByteArrayInputStream(original),"video/mp4",MediaKind.VIDEO)
            val processed=result.use { it.input.readBytes() }
            assertEquals("video/mp4",result.mimeType)
            assertEquals("clip.mp4",result.displayName("clip.mov"))
            assertTrue(processed.isNotEmpty())
            assertTrue(processed.size<original.size)
            assertEquals(before,folder.listFiles().orEmpty().toSet())
            assertArrayEquals(processed,encryptedRoundTrip(processed))
        } finally { source.delete() }
    }

    @Test
    fun corruptDeclaredMp4FailsClosedAndRemovesPlaintext() = runBlocking {
        val folder=File(context.noBackupFilesDir,"media-preprocess").apply { mkdirs() }
        val before=folder.listFiles().orEmpty().toSet()
        runCatching {
            OnDeviceMediaPreprocessor(context).process(
                ByteArrayInputStream(ByteArray(16_384) { 0x55 }),"video/mp4",MediaKind.VIDEO)
        }.onSuccess { error("Corrupt video must fail closed") }
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

    private fun createAvcFixture(file:File,width:Int,height:Int,frames:Int,bitrate:Int) {
        val codec=MediaCodec.createEncoderByType("video/avc")
        val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info=MediaCodec.BufferInfo()
            var frame=0
            var inputEnded=false
            var outputEnded=false
            var track=-1
            while(!outputEnded) {
                if(!inputEnded) {
                    val inputIndex=codec.dequeueInputBuffer(10_000)
                    if(inputIndex>=0) {
                        if(frame<frames) {
                            val buffer=checkNotNull(codec.getInputBuffer(inputIndex))
                            buffer.clear()
                            fillFrame(buffer,width,height,frame)
                            codec.queueInputBuffer(inputIndex,0,width*height*3/2,
                                frame*1_000_000L/30,0)
                            frame++
                        } else {
                            codec.queueInputBuffer(inputIndex,0,0,frame*1_000_000L/30,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded=true
                        }
                    }
                }
                when(val outputIndex=codec.dequeueOutputBuffer(info,10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track=muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted=true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if(outputIndex>=0) {
                        val output=checkNotNull(codec.getOutputBuffer(outputIndex))
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            check(muxerStarted)
                            output.position(info.offset)
                            output.limit(info.offset+info.size)
                            muxer.writeSampleData(track,output,info)
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(outputIndex,false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if(muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes")
        } finally { retriever.release() }
    }

    private fun fillFrame(buffer:java.nio.ByteBuffer,width:Int,height:Int,frame:Int) {
        var value=frame*1_103_515_245+12_345
        repeat(width*height) {
            value=value*1_103_515_245+12_345
            buffer.put((value ushr 16).toByte())
        }
        repeat(width*height/2) { buffer.put((128+(frame%9)-4).toByte()) }
    }
}
