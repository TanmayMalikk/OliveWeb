// Safe-app roles: routes eligible media through bounded local compression.
package com.olive.app.data.media

import android.content.Context
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns temporary plaintext and delegates only codec-specific work to focused compressors. */
internal class OnDeviceMediaPreprocessor(
    context:Context,
    private val imageCompressor:ImageMediaCompressor=ImageMediaCompressor(),
    private val videoCompressor:VideoMediaCompressor=VideoMediaCompressor(context.applicationContext),
):MediaPreprocessor {
    private val folder=File(context.applicationContext.noBackupFilesDir,TEMP_FOLDER)

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

    private fun purgeStaleFiles(now:Long=System.currentTimeMillis()) {
        folder.listFiles()?.asSequence()?.filter {
            it.isFile && TEMP_PREFIXES.any(it.name::startsWith) &&
                now-it.lastModified()>STALE_FILE_AGE_MS
        }?.forEach(File::delete)
    }

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
