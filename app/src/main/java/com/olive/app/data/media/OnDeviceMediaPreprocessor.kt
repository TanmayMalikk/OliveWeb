// Safe-app roles: routes eligible media through bounded local compression.
package com.olive.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
