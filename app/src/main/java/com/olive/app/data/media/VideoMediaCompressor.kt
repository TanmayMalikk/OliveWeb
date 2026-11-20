// Safe-app roles: validates and compresses eligible video locally before encryption.
package com.olive.app.data.media
import android.app.ActivityManager
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
import androidx.media3.common.util.Clock
import androidx.media3.effect.Presentation
import androidx.media3.effect.FrameDropEffect
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
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

    /**
     * Computes compress.
     *
     * @param source Source input or object to read from.
     * @param outputFolder Output folder supplied to [compress].
     * @param declaredMimeType Declared mime type supplied to [compress].
     * @return The resulting [Result] instance.
     */
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
        val memoryClassMb=(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        if(VideoCompressionPolicy.shouldAvoidTranscode(
                input.width,input.height,input.frameRate,memoryClassMb)) {
            if(source.length()<=MAX_MEDIA_PLAINTEXT_FALLBACK_BYTES) {
                return original(source,declaredMimeType)
            }
            throw MediaPreparationMemoryException()
        }
        val target=VideoCompressionPolicy.targetSize(input.width,input.height,input.rotationDegrees)
        val bitrate=VideoCompressionPolicy.targetBitrate(target.first,target.second,input.frameRate)
        val output= withContext(Dispatchers.IO) {
            File.createTempFile(OUTPUT_PREFIX, ".mp4", outputFolder)
        }
        try {
            try {
                export(source,output,target,bitrate,input.frameRate)
            } catch (failure:ExportException) {
                // Some vendor decoders initialize successfully but fail on the first sample.
                // Retry once with a software decoder before retaining the original video.
                if (failure.errorCode != ExportException.ERROR_CODE_DECODING_FAILED) throw failure
                output.delete()
                export(source,output,target,bitrate,input.frameRate,softwareDecoder = true)
            }
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
        } catch(failure:ExportException) {
            if (com.olive.app.BuildConfig.DEBUG) android.util.Log.d(
                "OliveVideoCompression", "Codec fallback errorCode=${failure.errorCode}",
            )
            output.delete()
            return original(source,declaredMimeType)
        } catch(_:OutOfMemoryError) {
            output.delete()
            throw MediaPreparationMemoryException()
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

    /**
     * Avoids silently dropping additional media tracks by retaining the original container.
     *
     * @param file Local file to read, hash, process, or write.
     * @return The resulting [VideoInfo] instance.
     */
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

    /**
     * Exports the video media compressor.
     *
     * @param source Source input or object to read from.
     * @param output Output stream or target receiving processed data.
     * @param target Target destination or output object to write to.
     * @param bitrate Bitrate supplied to [export].
     * @param sourceFrameRate Source frame rate supplied to [export].
     * @param softwareDecoder Whether to apply software decoder.
     */
    private suspend fun export(
        source:File,
        output:File,
        target:Pair<Int,Int>,
        bitrate:Int,
        sourceFrameRate:Float,
        softwareDecoder:Boolean = false,
    ):Unit =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val videoEffects=buildList {
                    add(Presentation.createForWidthAndHeight(
                        target.first,target.second,Presentation.LAYOUT_SCALE_TO_FIT))
                    if(sourceFrameRate>MAX_OUTPUT_FRAME_RATE) {
                        add(FrameDropEffect.createSimpleFrameDropEffect(
                            sourceFrameRate,MAX_OUTPUT_FRAME_RATE))
                    }
                }
                val edited=EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(Effects(emptyList(),videoEffects))
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
                    /**
                     * Handles the completed event.
                     *
                     * @param composition Composition supplied to [onCompleted].
                     * @param exportResult Export result supplied to [onCompleted].
                     */
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if(continuation.isActive) continuation.resumeWith(kotlin.Result.success(Unit))
                    }

                    /**
                     * Handles the error event.
                     *
                     * @param composition Composition supplied to [onError].
                     * @param exportResult Export result supplied to [onError].
                     * @param exportException Export exception supplied to [onError].
                     */
                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
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
                val decoder=DefaultDecoderFactory.Builder(context)
                        .setEnableDecoderFallback(true)
                        .apply {
                            if (softwareDecoder) setMediaCodecSelector { mime, secure, tunneled ->
                                MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneled)
                                    .filter { it.softwareOnly }
                            }
                        }.build()
                transformer=Transformer.Builder(context)
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(
                        context,decoder,Clock.DEFAULT,null,DataSourceBitmapLoader(context)))
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

    /**
     * Computes original.
     *
     * @param source Source input or object to read from.
     * @param mimeType MIME content type of the payload.
     * @return The resulting original.
     */
    private fun original(source:File,mimeType:String)=Result(
        source,
        VideoCompressionPolicy.normalizedMimeType(mimeType),
        VideoCompressionPolicy.extensionFor(mimeType),
    )

    /**
     * Returns integer or null on media format.
     *
     * @param key Lookup key or cryptographic key.
     * @return The resulting get integer or null.
     */
    @Suppress("SameParameterValue")
    private fun MediaFormat.getIntegerOrNull(key:String)=
        if(containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /**
     * Returns long or null on media format.
     *
     * @param key Lookup key or cryptographic key.
     * @return The resulting get long or null.
     */
    @Suppress("SameParameterValue")
    private fun MediaFormat.getLongOrNull(key:String)=
        if(containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    /**
     * Returns float or null on media format.
     *
     * @param key Lookup key or cryptographic key.
     * @return The computed get float or null number, or `null` if unavailable.
     */
    @Suppress("SameParameterValue")
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
        const val MAX_OUTPUT_FRAME_RATE=30f
        const val MAX_LONG_SIDE=1_920
        const val MAX_SHORT_SIDE=1_080
    }
}

