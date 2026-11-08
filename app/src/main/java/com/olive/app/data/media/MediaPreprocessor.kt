// Safe-app roles: prepares media locally before the encrypted upload boundary.
package com.olive.app.data.media

import java.io.Closeable
import java.io.InputStream

/** Narrow, replaceable boundary between capture/OCR and authenticated encryption. */
internal fun interface MediaPreprocessor {
    /** Ownership of [input] transfers to the returned closeable result. */
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
    fun displayName(original:String):String {
        val extension=fileExtension?.trimStart('.')
            ?.takeIf { it.matches(EXTENSION_PATTERN) } ?: return original
        val trimmed=original.trim()
        val base=trimmed.substringBeforeLast('.',trimmed).ifBlank { DEFAULT_NAME }
        val bounded=base.take(MAX_NAME_LENGTH-extension.length-1).ifBlank { DEFAULT_NAME }
        return "$bounded.$extension"
    }

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
    override suspend fun process(input:InputStream,mimeType:String,kind:MediaKind)=
        ProcessedMedia(input,mimeType)
}
