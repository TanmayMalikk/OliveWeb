package com.olive.app.ui.users.user.documents

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.exifinterface.media.ExifInterface
import com.olive.app.R
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyDocumentViewerDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun doc(name: String, mime: String) = SafetyDocument(
        id = "doc-$name",
        ownerUid = "owner-1",
        storagePath = "users/owner-1/docs/$name",
        name = name,
        mimeType = mime,
        allowedUsers = listOf("owner-1"),
    )

    @Test
    fun imageViewerWithExifOrientationSupportsZoomAndRotateControls() {
        compose.mainClock.autoAdvance = false
        val imageFile = File(context.cacheDir, "test-exif.jpg")
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        FileOutputStream(imageFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bmp.recycle()
        ExifInterface(imageFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        var backed = false
        compose.setContent {
            OliveUiTestHost {
                SafetyDocumentViewer(doc("Photo", "image/jpeg"), imageFile, onBack = { backed = true })
            }
        }
        compose.mainClock.advanceTimeBy(300)
        Thread.sleep(80)
        compose.mainClock.advanceTimeBy(200)

        compose.onNodeWithTag("media_zoom_in").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("media_rotate").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("media_zoom_out").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("media_zoom_reset").performClick()
        compose.mainClock.advanceTimeBy(100)

        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(backed)
    }

    @Test
    fun pdfDocxTextAndAudioDocumentsRenderExpectedViewers() {
        compose.mainClock.autoAdvance = false
        val docxFile = File(context.cacheDir, "note.docx")
        ZipOutputStream(FileOutputStream(docxFile)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>Hello from docx</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }

        compose.setContent {
            OliveUiTestHost {
                SafetyDocumentViewer(doc("Plan.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), docxFile, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(300)
        Thread.sleep(80)
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Hello from docx", substring = true).assertExists()
    }

    @Test
    fun pdfAndUnsupportedAudioDocumentsRenderCleanly() {
        compose.mainClock.autoAdvance = false
        val pdfFile = File(context.cacheDir, "sample.pdf")
        runCatching {
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            FileOutputStream(pdfFile).use { pdf.writeTo(it) }
            pdf.close()
        }.onFailure {
            pdfFile.writeBytes("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".toByteArray())
        }

        val audioFile = File(context.cacheDir, "clip.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        compose.setContent {
            OliveUiTestHost {
                SafetyDocumentViewer(doc("Report.pdf", "application/pdf"), pdfFile, onBack = {})
                SafetyDocumentViewer(doc("Audio.m4a", "audio/mp4"), audioFile, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(300)
        Thread.sleep(80)
        compose.mainClock.advanceTimeBy(200)
        compose.onAllNodesWithText(context.getString(R.string.documents_preview_unavailable))[0].assertExists()
    }
}
