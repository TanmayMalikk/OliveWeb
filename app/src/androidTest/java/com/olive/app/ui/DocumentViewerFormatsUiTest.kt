package com.olive.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.media.MediaKind
import com.olive.app.ocr.OcrResult
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.camera.CameraReview
import com.olive.app.ui.users.shared.camera.CapturedMedia
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentViewerFormatsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun document(file: File, mime: String) =
        SafetyDocument(file.name, "fixture", "", file.name, mime, listOf("fixture"))

    @Test
    fun textAndWordDocumentsRenderInThePrivateViewerAndCorruptWordFilesShowAnError() {
        val textFile = File.createTempFile("viewer-test-", ".txt", context.cacheDir)
        val wordFile = File.createTempFile("viewer-test-", ".docx", context.cacheDir)
        val corruptFile = File.createTempFile("viewer-test-", ".docx", context.cacheDir)
        val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        try {
            textFile.writeText("Synthetic private text")
            ZipOutputStream(wordFile.outputStream()).use {
                it.putNextEntry(ZipEntry("word/document.xml"))
                it.write("""<w:document xmlns:w="urn:fixture"><w:body><w:p><w:r><w:t>Synthetic Word text</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                it.closeEntry()
            }
            corruptFile.writeText("Invalid archive")
            val preview = mutableStateOf(document(textFile, "text/plain") to textFile)
            compose.setContent { OliveTheme { SafetyDocumentViewer(preview.value.first, preview.value.second) {} } }
            awaitText("Synthetic private text")
            compose.onNodeWithTag("media_zoom_in").performClick()
            compose.onNodeWithTag("media_zoom_out").performClick()
            compose.onNodeWithTag("media_zoom_reset").performClick()
            compose.runOnIdle { preview.value = document(wordFile, mime) to wordFile }
            awaitText("Synthetic Word text")
            compose.onNodeWithText("Synthetic private text").assertDoesNotExist()
            compose.runOnIdle { preview.value = document(corruptFile, mime) to corruptFile }
            awaitText(context.getString(R.string.documents_preview_unavailable))
        } finally {
            textFile.delete()
            wordFile.delete()
            corruptFile.delete()
        }
    }

    @Test
    fun imagePreviewSupportsZoomAndRotationAndUnsupportedFilesStayInApp() {
        val photo = File.createTempFile("viewer-image-", ".png", context.cacheDir)
        val unknown = File.createTempFile("viewer-unknown-", ".bin", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val preview = mutableStateOf(document(photo, "image/png") to photo)
            compose.setContent { OliveTheme { SafetyDocumentViewer(preview.value.first, preview.value.second) {} } }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(photo.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media_zoom_in").performClick()
            compose.onNodeWithTag("media_rotate").performClick()
            compose.onNodeWithTag("media_zoom_out").performClick()
            compose.onNodeWithTag("media_zoom_reset").performClick()
            compose.runOnIdle { preview.value = document(unknown, "application/octet-stream") to unknown }
            awaitText(context.getString(R.string.documents_preview_supported))
        } finally {
            photo.delete()
            unknown.delete()
        }
    }

    @Test
    fun brokenVideoReportsAPrivatePreviewErrorAndCanBeClosed() {
        val video = File.createTempFile("viewer-broken-", ".mp4", context.cacheDir)
        try {
            video.writeText("Synthetic invalid video")
            var closed = 0
            compose.setContent { OliveTheme { SafetyDocumentViewer(document(video, "video/mp4"), video) { closed++ } } }
            awaitText(context.getString(R.string.documents_preview_unavailable))
            compose.onNodeWithTag("media_zoom_in").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.runOnIdle { assertEquals(1, closed) }
        } finally {
            video.delete()
        }
    }

    @Test
    fun cameraReviewWarnsAboutUncertainOcrAndKeepsTheCaptureAvailableAfterUploadFailure() {
        val file = File.createTempFile("synthetic-scan-", ".png", context.cacheDir)
        try {
            val image = Bitmap.createBitmap(48, 65, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
            val captured = mutableStateOf(CapturedMedia(file, "image/png", "Synthetic scan", MediaKind.IMAGE,
                ocrResult = OcrResult(true, "Synthetic OCR text", 0.31f)))
            val busy = mutableStateOf(false)
            val error = mutableStateOf<String?>(null)
            var saved: CapturedMedia? = null
            var retakes = 0
            compose.setContent {
                OliveTheme {
                    CameraReview(captured.value, busy.value, error.value, { retakes++ }) { saved = it }
                }
            }
            compose.onNodeWithTag("camera_ocr_text").assertTextContains("Synthetic OCR text")
            compose.onNodeWithTag("camera_ocr_low_confidence").assertExists()
            val save = compose.onNodeWithTag("camera_save")
            val retake = compose.onNodeWithTag("camera_retake")
            save.assertTextContains(context.getString(R.string.camera_save_anyway)).performClick()
            compose.runOnIdle {
                assertEquals(captured.value, saved)
                busy.value = true
            }
            save.assertIsNotEnabled()
            retake.assertIsNotEnabled()
            compose.onNodeWithTag("camera_saving").assertIsDisplayed()
            compose.runOnIdle {
                busy.value = false
                error.value = "Synthetic connection failure"
            }
            compose.onNodeWithTag("camera_upload_error").assertTextContains("Synthetic connection failure")
            retake.assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(1, retakes)
                captured.value = captured.value.copy(ocrResult = OcrResult(true, "Verified fixture text", 0.96f))
                error.value = null
            }
            compose.onNodeWithTag("camera_ocr_low_confidence").assertDoesNotExist()
            save.assertTextContains(context.getString(R.string.camera_save))
        } finally {
            file.delete()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
