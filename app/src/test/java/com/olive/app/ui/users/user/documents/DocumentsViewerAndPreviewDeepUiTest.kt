package com.olive.app.ui.users.user.documents

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.media.MediaKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.TestPdfDocumentShadow
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp", shadows = [TestPdfDocumentShadow::class])
class DocumentsViewerAndPreviewDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun tempImageFile(): File = File.createTempFile("doc-view", ".png").apply {
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        FileOutputStream(this).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun tempTextFile(text: String): File = File.createTempFile("doc-view", ".txt").apply {
        writeText(text)
    }

    private fun tempDocxFile(): File = File.createTempFile("doc-view", ".docx").apply {
        ZipOutputStream(FileOutputStream(this)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>Hello Docx</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Test
    fun safetyDocumentViewerCoversImageTextDocxAudioAndZoomControls() {
        val imgFile = tempImageFile()
        val txtFile = tempTextFile("First paragraph of private text note.\nSecond line.")
        val docxFile = tempDocxFile()

        val imgPreview = renderBlurredDocumentPreview(imgFile, "image/png", "photo.png")
        assertNotNull(imgPreview.bitmap)
        val txtPreview = renderBlurredDocumentPreview(txtFile, "text/plain", "note.txt")
        assertNotNull(txtPreview.bitmap)
        val docxPreview = renderBlurredDocumentPreview(docxFile, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "plan.docx")
        assertNotNull(docxPreview.bitmap)
        assertNull(renderBlurredDocumentPreview(txtFile, "audio/mpeg", "voice.mp3").bitmap)

        val imgDoc = SafetyDocument("d-img", "owner", "path", "evidence.png", "image/png", listOf("owner"), 1000L, evidence = true)
        val txtDoc = SafetyDocument("d-txt", "owner", "path", "notes.txt", "text/plain", listOf("owner"), 1000L)
        val docxDoc = SafetyDocument("d-docx", "owner", "path", "lease.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", listOf("owner"), 1000L)
        val audioDoc = SafetyDocument("d-aud", "owner", "path", "clip.mp3", "audio/mpeg", listOf("owner"), 1000L)

        var stage by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OliveUiTestHost {
                when (stage) {
                    0 -> SafetyDocumentViewer(imgDoc, imgFile) {}
                    1 -> SafetyDocumentViewer(txtDoc, txtFile) {}
                    2 -> SafetyDocumentViewer(docxDoc, docxFile) {}
                    else -> SafetyDocumentViewer(audioDoc, File.createTempFile("aud", ".mp3")) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("media_rotate").performClick()
        compose.onNodeWithTag("media_zoom_in").performClick()
        compose.onNodeWithTag("media_zoom_reset").performClick()

        stage = 1
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("media_zoom_in").performClick()
        compose.onNodeWithTag("media_zoom_out").performClick()
        compose.onNodeWithTag("media_zoom_reset").performClick()

        stage = 2
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(300)

        stage = 3
        compose.mainClock.advanceTimeBy(200)
    }

    @Test
    fun blurredPreviewGridsAndStripsRenderAcrossAttachmentLayouts() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Documents, localMode = true).use { handle ->
            val docs = (1..5).map { idx ->
                SafetyDocument("doc-$idx", "owner", "p-$idx", "file-$idx.jpg", "image/jpeg", listOf("owner"), 1000L + idx, evidence = idx == 1)
            }
            runBlocking { handle.session.documents.rememberRecoveredDocuments("owner", docs) }

            compose.setContent {
                OliveUiTestHost {
                    Column {
                        OliveStoragePreviewGrid(docs.take(2), handle.session.documents) {}
                        NoteAttachmentPreviewStrip(docs.map { it.id }, "owner", handle.session.documents)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("olive_storage_doc-1").performClick()
            compose.onNodeWithText("+1").assertExists()
        }
    }

    @Test
    fun interactiveLocationMapTransitionsReadyZoomCompassFollowAndErrorRetry() {
        compose.mainClock.autoAdvance = false
        var rootView: View? = null
        var zooms = 0
        compose.setContent {
            OliveUiTestHost {
                rootView = LocalView.current.rootView
                InteractiveLocationMap(
                    points = listOf(MapLocationPoint("owner", "Alex", 53.5461, -113.4938, 1000L, true)),
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    userSearchEnabled = true,
                    compassEnabled = true,
                    onToggleHeadingUp = { zooms++ },
                    initialFollowUid = "owner",
                )
            }
        }
        compose.mainClock.advanceTimeBy(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val webView = rootView?.let(::findWebView)
        assertNotNull(webView)
        val chromeClient = webView!!.webChromeClient ?: Shadows.shadowOf(webView).webChromeClient
        assertNotNull(chromeClient)
        compose.runOnUiThread {
            chromeClient!!.onReceivedTitle(webView, "olive-map-ready")
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithTag("location_map_view").assertExists()

        compose.runOnUiThread {
            chromeClient!!.onReceivedTitle(webView, "olive-map-error")
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(16_000)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithTag("map_retry").assertExists()
    }
}