/** Pure container, dimension, and bitrate policy for JVM edge and stress tests. */
internal object VideoCompressionPolicy {
    private val supported=setOf(
        "video/mp4","video/quicktime","video/webm","video/3gpp","video/3gpp2","video/x-matroska",
    )

    /**
     * Computes normalized mime type.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting normalized mime type.
     */
    fun normalizedMimeType(value:String)=value.substringBefore(';').trim().lowercase()
    /**
     * Checks whether supported mime.
     *
     * @param value Input value to store, parse, or format.
     * @return `true` if is supported mime, or `false` otherwise.
     */
    fun isSupportedMime(value:String)=normalizedMimeType(value) in supported
    /**
     * Checks whether eligible.
     *
     * @param value Input value to store, parse, or format.
     * @param kind Kind supplied to [isEligible].
     * @return `true` if is eligible, or `false` otherwise.
     */
    fun isEligible(value:String,kind:MediaKind)=kind==MediaKind.VIDEO && isSupportedMime(value)

    /**
     * Computes matches container.
     *
     * @param file Local file to read, hash, process, or write.
     * @param mimeType MIME content type of the payload.
     * @return `true` if matches container, or `false` otherwise.
     */
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

    /**
     * Computes target size.
     *
     * @param width Target or source width in pixels or layout units.
     * @param height Target or source height in pixels or layout units.
     * @param rotationDegrees Rotation degrees supplied to [targetSize].
     * @return The resulting [Pair] for target size.
     */
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

    /**
     * Computes target bitrate.
     *
     * @param width Target or source width in pixels or layout units.
     * @param height Target or source height in pixels or layout units.
     * @param frameRate Frame rate supplied to [targetBitrate].
     * @return The computed target bitrate numeric value.
     */
    fun targetBitrate(width:Int,height:Int,frameRate:Float):Int {
        require(width>0 && height>0 && frameRate.isFinite() && frameRate>0)
        val outputFrameRate=minOf(frameRate,MAX_OUTPUT_FRAME_RATE)
        return (width.toLong()*height*outputFrameRate*BITS_PER_PIXEL_FRAME).roundToInt()
            .coerceIn(MIN_VIDEO_BITRATE,MAX_VIDEO_BITRATE)
    }

    /**
     * Determines whether the app should avoid transcode.
     *
     * @param width Target or source width in pixels or layout units.
     * @param height Target or source height in pixels or layout units.
     * @param frameRate Frame rate supplied to [shouldAvoidTranscode].
     * @param memoryClassMb Memory class mb supplied to [shouldAvoidTranscode].
     * @return `true` if should avoid transcode, or `false` otherwise.
     */
    fun shouldAvoidTranscode(width:Int,height:Int,frameRate:Float,memoryClassMb:Int):Boolean {
        require(width>0 && height>0 && frameRate.isFinite() && frameRate>0 && memoryClassMb>0)
        val inputIsUhd=maxOf(width,height)>MAX_LONG_SIDE && minOf(width,height)>MAX_SHORT_SIDE
        return inputIsUhd && memoryClassMb<=LOW_MEMORY_CLASS_MB
    }

    /**
     * Computes extension for.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting extension for.
     */
    fun extensionFor(value:String)=when(normalizedMimeType(value)) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/x-matroska" -> "mkv"
        else -> "mp4"
    }

    /**
     * Computes even.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting even.
     */
    private fun even(value:Int)=maxOf(2,value-(value and 1))

    private const val MAX_LONG_SIDE=1_920
    private const val MAX_SHORT_SIDE=1_080
    private const val BITS_PER_PIXEL_FRAME=0.08
    private const val MIN_VIDEO_BITRATE=1_000_000
    private const val MAX_VIDEO_BITRATE=10_000_000
    private const val MAX_OUTPUT_FRAME_RATE=30f
    private const val LOW_MEMORY_CLASS_MB=256
}
