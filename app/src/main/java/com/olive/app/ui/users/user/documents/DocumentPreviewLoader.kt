// In-memory, session-scoped blurred preview renderer and cache for encrypted media and documents.
package com.olive.app.ui.users.user.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.exifinterface.media.ExifInterface
import com.olive.app.R
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaPreparationMemoryException
import com.olive.app.data.media.pickerMediaKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.KeyManager
import com.olive.app.security.MediaCipher
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

private const val PREVIEW_MAX_DIMENSION = 360
private const val PREVIEW_CACHE_CAPACITY = 48

internal data class OpenedMedia(val document: SafetyDocument, val file: File)

internal data class MediaTransferUiState(
    val state: com.olive.app.data.media.MediaTransferState = com.olive.app.data.media.MediaTransferState.Idle,
    val uploads: List<com.olive.app.data.media.MediaUploadPresentation> = emptyList(),
    val offlineCopies: Map<String, com.olive.app.data.media.OfflineMediaAvailability> = emptyMap(),
    val recentCachingEnabled: Boolean = false,
)

internal data class DocumentPreview(
    val bitmap: Bitmap?,
    val mediaKind: MediaKind,
    val ready: Boolean = true,
)

/**
 * Applies a multi-pass separable box blur directly on [source] so low-detail content previews
 * remain recognizable while obscured across all supported Android API levels (API 28+).
 *
 * @param source Compact preview bitmap to blur.
 * @param radius Half-kernel width in pixels; larger values increase blur strength.
 * @return A new blurred ARGB_8888 [Bitmap] matching the dimensions of [source].
 */
internal fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width <= 1 || height <= 1 || radius <= 0) {
        return source.copy(Bitmap.Config.ARGB_8888, false)
    }
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val temp = IntArray(width * height)
    val clampedRadius = radius.coerceAtMost(max(width, height))
    repeat(3) {
        boxBlurHorizontal(pixels, temp, width, height, clampedRadius)
        boxBlurVertical(temp, pixels, width, height, clampedRadius)
    }
    return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private fun boxBlurHorizontal(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val rowOffset = y * width
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val px = src[rowOffset + i.coerceIn(0, width - 1)]
            sumA += (px ushr 24) and 0xFF
            sumR += (px ushr 16) and 0xFF
            sumG += (px ushr 8) and 0xFF
            sumB += px and 0xFF
        }
        for (x in 0 until width) {
            dst[rowOffset + x] =
                ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
            val removeIdx = (x - radius).coerceIn(0, width - 1)
            val addIdx = (x + radius + 1).coerceIn(0, width - 1)
            val oldPx = src[rowOffset + removeIdx]
            val newPx = src[rowOffset + addIdx]
            sumA += ((newPx ushr 24) and 0xFF) - ((oldPx ushr 24) and 0xFF)
            sumR += ((newPx ushr 16) and 0xFF) - ((oldPx ushr 16) and 0xFF)
            sumG += ((newPx ushr 8) and 0xFF) - ((oldPx ushr 8) and 0xFF)
            sumB += (newPx and 0xFF) - (oldPx and 0xFF)
        }
    }
}

private fun boxBlurVertical(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val px = src[i.coerceIn(0, height - 1) * width + x]
            sumA += (px ushr 24) and 0xFF
            sumR += (px ushr 16) and 0xFF
            sumG += (px ushr 8) and 0xFF
            sumB += px and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] =
                ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
            val removeIdx = (y - radius).coerceIn(0, height - 1) * width + x
            val addIdx = (y + radius + 1).coerceIn(0, height - 1) * width + x
            val oldPx = src[removeIdx]
            val newPx = src[addIdx]
            sumA += ((newPx ushr 24) and 0xFF) - ((oldPx ushr 24) and 0xFF)
            sumR += ((newPx ushr 16) and 0xFF) - ((oldPx ushr 16) and 0xFF)
            sumG += ((newPx ushr 8) and 0xFF) - ((oldPx ushr 8) and 0xFF)
            sumB += (newPx and 0xFF) - (oldPx and 0xFF)
        }
    }
}

/**
 * Generates a compact, 50%-blurred live preview for an image, video frame, PDF page, or text/DOCX document.
 *
 * @param file Decrypted temporary source file.
 * @param mimeType Document MIME type.
 * @param name Document filename used for extension fallbacks.
 * @return A [DocumentPreview] containing a blurred bitmap when renderable, or `bitmap = null` otherwise.
 */
