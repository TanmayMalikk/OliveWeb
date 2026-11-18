// Tests that a user photo opens inside the private document viewer.
package com.olive.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SafetyDocumentViewerUiTest {
    @get:Rule
    val compose = createOliveComposeRule()

    // Checks the image opens inside private viewer step with the test's sample data.
    @Test
    fun imageOpensInsidePrivateViewer() {
        val file = File(compose.activity.cacheDir, "viewer-test.png")
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val document = SafetyDocument(
            "test",
            "owner",
            "path",
            "Private photo.png",
            "image/png",
            listOf("owner")
        )
        compose.activity.setContent { OliveTheme { SafetyDocumentViewer(document, file) {} } }
        compose.onNodeWithText("Private in-app viewer").assertExists()
        compose.onNodeWithContentDescription("Private photo.png").assertExists()
        compose.onNode(
            SemanticsMatcher("has zoom accessibility actions") {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.CustomActions) &&
                        it.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].size == 3
            }
        ).assertExists()
    }

    @Test
    fun pdfSwipesAndArrowsChangePagesWithoutTreatingZoomedPansAsPageChanges() {
        val file = File(compose.activity.cacheDir, "viewer-test.pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawText("Test page ${index + 1}", 20f, 40f, android.graphics.Paint())
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        val document = SafetyDocument("pdf-test", "owner", "path", "Sample.pdf", "application/pdf", listOf("owner"))
        compose.activity.setContent { OliveTheme { SafetyDocumentViewer(document, file) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pdf_page_number").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf_previous_page").assertIsNotEnabled()
        compose.onNodeWithTag("pdf_page_content").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("pdf_page_number").assertTextEquals("Page 2 of 3")
        compose.onNodeWithTag("pdf_next_page").performClick()
        compose.onNodeWithTag("pdf_page_number").assertTextEquals("Page 3 of 3")
        compose.onNodeWithTag("pdf_next_page").assertIsNotEnabled()
        compose.onNodeWithTag("pdf_page_content").performTouchInput { swipeRight() }
        compose.onNodeWithTag("pdf_page_number").assertTextEquals("Page 2 of 3")
        compose.onNodeWithTag("media_zoom_in").performClick()
        compose.onNodeWithTag("pdf_page_content").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("pdf_page_number").assertTextEquals("Page 2 of 3")
        compose.onNodeWithTag("pdf_previous_page").performClick()
        compose.onNodeWithTag("pdf_page_number").assertTextEquals("Page 1 of 3")
    }
}
