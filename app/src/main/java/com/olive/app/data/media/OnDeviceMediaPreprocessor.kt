// Safe-app roles: routes eligible media through bounded local compression.
package com.olive.app.data.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Owns temporary plaintext and delegates only codec-specific work to focused compressors. */
internal class OnDeviceMediaPreprocessor(
    context:Context,
    private val imageCompressor:ImageMediaCompressor=ImageMediaCompressor(),
    private val videoCompressor:VideoMediaCompressor=VideoMediaCompressor(context.applicationContext),
):MediaPreprocessor {
    private val folder=File(context.applicationContext.noBackupFilesDir,TEMP_FOLDER)

    /**
     * Processes the on device media preprocessor.
     *
     * @param input Input stream, matrix, or value to process.
     * @param mimeType MIME content type of the payload.
     * @param kind Kind supplied to [process].
     * @return The resulting [ProcessedMedia] instance.
     */
    override suspend fun process(input:InputStream,mimeType:String,kind:MediaKind):ProcessedMedia =
        withContext(Dispatchers.IO) {
            val imageEligible=ImageCompressionPolicy.isEligible(mimeType,kind)
            val videoEligible=VideoCompressionPolicy.isEligible(mimeType,kind)
            if(!imageEligible && !videoEligible) {
                return@withContext ProcessedMedia(input,normalizedPassThroughMime(mimeType))
            }
            require(folder.exists() || folder.mkdirs()) { "Unable to prepare private media storage" }
            purgeStaleFiles()
            val source=File.createTempFile(SOURCE_PREFIX,".tmp",folder)
            var output:File?=null
            try {
                copyBounded(input,source,if(videoEligible) MAX_VIDEO_SOURCE_BYTES else MAX_IMAGE_SOURCE_BYTES)
                val result=if(videoEligible) {
                    videoCompressor.compress(source,folder,mimeType).let {
                        PreprocessedFile(it.file,it.mimeType,it.fileExtension)
                    }
                } else {
                    imageCompressor.compress(source,folder,mimeType).let {
                        PreprocessedFile(it.file,it.mimeType,it.fileExtension)
                    }
                }
                output=result.file
                ProcessedMedia(
                    result.file.inputStream().buffered(),
                    result.mimeType,
                    result.fileExtension,
                ) {
                    source.delete()
                    if(result.file!=source) result.file.delete()
                }
            } catch(failure:Throwable) {
                source.delete()
                output?.takeIf { it!=source }?.delete()
                throw failure
            }
        }

    private data class PreprocessedFile(val file:File,val mimeType:String,val fileExtension:String)

    /**
     * Copies bounded.
     *
     * @param input Input stream, matrix, or value to process.
     * @param target Target destination or output object to write to.
     * @param maxBytes Maximum bytes supplied to [copyBounded].
     */
    private fun copyBounded(input:InputStream,target:File,maxBytes:Long) {
        val buffer=ByteArray(BUFFER_BYTES)
        var total=0L
        try {
            input.use { source -> target.outputStream().buffered().use { sink ->
                while(true) {
                    val count=source.read(buffer)
                    if(count<0) break
                    if(count==0) continue
                    total+=count
                    require(total<=maxBytes) {
                        if(maxBytes==MAX_VIDEO_SOURCE_BYTES) "Video source must be no larger than 500 MB"
                        else "Image source must be smaller than 250 MB"
                    }
                    sink.write(buffer,0,count)
                }
            } }
            require(total>0) { "Media source is empty" }
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Purges stale files.
     *
     * @param now Current epoch timestamp in milliseconds.
     */
    private fun purgeStaleFiles(now:Long=System.currentTimeMillis()) {
        folder.listFiles()?.asSequence()?.filter {
            it.isFile && TEMP_PREFIXES.any(it.name::startsWith) &&
                now-it.lastModified()>STALE_FILE_AGE_MS
        }?.forEach(File::delete)
    }

    /**
     * Computes normalized pass through mime.
     *
     * @param mimeType MIME content type of the payload.
     * @return The resulting normalized pass through mime.
     */
    private fun normalizedPassThroughMime(mimeType:String)=
        mimeType.substringBefore(';').trim().lowercase().ifBlank { "application/octet-stream" }

    private companion object {
        val TEMP_PREFIXES=listOf("olive-media-","olive-image-","olive-video-")
        const val TEMP_FOLDER="media-preprocess"
        const val SOURCE_PREFIX="olive-media-source-"
        const val BUFFER_BYTES=64*1024
        const val MAX_IMAGE_SOURCE_BYTES=250L*1024L*1024L
        const val MAX_VIDEO_SOURCE_BYTES=500_000_000L
        const val STALE_FILE_AGE_MS=24L*60L*60L*1000L
    }
}

/** Narrow, replaceable boundary between capture/OCR and authenticated encryption. */
internal fun interface MediaPreprocessor {
    /**
     * Ownership of [input] transfers to the returned closeable result.
     *
     * @param input Input stream, matrix, or value to process.
     * @param mimeType MIME content type of the payload.
     * @param kind Kind supplied to [process].
     * @return The resulting [ProcessedMedia] instance.
     */
    suspend fun process(input:InputStream,mimeType:String,kind:MediaKind):ProcessedMedia
}

/**
 * One local preprocessing result. Closing it always closes its stream and removes temporary
 * plaintext, including when the encryption consumer fails.
 */
internal class ProcessedMedia(
    val input:InputStream,
    val mimeType:String,
    private val fileExtension:String?=null,
    private val cleanup:()->Unit={},
):Closeable {
    /**
     * Displays name.
     *
     * @param original Original supplied to [displayName].
     * @return The resulting display name string.
     */
    fun displayName(original:String):String {
        val extension=fileExtension?.trimStart('.')
            ?.takeIf { it.matches(EXTENSION_PATTERN) } ?: return original
        val trimmed=original.trim()
        val base=trimmed.substringBeforeLast('.',trimmed).ifBlank { DEFAULT_NAME }
        val bounded=base.take(MAX_NAME_LENGTH-extension.length-1).ifBlank { DEFAULT_NAME }
        return "$bounded.$extension"
    }

    /**
     * Closes the target.
     */
    override fun close() {
        try { input.close() } finally { cleanup() }
    }

    private companion object {
        val EXTENSION_PATTERN=Regex("[A-Za-z0-9]{1,8}")
        const val DEFAULT_NAME="Document"
        const val MAX_NAME_LENGTH=160
    }
}

/** Used for formats that must remain byte-exact and by focused controller tests. */
internal object PassThroughMediaPreprocessor:MediaPreprocessor {
    /**
     * Processes the pass through media preprocessor.
     *
     * @param input Input stream, matrix, or value to process.
     * @param mimeType MIME content type of the payload.
     * @param kind Kind supplied to [process].
     * @return The resulting process.
     */
    override suspend fun process(input:InputStream,mimeType:String,kind:MediaKind)=
        ProcessedMedia(input,mimeType)
}

/** Codec-only image compressor with no Firebase, WorkManager, R2, UI, or encryption dependency. */
internal class ImageMediaCompressor {
    data class Result(val file:File,val mimeType:String,val fileExtension:String)

    /**
     * Computes compress.
     *
     * @param source Source input or object to read from.
     * @param outputFolder Output folder supplied to [compress].
     * @param declaredMimeType Declared mime type supplied to [compress].
     * @return The resulting [Result] instance.
     */
    fun compress(source:File,outputFolder:File,declaredMimeType:String):Result {
        require(ImageCompressionPolicy.isSupportedStillMime(declaredMimeType)) {
            "Declared image type is unsupported"
        }
        val detectedMime=requireNotNull(ImageCompressionPolicy.detectMimeType(source)) {
            "Declared image type does not match a supported still image"
        }
        if(detectedMime=="image/webp" && ImageCompressionPolicy.isAnimatedWebp(source)) {
            return Result(source,detectedMime,ImageCompressionPolicy.extensionFor(detectedMime))
        }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Image could not be decoded" }
        require(bounds.outWidth<=MAX_SOURCE_DIMENSION && bounds.outHeight<=MAX_SOURCE_DIMENSION) {
            "Image dimensions are unsupported"
        }
        val actualMime=ImageCompressionPolicy.normalizedMimeType(bounds.outMimeType ?: detectedMime)
        require(ImageCompressionPolicy.isSupportedStillMime(actualMime)) {
            "Declared image type does not match a supported still image"
        }
        require(actualMime==detectedMime || setOf(actualMime,detectedMime)==setOf("image/heic","image/heif")) {
            "Image signature and decoder type do not match"
        }
        val originalExtension=ImageCompressionPolicy.extensionFor(actualMime)
        val options=BitmapFactory.Options().apply {
            inSampleSize=ImageCompressionPolicy.sampleSize(bounds.outWidth,bounds.outHeight)
            inPreferredConfig=Bitmap.Config.ARGB_8888
        }
        var bitmap=BitmapFactory.decodeFile(source.absolutePath,options)
            ?: error("Image could not be decoded")
        var compressed:File?=null
        try {
            compressed=File.createTempFile(OUTPUT_PREFIX,".tmp",outputFolder)
            bitmap=replace(bitmap,applyOrientation(bitmap,source))
            val target=ImageCompressionPolicy.targetSize(bitmap.width,bitmap.height)
            if(target.first!=bitmap.width || target.second!=bitmap.height) {
                bitmap= replace(bitmap, bitmap.scale(target.first, target.second))
            }
            val preserveAlpha=actualMime=="image/png" || (bitmap.hasAlpha() && actualMime!="image/jpeg")
            val outputMime=if(preserveAlpha) "image/png" else "image/jpeg"
            val outputExtension=ImageCompressionPolicy.extensionFor(outputMime)
            compressed.outputStream().buffered().use { output ->
                val format=if(preserveAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format,JPEG_QUALITY,output)) { "Image compression failed" }
            }
            require(compressed.length()>0) { "Image compression produced an empty result" }
            return if(compressed.length()<source.length()) {
                Result(compressed,outputMime,outputExtension)
            } else {
                compressed.delete()
                Result(source,actualMime,originalExtension)
            }
        } catch(failure:Throwable) {
            compressed?.delete()
            throw failure
        } finally {
            if(!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Applies orientation.
     *
     * @param bitmap Bitmap image to process or render.
     * @param source Source input or object to read from.
     * @return The resulting [Bitmap] for apply orientation.
     */
    private fun applyOrientation(bitmap:Bitmap,source:File):Bitmap {
        val orientation=runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix=Matrix()
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f);matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f);matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    }

    /**
     * Computes replace.
     *
     * @param previous Previous supplied to [replace].
     * @param next Next supplied to [replace].
     * @return The resulting [Bitmap] instance.
     */
    private fun replace(previous:Bitmap,next:Bitmap):Bitmap {
        if(previous!==next && !previous.isRecycled) previous.recycle()
        return next
    }

    private companion object {
        const val OUTPUT_PREFIX="olive-image-output-"
        const val MAX_SOURCE_DIMENSION=65_535
        const val JPEG_QUALITY=85
    }
}

/** Pure image policy kept separate from Android codecs for broad JVM stress testing. */
internal object ImageCompressionPolicy {
    private val eligibleMimeTypes=setOf("image/jpeg","image/png","image/webp","image/heic","image/heif")

    /**
     * Computes normalized mime type.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting normalized mime type.
     */
    fun normalizedMimeType(value:String)=when(val normalized=value.substringBefore(';').trim().lowercase()) {
        "image/jpg","image/pjpeg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> normalized
    }

    /**
     * Checks whether supported still mime.
     *
     * @param mimeType MIME content type of the payload.
     * @return `true` if is supported still mime, or `false` otherwise.
     */
    fun isSupportedStillMime(mimeType:String)=normalizedMimeType(mimeType) in eligibleMimeTypes

    /**
     * Computes detect mime type.
     *
     * @param file Local file to read, hash, process, or write.
     * @return The resulting detect mime type string, or `null` when unavailable.
     */
    fun detectMimeType(file:File):String? {
        val header=readHeader(file,32)
        if(header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() &&
            header[2]==0xff.toByte()) return "image/jpeg"
        val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        if(header.size>=png.size && png.indices.all { header[it]==png[it] }) return "image/png"
        if(header.size>=12 && ascii(header,0,4)=="RIFF" && ascii(header,8,4)=="WEBP") return "image/webp"
        if(header.size>=12 && ascii(header,4,4)=="ftyp") return when(ascii(header,8,4)) {
            "heic","heix","hevc","hevx" -> "image/heic"
            "mif1" -> "image/heif"
            else -> null
        }
        return null
    }

    /**
     * Checks whether animated webp.
     *
     * @param file Local file to read, hash, process, or write.
     * @return `true` if is animated webp, or `false` otherwise.
     */
    fun isAnimatedWebp(file:File):Boolean {
        if(detectMimeType(file)!="image/webp") return false
        val header=readHeader(file,64)
        return header.size>=21 && ascii(header,12,4)=="VP8X" && (header[20].toInt() and 0x02)!=0 || (12..(header.size-4).coerceAtLeast(11)).any { ascii(header,it,4)=="ANIM" }
    }

    /**
     * Checks whether eligible.
     *
     * @param mimeType MIME content type of the payload.
     * @param kind Kind supplied to [isEligible].
     * @return `true` if is eligible, or `false` otherwise.
     */
    fun isEligible(mimeType:String,kind:MediaKind)=
        kind in setOf(MediaKind.DOCUMENT,MediaKind.IMAGE) && isSupportedStillMime(mimeType)

    /**
     * Computes extension for.
     *
     * @param mimeType MIME content type of the payload.
     * @return The resulting extension for.
     */
    fun extensionFor(mimeType:String)=when(normalizedMimeType(mimeType)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "img"
    }

    /**
     * Computes target size.
     *
     * @param width Target or source width in pixels or layout units.
     * @param height Target or source height in pixels or layout units.
     * @param maxDimension Maximum dimension supplied to [targetSize].
     * @return The resulting [Pair] for target size.
     */
    fun targetSize(width:Int,height:Int,maxDimension:Int=2_048):Pair<Int,Int> {
        require(width>0 && height>0 && maxDimension>0)
        val largest=max(width,height)
        if(largest<=maxDimension) return width to height
        val scale=maxDimension.toDouble()/largest
        return maxOf(1,(width*scale).roundToInt()) to maxOf(1,(height*scale).roundToInt())
    }

    /**
     * Computes sample size.
     *
     * @param width Target or source width in pixels or layout units.
     * @param height Target or source height in pixels or layout units.
     * @param maxDecodeDimension Maximum decode dimension supplied to [sampleSize].
     * @return The computed sample size numeric value.
     */
    fun sampleSize(width:Int,height:Int,maxDecodeDimension:Int=4_096):Int {
        require(width>0 && height>0 && maxDecodeDimension>0)
        var sample=1
        while(max(width,height)/sample>maxDecodeDimension && sample<=Int.MAX_VALUE/2) sample*=2
        return sample
    }

    /**
     * Reads header.
     *
     * @param file Local file to read, hash, process, or write.
     * @param size Size or count limit for the operation.
     * @return The resulting [ByteArray] for read header.
     */
    private fun readHeader(file:File,size:Int):ByteArray {
        val buffer=ByteArray(size)
        val count=file.inputStream().use { input ->
            var total=0
            while(total<buffer.size) {
                val read=input.read(buffer,total,buffer.size-total)
                if(read<0) break
                if(read>0) total+=read
            }
            total
        }
        return buffer.copyOf(count)
    }
    /**
     * Linter believes length is always 4 here. I don't know why, but I don't see a need to mess with it.
     *
     * @param bytes Raw byte array payload.
     * @param start Start supplied to [ascii].
     * @param length Length supplied to [ascii].
     * @return The resulting ascii.
     */
    @Suppress("SameParameterValue")
    private fun ascii(bytes:ByteArray,start:Int,length:Int)=String(bytes,start,length,Charsets.US_ASCII)
}

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
                        context,decoder,Clock.DEFAULT,null,DataSourceBitmapLoader.Builder(context).build()))
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