internal fun renderBlurredDocumentPreview(
    file: File,
    mimeType: String,
    name: String,
): DocumentPreview {
    val cleanMime = mimeType.substringBefore(';').trim().lowercase()
    val kind = pickerMediaKind(cleanMime)
    if (kind == MediaKind.AUDIO || !file.isFile || file.length() <= 0L) {
        return DocumentPreview(bitmap = null, mediaKind = kind)
    }
    val bitmap = when {
        cleanMime.startsWith("image/") -> decodeBlurredImagePreview(file)
        cleanMime.startsWith("video/") -> decodeBlurredVideoPreview(file)
        cleanMime == "application/pdf" || name.endsWith(".pdf", true) || file.extension.equals("pdf", true) ->
            renderBlurredPdfPreview(file)
        cleanMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            name.endsWith(".docx", true) || file.extension.equals("docx", true) ->
            renderBlurredTextPreview(extractDocxPreviewText(file))
        cleanMime.startsWith("text/") || name.endsWith(".txt", true) || file.extension.equals("txt", true) ->
            renderBlurredTextPreview(runCatching { file.readText().take(480) }.getOrDefault(""))
        else -> null
    }
    return DocumentPreview(bitmap = bitmap, mediaKind = kind)
}

private fun decodeBlurredImagePreview(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > PREVIEW_MAX_DIMENSION * 2) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    val oriented = applyExifOrientation(file, decoded)
    val scaled = scaleBitmapDown(oriented, PREVIEW_MAX_DIMENSION)
    if (scaled !== oriented) oriented.recycle()
    return blurBitmap(scaled, radius = 6).also { blurred ->
        if (blurred !== scaled) scaled.recycle()
    }
}

private fun decodeBlurredVideoPreview(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    val frame = try {
        retriever.setDataSource(file.absolutePath)
        runCatching {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                PREVIEW_MAX_DIMENSION,
                PREVIEW_MAX_DIMENSION,
            )
        }.getOrNull() ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    } ?: return null
    val scaled = scaleBitmapDown(frame, PREVIEW_MAX_DIMENSION)
    if (scaled !== frame) frame.recycle()
    return blurBitmap(scaled, radius = 6).also { blurred ->
        if (blurred !== scaled) scaled.recycle()
    }
}

private fun renderBlurredPdfPreview(file: File): Bitmap? = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return@runCatching null
            renderer.openPage(0).use { page ->
                val targetWidth = 300f
                val ratio = (targetWidth / page.width.coerceAtLeast(1)).coerceAtMost(2f)
                val w = (page.width * ratio).roundToInt().coerceIn(80, PREVIEW_MAX_DIMENSION)
                val h = (page.height * ratio).roundToInt().coerceIn(80, 440)
                val raw = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                raw.eraseColor(Color.WHITE)
                page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                blurBitmap(raw, radius = 4).also { if (it !== raw) raw.recycle() }
            }
        }
    }
}.getOrNull()

internal fun renderBlurredTextPreview(text: String): Bitmap? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return null
    val width = 300
    val height = 320
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(250, 250, 245))
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 48, 38)
        textSize = 16f
    }
    val contentWidth = width - 32
    val layout = StaticLayout.Builder.obtain(cleaned, 0, cleaned.length, paint, contentWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(4f, 1.15f)
        .setMaxLines(14)
        .build()
    canvas.withTranslation(16f, 18f) {
        layout.draw(this)
    }
    return blurBitmap(bitmap, radius = 2).also { if (it !== bitmap) bitmap.recycle() }
}

private fun extractDocxPreviewText(file: File): String = runCatching {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return@runCatching ""
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(zip.getInputStream(entry), "UTF-8")
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && text.length < 480) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfterLast(':') == "t") {
                text.append(parser.nextText())
            } else if (event == XmlPullParser.END_TAG && parser.name.substringAfterLast(':') == "p") {
                text.append('\n')
            }
            event = parser.next()
        }
        text.toString().trim()
    }
}.getOrDefault("")

