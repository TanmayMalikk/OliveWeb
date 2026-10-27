package com.olive.app.data.media

import java.io.Closeable
import java.io.InputStream

/**
 * Pluggable boundary between document-quality review and encryption.
 *
 * Intended pipeline: OCR recommendation -> [MediaPreprocessor] ->
 * [com.olive.app.security.MediaCipher] -> [MediaTransferStore] -> [MediaUploadWorker].
 *
 * A future compressor can replace [PassThroughMediaPreprocessor] without depending on Firebase,
 * WorkManager, R2, or UI classes. Implementations operate entirely on-device and return a stream
 * that the controller immediately encrypts. They must never upload plaintext or persist it outside
 * app-private storage.
 */
fun interface MediaPreprocessor {
    /** Opens the processed representation; ownership of [input] transfers to the returned object. */
    fun process(input:InputStream,mimeType:String,kind:MediaKind):ProcessedMedia
}

/**
 * Closeable result of preprocessing.
 *
 * [cleanup] exists for compressors that must create an app-private temporary plaintext file. It is
 * guaranteed to run after the processed stream closes, including when encryption throws.
 */
class ProcessedMedia(
    val input:InputStream,
    val mimeType:String,
    private val cleanup:()->Unit={},
):Closeable {
    override fun close() {
        try { input.close() } finally { cleanup() }
    }
}

/** No-op implementation used until the parallel compression implementation is ready. */
object PassThroughMediaPreprocessor:MediaPreprocessor {
    override fun process(input:InputStream,mimeType:String,kind:MediaKind)=ProcessedMedia(input,mimeType)
}
