// Safe-app roles: validates and compresses eligible video locally before encryption.
package com.olive.app.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Media3 adapter with no Firebase, R2, WorkManager, UI, or encryption responsibilities. */
@OptIn(UnstableApi::class)
internal class VideoMediaCompressor(private val context:Context) {
    data class Result(val file:File,val mimeType:String,val fileExtension:String)

    suspend fun compress(source:File,outputFolder:File,declaredMimeType:String):Result {
        require(VideoCompressionPolicy.isSupportedMime(declaredMimeType)) {
            "Declared video type is unsupported"
        }
        require(VideoCompressionPolicy.matchesContainer(source,declaredMimeType)) {
            "Declared video type does not match its container"
        }
        val input=try {
            inspect(source)
        } catch(_:VideoNotCompressibleException) {
            return original(source,declaredMimeType)
        }
        val target=VideoCompressionPolicy.targetSize(input.width,input.height,input.rotationDegrees)
        val bitrate=VideoCompressionPolicy.targetBitrate(target.first,target.second,input.frameRate)
        val output=File.createTempFile(OUTPUT_PREFIX,".mp4",outputFolder)
        try {
            export(source,output,target,bitrate)
            require(output.isFile && output.length()>0) { "Video compression produced an empty result" }
            val exported=inspect(output)
            require(maxOf(exported.width,exported.height)<=MAX_LONG_SIDE &&
                minOf(exported.width,exported.height)<=MAX_SHORT_SIDE) {
                "Compressed video dimensions are unsupported"
            }
            return if(output.length()<source.length()) Result(output,"video/mp4","mp4") else {
                output.delete()
                original(source,declaredMimeType)
            }
        } catch(_:ExportException) {
            output.delete()
            return original(source,declaredMimeType)
        } catch(failure:Throwable) {
            output.delete()
            throw failure
        }
    }

    private data class VideoInfo(
        val width:Int,
        val height:Int,
        val rotationDegrees:Int,
        val frameRate:Float,
    )