private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (orientation == ExifInterface.ORIENTATION_NORMAL || matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / longest.toFloat()
    val targetWidth = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

/**
 * In-memory, account-scoped cache for compact blurred document previews.
 * Never persists plaintext thumbnails to disk and evicts all bitmaps when the session clears.
 */
internal class DocumentPreviewCache(private val scope: CoroutineScope) {
    private val gate = Semaphore(3)
    private val lru = LruCache<String, DocumentPreview>(PREVIEW_CACHE_CAPACITY)
    private val observable = mutableStateMapOf<String, DocumentPreview>()
    private val loading = mutableStateMapOf<String, Boolean>()
    private val completedOrFailed = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private var generation = 0

    private fun cacheKey(ownerUid: String, documentId: String) = "$ownerUid::$documentId"

    fun preview(ownerUid: String, documentId: String): DocumentPreview? {
        val key = cacheKey(ownerUid, documentId)
        return observable[key] ?: lru.get(key)
    }

    fun isLoading(ownerUid: String, documentId: String): Boolean =
        loading[cacheKey(ownerUid, documentId)] == true

    fun request(
        ownerUid: String,
        document: SafetyDocument,
        loader: suspend () -> File?,
    ) {
        if (pickerMediaKind(document.mimeType) == MediaKind.AUDIO) return
        val key = cacheKey(ownerUid, document.id)
        if (preview(ownerUid, document.id) != null || key in completedOrFailed || jobs[key]?.isActive == true) {
            return
        }
        val token = generation
        loading[key] = true
        jobs[key] = scope.launch {
            var tempFile: File? = null
            try {
                val rendered = gate.withPermit {
                    tempFile = withContext(Dispatchers.IO) { loader() }
                    val file = tempFile ?: return@withPermit null
                    withContext(Dispatchers.Default) {
                        renderBlurredDocumentPreview(file, document.mimeType, document.name)
                    }
                }
                if (token != generation) return@launch
                if (rendered != null) {
                    lru.put(key, rendered)
                    observable[key] = rendered
                }
                completedOrFailed.add(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) completedOrFailed.add(key)
            } finally {
                tempFile?.let { file -> runCatching { file.delete() } }
                if (token == generation) loading.remove(key)
                jobs.remove(key)
            }
        }
    }

    fun clear() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        loading.clear()
        observable.clear()
        completedOrFailed.clear()
        lru.evictAll()
    }
}

internal fun decryptCiphertextForPreview(
    source: File,
    document: SafetyDocument,
    viewerUid: String,
    cacheDir: File,
): File? {
    val deviceKey = "$viewerUid--${KeyManager.deviceId()}"
    val wrapped = listOfNotNull(document.deviceWrappedKeys[deviceKey], document.wrappedKeys[viewerUid])
    val key = runCatching { KeyManager.unwrapFirst(wrapped, document.encryptionVersion) }.getOrNull() ?: return null
    val opened = File(cacheDir, "opened-documents").apply { mkdirs() }
    val partial = File.createTempFile("thumb-", ".part", opened)
    return try {
        source.inputStream().buffered().use { input ->
            partial.outputStream().buffered().use { out -> MediaCipher.decrypt(input, out, key) }
        }
        partial
    } catch (_: Exception) {
        partial.delete()
        null
    }
}

/**
 * Computes preparation failure resource.
 *
 * @param failure Exception or failure reason encountered during execution.
 * @param kind Kind supplied to [preparationFailureResource].
 * @return The computed preparation failure resource number, or `null` if unavailable.
 */
internal fun preparationFailureResource(failure: Throwable, kind: MediaKind): Int? =
    if (failure is OutOfMemoryError || failure is MediaPreparationMemoryException) {
        if (kind == MediaKind.VIDEO) R.string.documents_video_memory_failed
        else R.string.documents_media_memory_failed
    } else null

/**
 * Computes preparation failure message.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param failure Exception or failure reason encountered during execution.
 * @param kind Kind supplied to [preparationFailureMessage].
 * @return The resulting preparation failure message string.
 */
internal fun preparationFailureMessage(
    context: Context,
    failure: Throwable,
    kind: MediaKind,
): String {
    val resource = preparationFailureResource(failure, kind)
    return resource?.let(context::getString)
        ?: failure.localizedMessage
        ?: context.getString(R.string.documents_upload_failed)
}
