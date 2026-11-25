package com.olive.app.ui.users.user.documents

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyDocumentsUploadFlowDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safetyDocumentUploadFlowCoversOliveStorageCameraAndDismissal() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Documents, localMode = true).use { handle ->
            val session = handle.session
            var visible by mutableStateOf(true)
            var dismissedCount = 0
            var uploadStarted = false
            var selectedDoc: SafetyDocument? = null

            compose.setContent {
                OliveUiTestHost {
                    SafetyDocumentUploadFlow(
                        session = session,
                        visible = visible,
                        inWindowSheet = true,
                        onDismiss = { dismissedCount++; visible = false },
                        onSelectExisting = { selectedDoc = it },
                        onUploadStarted = { uploadStarted = true },
                    )
                }
            }
            compose.waitForIdle()

            // Open Olive Storage (empty), then go Back
            compose.onNodeWithTag("documents_choose_olive_storage").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.attachments_empty_help)).assertExists()
            compose.onNodeWithText(context.getString(R.string.back)).performClick()
            compose.waitForIdle()

            // Click camera source -> triggers onUploadStarted and onDismiss
            compose.onNodeWithTag("documents_choose_camera").performClick()
            compose.waitForIdle()
            assertTrue(uploadStarted && dismissedCount == 1 && selectedDoc == null)
        }
    }

    @Test
    fun safetyDocumentViewerCoversTextPdfUnsupportedAndMissingFiles() {
        compose.mainClock.autoAdvance = false
        val txtFile = File(context.cacheDir, "viewer_text.txt").apply { writeText("Confidential journal entry") }
        val binFile = File(context.cacheDir, "viewer_bin.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val badPdf = File(context.cacheDir, "viewer_bad.pdf").apply { writeBytes("corrupt".toByteArray()) }

        var mode by mutableStateOf("txt")
        var closedCount = 0

        compose.setContent {
            OliveUiTestHost {
                Column {
                    val (doc, file) = when (mode) {
                        "txt" -> SafetyDocument("v1", "u1", "p/1", "notes.txt", "text/plain", listOf("u1")) to txtFile
                        "pdf" -> SafetyDocument("v2", "u1", "p/2", "report.pdf", "application/pdf", listOf("u1")) to badPdf
                        else -> SafetyDocument("v3", "u1", "p/3", "blob.bin", "application/octet-stream", listOf("u1")) to binFile
                    }
                    SafetyDocumentViewer(document = doc, file = file, onClose = { closedCount++ })
                }
            }
        }
        compose.mainClock.advanceTimeBy(250)

        compose.runOnUiThread { mode = "pdf" }
        compose.mainClock.advanceTimeBy(250)

        compose.runOnUiThread { mode = "bin" }
        compose.mainClock.advanceTimeBy(250)

        compose.onNodeWithText(context.getString(R.string.back)).performClick()
        compose.mainClock.advanceTimeBy(150)
        assertTrue(closedCount == 1)
    }
}
