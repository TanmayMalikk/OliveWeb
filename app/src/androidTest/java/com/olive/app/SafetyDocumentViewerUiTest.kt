package com.olive.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.SafetyDocumentViewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SafetyDocumentViewerUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun imageOpensInsidePrivateViewer() {
        val file=File(compose.activity.cacheDir,"viewer-test.png")
        Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        val document=SafetyDocument("test","owner","path","Private photo.png","image/png",listOf("owner"))
        compose.activity.setContent { OliveTheme { SafetyDocumentViewer(document,file) {} } }
        compose.onNodeWithText("Private in-app viewer").assertExists()
        compose.onNodeWithContentDescription("Private photo.png").assertExists()
    }
}
