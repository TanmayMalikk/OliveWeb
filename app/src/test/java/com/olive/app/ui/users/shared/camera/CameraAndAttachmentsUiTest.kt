package com.olive.app.ui.users.shared.camera

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.media.MediaKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ocr.OcrResult
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.TestPdfDocumentShadow
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CameraAndAttachmentsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun tempImage(): File = File.createTempFile("cam-test", ".png").apply {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(this).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    @Test
    fun cameraScreenPermissionPromptAndGrantedModesRender() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Camera, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    CameraScreen(handle.session)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.grant_camera_permission)).assertExists()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        }
    }

    @Test
    fun cameraScreenWithPermissionGrantedSwitchesModes() {
        val shadowApp = Shadows.shadowOf(context)
        shadowApp.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Camera, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    CameraScreen(handle.session)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("camera_mode_video").performClick()
            compose.onNodeWithTag("camera_mode_document").performClick()
            compose.onNodeWithTag("camera_mode_photo").performClick()
            compose.onNodeWithTag("camera_shutter").assertExists()
        }
    }

    @Test
    fun cameraReviewAndViewModelHelpersCoverOcrAndSavingBranches() {
        val img = tempImage()
        val media = CapturedMedia(
            file = img,
            mimeType = "image/jpeg",
            name = "scan.jpg",
            kind = MediaKind.DOCUMENT,
            ocrResult = OcrResult(success = true, extractedText = "Lease Agreement", confidence = 0.52f),
            previewFile = img,
        )
        var retakes = 0
        var saves = 0

        compose.setContent {
            OliveUiTestHost {
                CameraReview(
                    media = media,
                    controllerLoading = false,
                    uploadError = "Upload interrupted",
                    onRetake = { retakes++ },
                    onSave = { saves++ },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("camera_ocr_low_confidence").assertExists()
        compose.onNodeWithTag("camera_upload_error").assertExists()
        compose.onNodeWithTag("camera_retake").performClick()
        compose.onNodeWithTag("camera_save").performClick()
        assertEquals(1, retakes)
        assertEquals(1, saves)

        assertTrue(isLowOcrConfidence(OcrResult(false, "", 0.9f)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "hi", Float.NaN)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "hi", 0.5f)))
        assertFalse(isLowOcrConfidence(OcrResult(true, "hi", 0.85f)))

        val vm = CameraViewModel(context)
        val owner = object : LifecycleOwner {
            private val reg = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
            override val lifecycle get() = reg
        }
        vm.bind(owner, PreviewView(context))
        vm.flip(owner, PreviewView(context))
        vm.zoomIn()
        vm.zoomOut()
        vm.zoomBy(1.1f)
        vm.capturePhoto(img) {}
        vm.captureDocument(img) { _, _, _ -> }
        val pdfOut = File.createTempFile("cam-pdf", ".pdf")
        convertDocumentPhotoToPdf(img, pdfOut)
        assertTrue(pdfOut.length() > 0L)
        assertTrue(builtInCameraFileName(BuiltInCameraFile.DOCUMENT).endsWith(".pdf"))
        Thread.sleep(50)
    }

    @Test
    fun chatAttachmentPickerOpensCategories() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Messages, localMode = false).use { handle ->
            handle.session.runtime.state.privateEntries = listOf(
                PrivateEntry(id = "n-1", kind = PrivateEntryKind.Note, title = "Key Info", body = "Details", timestamp = 1000L),
                PrivateEntry(id = "i-1", kind = PrivateEntryKind.Incident, title = "Incident One", body = "Location notes", timestamp = 2000L, incidentDate = "2026-11-20"),
            )
            kotlinx.coroutines.runBlocking {
                handle.session.documents.rememberRecoveredDocuments(
                    "owner",
                    listOf(SafetyDocument("d-1", "owner", "users/owner/media/d-1", "doc.jpg", "image/jpeg", listOf("owner"), 1000L)),
                )
            }

            compose.setContent {
                OliveUiTestHost(locationGranted = true) {
                    AttachmentPicker(session = handle.session, onDismiss = {})
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.share_attachment)).assertExists()
        }
    }
}