    /** Avoids silently dropping additional media tracks by retaining the original container. */
    private fun inspect(file:File):VideoInfo {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video:MediaFormat?=null
            var audioTracks=0
            for(index in 0 until extractor.trackCount) {
                val format=extractor.getTrackFormat(index)
                val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime in COMPRESSIBLE_VIDEO_MIMES -> {
                        if(video!=null) throw VideoNotCompressibleException()
                        video=format
                    }
                    mime.startsWith("video/") -> throw VideoNotCompressibleException()
                    mime.startsWith("audio/") -> {
                        audioTracks++
                        if(audioTracks>1) throw VideoNotCompressibleException()
                    }
                    mime.isNotBlank() -> throw VideoNotCompressibleException()
                }
            }
            val format=requireNotNull(video) { "Video track is missing" }
            val width=format.getInteger(MediaFormat.KEY_WIDTH)
            val height=format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width>0 && height>0) { "Video dimensions are invalid" }
            val duration=format.getLongOrNull(MediaFormat.KEY_DURATION)
            require(duration==null || duration>0) { "Video duration is invalid" }
            return VideoInfo(
                width,
                height,
                format.getIntegerOrNull(MediaFormat.KEY_ROTATION) ?: 0,
                (format.getFloatOrNull(MediaFormat.KEY_FRAME_RATE) ?: DEFAULT_FRAME_RATE)
                    .takeIf { it.isFinite() && it>0 } ?: DEFAULT_FRAME_RATE,
            )
        } finally {
            extractor.release()
        }
    }

    private suspend fun export(source:File,output:File,target:Pair<Int,Int>,bitrate:Int):Unit =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val edited=EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(
                        target.first,target.second,Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                @Suppress("DEPRECATION")
                val sequence=EditedMediaItemSequence.Builder(listOf(edited)).build()
                val composition=Composition.Builder(listOf(sequence))
                    .setHdrMode(if(Build.VERSION.SDK_INT>=29)
                        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    else Composition.HDR_MODE_KEEP_HDR)
                    .build()
                lateinit var transformer:Transformer
                val listener=object:Transformer.Listener {
                    override fun onCompleted(composition:Composition,exportResult:ExportResult) {
                        if(continuation.isActive) continuation.resumeWith(kotlin.Result.success(Unit))
                    }

                    override fun onError(
                        composition:Composition,
                        exportResult:ExportResult,
                        exportException:ExportException,
                    ) {
                        if(continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }
                val encoder=DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build())
                    .build()
                transformer=Transformer.Builder(context)
                    .setEncoderFactory(encoder)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()
                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        transformer.cancel()
                        output.delete()
                    }
                }
                transformer.start(composition,output.absolutePath)
            }
        }

    private fun original(source:File,mimeType:String)=Result(
        source,
        VideoCompressionPolicy.normalizedMimeType(mimeType),
        VideoCompressionPolicy.extensionFor(mimeType),
    )

    private fun MediaFormat.getIntegerOrNull(key:String)=
        if(containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.getLongOrNull(key:String)=
        if(containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private fun MediaFormat.getFloatOrNull(key:String):Float? {
        if(!containsKey(key)) return null
        return runCatching { getFloat(key) }.getOrElse {
            runCatching { getInteger(key).toFloat() }.getOrNull()
        }
    }

    private class VideoNotCompressibleException:IllegalArgumentException()

    private companion object {
        val COMPRESSIBLE_VIDEO_MIMES=setOf(
            "video/avc","video/hevc","video/mp4v-es","video/3gpp",
            "video/x-vnd.on2.vp8","video/x-vnd.on2.vp9","video/av01","video/dolby-vision",
        )
        const val OUTPUT_PREFIX="olive-video-output-"
        const val AUDIO_BITRATE=128_000
        const val DEFAULT_FRAME_RATE=30f
        const val MAX_LONG_SIDE=1_920
        const val MAX_SHORT_SIDE=1_080
    }
}

/** Pure container, dimension, and bitrate policy for JVM edge and stress tests. */
internal object VideoCompressionPolicy {
    private val supported=setOf(
        "video/mp4","video/quicktime","video/webm","video/3gpp","video/3gpp2","video/x-matroska",
    )

    fun normalizedMimeType(value:String)=value.substringBefore(';').trim().lowercase()
    fun isSupportedMime(value:String)=normalizedMimeType(value) in supported
    fun isEligible(value:String,kind:MediaKind)=kind==MediaKind.VIDEO && isSupportedMime(value)

    fun matchesContainer(file:File,mimeType:String):Boolean {
        val header=ByteArray(12)
        val count=file.inputStream().use { input ->
            var total=0
            while(total<header.size) {
                val read=input.read(header,total,header.size-total)
                if(read<0) break
                if(read>0) total+=read
            }
            total
        }
        val normalized=normalizedMimeType(mimeType)
        val iso=count>=12 && header.copyOfRange(4,8).decodeToString()=="ftyp"
        val ebml=count>=4 && header.sliceArray(0..3).contentEquals(
            byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))
        return when(normalized) {
            "video/mp4","video/quicktime","video/3gpp","video/3gpp2" -> iso
            "video/webm","video/x-matroska" -> ebml
            else -> false
        }
    }

    fun targetSize(width:Int,height:Int,rotationDegrees:Int):Pair<Int,Int> {
        require(width>0 && height>0)
        val rotated=rotationDegrees.mod(180)!=0
        val displayWidth=if(rotated) height else width
        val displayHeight=if(rotated) width else height
        val scale=minOf(
            1.0,
            MAX_LONG_SIDE.toDouble()/maxOf(displayWidth,displayHeight),
            MAX_SHORT_SIDE.toDouble()/minOf(displayWidth,displayHeight),
        )
        return even((displayWidth*scale).roundToInt()) to even((displayHeight*scale).roundToInt())
    }

    fun targetBitrate(width:Int,height:Int,frameRate:Float):Int {
        require(width>0 && height>0 && frameRate.isFinite() && frameRate>0)
        return (width.toLong()*height*frameRate*BITS_PER_PIXEL_FRAME).roundToInt()
            .coerceIn(MIN_VIDEO_BITRATE,MAX_VIDEO_BITRATE)
    }

    fun extensionFor(value:String)=when(normalizedMimeType(value)) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/x-matroska" -> "mkv"
        else -> "mp4"
    }

    private fun even(value:Int)=maxOf(2,value-(value and 1))

    private const val MAX_LONG_SIDE=1_920
    private const val MAX_SHORT_SIDE=1_080
    private const val BITS_PER_PIXEL_FRAME=0.08
    private const val MIN_VIDEO_BITRATE=1_000_000
    private const val MAX_VIDEO_BITRATE=10_000_000
}
