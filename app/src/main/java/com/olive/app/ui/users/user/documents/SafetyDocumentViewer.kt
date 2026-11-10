// Users and support staff: opens shared images, videos, and documents in the app.
package com.olive.app.ui.users.user.documents

import com.olive.app.ui.shared.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.core.graphics.createBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.R
import com.olive.app.data.SafetyDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray


// Opens an in-app image, video, PDF, or text viewer for a safety document.
@Composable
internal fun SafetyDocumentViewer(document: SafetyDocument, file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Reload the preview when the selected encrypted file changes.
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
        // Opens the correct in-app viewer for an image, video, PDF, or text document.
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
// Fits an image inside the document viewer with zoom controls.
@Composable
private fun ImageViewer(file: File, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    val image by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeImageWithExif(file) }
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
                translationY = offsetY,
                rotationZ = rotation.toFloat(),
            )
        )
        ZoomControls(
            scale = scale,
            onZoomOut = {
                scale = (scale / 1.25f).coerceAtLeast(1f)
                if (scale == 1f) { offsetX = 0f; offsetY = 0f }
            },
            onReset = { scale = 1f; offsetX = 0f; offsetY = 0f },
            onZoomIn = { scale = (scale * 1.25f).coerceAtMost(5f) },
            onRotate = { rotation = (rotation + 90) % 360 },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

// Plays a saved safety video inside the app.
@Composable
private fun VideoViewer(file: File) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    var scale by rememberSaveable(file.absolutePath) { mutableFloatStateOf(1f) }
    var rotation by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    // Prepare and release video playback with the viewer lifecycle.
    DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
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
        }, modifier = Modifier.fillMaxSize().graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            rotationZ = rotation.toFloat(),
        ))
        ZoomControls(
            scale = scale,
            onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(1f) },
            onReset = { scale = 1f },
            onZoomIn = { scale = (scale * 1.25f).coerceAtMost(5f) },
            onRotate = { rotation = (rotation + 90) % 360 },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

private data class PdfPage(val bitmap: Bitmap, val count: Int)

// Renders a saved PDF one page at a time inside the app.
@Composable
private fun PdfViewer(file: File) {
    var page by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    var scale by rememberSaveable(file.absolutePath) { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    val rendered by produceState<PdfPage?>(null, file, page) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file, page) }
    }
    LaunchedEffect(page) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    val result = rendered
    if (result == null) Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
    else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(page) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                result.bitmap.asImageBitmap(),
                contentDescription = stringResource(
                    R.string.documents_pdf_page,
                    page + 1,
                    result.count
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                    rotationZ = rotation.toFloat(),
                )
            )
            ZoomControls(
                scale = scale,
                onZoomOut = {
                    scale = (scale / 1.25f).coerceAtLeast(1f)
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                },
                onReset = { scale = 1f; offsetX = 0f; offsetY = 0f },
                onZoomIn = { scale = (scale * 1.25f).coerceAtMost(5f) },
                onRotate = { rotation = (rotation + 90) % 360 },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
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

// user safety document viewer shows this text viewer view so the user can view its details and use its actions.
@Composable
private fun TextViewer(file: File, docx: Boolean) {
    var scale by rememberSaveable(file.absolutePath) { mutableFloatStateOf(1f) }
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
    else Column(Modifier.fillMaxSize()) {
        ZoomControls(
            scale = scale,
            onZoomOut = { scale = (scale / 1.2f).coerceAtLeast(1f) },
            onReset = { scale = 1f },
            onZoomIn = { scale = (scale * 1.2f).coerceAtMost(3f) },
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            item {
                Text(content!!, fontSize = (16f * scale).sp)
            }
        }
    }
}

// Gives every saved-media viewer the same visible zoom buttons and current zoom level.
@Composable
private fun ZoomControls(
    scale: Float,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onZoomIn: () -> Unit,
    onRotate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val resetDescription = stringResource(R.string.a11y_reset_zoom)
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onRotate != null) IconButton(
                onClick = onRotate,
                modifier = Modifier.testTag("media_rotate"),
            ) { Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.a11y_rotate_clockwise)) }
            IconButton(
                onClick = onZoomOut,
                enabled = scale > 1.01f,
                modifier = Modifier.testTag("media_zoom_out"),
            ) { Icon(Icons.Outlined.Remove, stringResource(R.string.a11y_zoom_out)) }
            TextButton(
                onClick = onReset,
                modifier = Modifier.semantics { contentDescription = resetDescription }
                    .testTag("media_zoom_reset"),
            ) {
                Text("${(scale * 100).toInt()}%")
            }
            IconButton(
                onClick = onZoomIn,
                enabled = scale < 4.99f,
                modifier = Modifier.testTag("media_zoom_in"),
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.a11y_zoom_in)) }
        }
    }
}

// Uses the photo's EXIF orientation so camera images are upright before the viewer adds user rotation.
private fun decodeImageWithExif(file: File): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
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

// user safety document viewer shows this unsupported viewer view so the user can view its details and use its actions.
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

