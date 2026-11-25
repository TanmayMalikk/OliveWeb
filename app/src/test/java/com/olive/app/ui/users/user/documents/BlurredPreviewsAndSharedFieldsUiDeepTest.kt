package com.olive.app.ui.users.user.documents

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.media.MediaKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.shared.AddressFields
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class BlurredPreviewsAndSharedFieldsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun blurredPreviewSurfaceStorageGridAttachmentStripAndAddressFieldsRenderAllVariants() {
        compose.mainClock.autoAdvance = false
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val preview = DocumentPreview(bmp, MediaKind.IMAGE, ready = true)
        val docs = listOf(
            SafetyDocument("d-img", "owner", "p1", "photo.jpg", "image/jpeg", listOf("owner"), evidence = true, objectKey = "p1"),
            SafetyDocument("d-vid", "owner", "p2", "clip.mp4", "video/mp4", listOf("owner"), objectKey = "p2"),
            SafetyDocument("d-pdf", "owner", "p3", "report.pdf", "application/pdf", listOf("owner"), objectKey = "p3"),
            SafetyDocument("d-aud", "owner", "p4", "voice.mp3", "audio/mpeg", listOf("owner"), objectKey = "p4"),
            SafetyDocument("d-doc", "owner", "p5", "notes.bin", "application/octet-stream", listOf("owner"), objectKey = "p5"),
        )
        val selected = mutableListOf<SafetyDocument>()
        createUiTestSession(localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    Column {
                        BlurredDocumentPreviewSurface(
                            document = docs[0],
                            preview = preview,
                            loading = false,
                            showEvidenceBadge = true,
                            offlineCached = true,
                            opening = true,
                            extraCountBadge = 2,
                            modifier = Modifier.size(120.dp),
                        )
                        BlurredDocumentPreviewSurface(
                            document = docs[1],
                            preview = null,
                            loading = true,
                            showEvidenceBadge = false,
                            modifier = Modifier.size(80.dp),
                        )
                        BlurredDocumentPreviewSurface(document = docs[2], preview = null, loading = false, modifier = Modifier.size(80.dp))
                        BlurredDocumentPreviewSurface(document = docs[3], preview = null, loading = false, modifier = Modifier.size(80.dp))
                        BlurredDocumentPreviewSurface(document = docs[4], preview = null, loading = false, modifier = Modifier.size(80.dp))
                        NoteAttachmentPreviewStrip(
                            attachmentIds = listOf("d-img", "d-vid", "d-pdf", "d-aud", "d-doc"),
                            viewerUid = "owner",
                            controller = handle.session.documents,
                        )
                        OliveStoragePreviewGrid(
                            documents = docs.take(2),
                            controller = handle.session.documents,
                            onSelect = { selected += it },
                        )
                        AddressFields(
                            value = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
                            cityOnlyRequired = false,
                            cityError = "Required",
                            shakeTrigger = 1,
                            change = {},
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithText("+2").assertExists()
            compose.onNodeWithTag("olive_storage_d-img").performClick()
            compose.mainClock.advanceTimeBy(100)
            assertTrue(selected.any { it.id == "d-img" })
        }
    }
}
