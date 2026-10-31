// Users and support staff: opens shared images, videos, and documents in the app.
package com.olive.app.ui.users.user.documents

import com.olive.app.ui.shared.*


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.graphics.createBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.R
import com.olive.app.data.SafetyDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

@Composable
internal fun SafetyDocumentViewer(document: SafetyDocument, file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(file) { onDispose { file.delete() } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.back)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    document.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.documents_private_viewer),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
        HorizontalDivider()
        when {
            document.mimeType.startsWith("image/") -> ImageViewer(file, document.name)
            document.mimeType.startsWith("video/") -> VideoViewer(file)
            document.mimeType == "application/pdf" || file.extension.equals(
                "pdf",
                true
            ) -> PdfViewer(file)

            document.mimeType == "text/plain" || file.extension.equals("txt", true) -> TextViewer(
                file,
                false
            )

            document.mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    file.extension.equals("docx", true) -> TextViewer(file, true)

            else -> UnsupportedViewer()
        }
    }
}

@Composable
private fun ImageViewer(file: File, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val image by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    val zoomPercent = (scale * 100).toInt()
    val zoomIn = stringResource(R.string.a11y_zoom_in)
    val zoomOut = stringResource(R.string.a11y_zoom_out)
    val reset = stringResource(R.string.a11y_reset_zoom)
    val zoomState = stringResource(R.string.a11y_zoom_percent, zoomPercent)
    val imageModifier = Modifier.fillMaxSize().semantics {
        stateDescription = zoomState
        customActions = listOf(
            CustomAccessibilityAction(zoomIn) {
                scale = (scale * 1.25f).coerceAtMost(5f)
                true
            },
            CustomAccessibilityAction(zoomOut) {
                scale = (scale / 1.25f).coerceAtLeast(1f)
                true
            },
            CustomAccessibilityAction(reset) {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
                true
            },
        )
    }.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offsetX += pan.x
            offsetY += pan.y
            if (scale == 1f) {
                offsetX = 0f
                offsetY = 0f
            }
        }
    }
    Box(imageModifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap == null) CircularProgressIndicator()
        else Image(
            bitmap.asImageBitmap(), contentDescription = name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        )
    }
}

@Composable
private fun VideoViewer(file: File) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = {
            VideoView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val controls =
                    MediaController(context)
                controls.setAnchorView(this)
                setMediaController(
                    controls
                )
                setVideoPath(file.absolutePath)
                setOnPreparedListener {
                    it.isLooping = false
                    seekTo(1)
                }
                player = this
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

private data class PdfPage(val bitmap: Bitmap, val count: Int)

@Composable
private fun PdfViewer(file: File) {
    var page by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    val rendered by produceState<PdfPage?>(null, file, page) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file, page) }
    }
    val result = rendered
    if (result == null) Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
    else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            result.bitmap.asImageBitmap(),
            contentDescription = stringResource(
                R.string.documents_pdf_page,
                page + 1,
                result.count
            ),
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { page-- },
                enabled = page > 0
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateBefore,
                    stringResource(R.string.ui_previous_safe_page)
                )
            }
            Text(stringResource(R.string.documents_pdf_page, page + 1, result.count))
            IconButton(
                onClick = { page++ },
                enabled = page + 1 < result.count
            ) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.next)) }
        }
    }
}

// Renders one bounded PDF page into a bitmap for the in-app document viewer.
private fun renderPdfPage(file: File, index: Int): PdfPage {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val safeIndex = index.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
            renderer.openPage(safeIndex).use { page ->
                val ratio = (1600f / page.width).coerceAtMost(2f)
                val bitmap =
                    createBitmap((page.width * ratio).toInt(), (page.height * ratio).toInt())
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return PdfPage(bitmap, renderer.pageCount)
            }
        }
    }
}

@Composable
private fun TextViewer(file: File, docx: Boolean) {
    val content by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (docx) readDocx(file) else file.readText().take(500_000)
            }.getOrNull()
        }
    }
    if (content == null) Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Text(
                content!!
            )
        }
    }
}

// Extracts readable paragraph text from the main XML file inside a DOCX archive.
private fun readDocx(file: File): String {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("Missing document content")
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(zip.getInputStream(entry), "UTF-8")
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && text.length < 500_000) {
            if (event == XmlPullParser.START_TAG && parser.name.endsWith("t")) text.append(parser.nextText())
            else if (event == XmlPullParser.END_TAG && parser.name.endsWith("p")) text.append('\n')
            event = parser.next()
        }
        return text.toString().trim()
    }
}

@Composable
private fun UnsupportedViewer() {
    Column(
        Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Description, null, Modifier.size(48.dp), tint = OliveGreen)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.documents_preview_unavailable),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.documents_preview_supported),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}
