// Tests the safety document viewer UI test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
// Groups the data and actions for safety document viewer UI test.
class SafetyDocumentViewerUiTest {
    @get:Rule
    val compose = createOliveComposeRule()

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
}
