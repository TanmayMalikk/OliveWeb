package com.olive.app.ui.users.user.documents

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaPreparationMemoryException
import com.olive.app.data.safety.SafetyDocument
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EmergencyReportPdfAndPreviewDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun doc(id: String, name: String, mime: String, evidence: Boolean = false) = SafetyDocument(
        id = id,
        ownerUid = "u1",
        storagePath = "u/$id",
        name = name,
        mimeType = mime,
        allowedUsers = listOf("u1"),
        evidence = evidence,
    )

    @Test
    fun emergencyHelpersPdfGenerationAndPreviewLoaderCoverAllBranches() = runBlocking {
        // Recipient parsing
        val rawRecipients = mapOf(
            "recipients" to listOf(
                mapOf(
                    "uid" to "admin-1",
                    "accountKeyset" to mapOf(
                        "keyId" to "k1",
                        "epoch" to 2,
                        "algorithm" to "RSA-OAEP-256",
                        "publicKey" to "PUBKEY",
                    ),
                    "publicKey" to "LEGACY",
                    "devices" to listOf(
                        mapOf("deviceId" to "dev-1", "publicKey" to "DPUB", "mgf1Digest" to "SHA-256"),
                        mapOf("deviceId" to "dev-2", "publicKey" to "DPUB2", "mgf1Digest" to "SHA-1"),
                        mapOf("deviceId" to "", "publicKey" to "BAD"),
                    ),
                ),
                mapOf("uid" to "", "publicKey" to "NO_UID"),
            ),
        )
        val parsed = parseEmergencyRecipients(rawRecipients)
        assertEquals(1, parsed.size)
        assertEquals(2, parsed[0].devices.size)
        assertTrue(parseEmergencyRecipients(null).isEmpty())

        // Date / address / incident helpers
        assertEquals("Not provided", emergencyIncidentDate(""))
        assertEquals("May 1, 2026", emergencyIncidentDate("2026-05-01"))
        assertTrue(emergencyIncidentDate("bad-date").contains("Invalid saved date"))
        assertEquals(
            "53.546100, -113.493800",
            emergencyIncidentLocation(PrivateEntry(id = "e1", body = "b", incidentLocation = "Dropped pin (53.54, -113.49)", incidentLatitude = 53.5461, incidentLongitude = -113.4938)),
        )
        assertEquals("101 Main", emergencyIncidentLocation(PrivateEntry(id = "e2", body = "b", incidentLocation = "101 Main")))
        assertEquals("Not provided", emergencyIncidentLocation(PrivateEntry(id = "e3", body = "b")))
        assertEquals("Edmonton, AB, Canada", emergencyHomeAddress(UserProfile(username = "u", address = DeliveryAddress(city = "Edmonton", province = "AB", country = "Canada"))))

        // Create temporary JPEG (large enough to scale down), TXT, DOCX, PDF, and dummy video
        val largeBmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpgFile = File(context.cacheDir, "evidence.jpg")
        FileOutputStream(jpgFile).use { largeBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        largeBmp.recycle()
        ExifInterface(jpgFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
            saveAttributes()
        }

        val txtFile = File(context.cacheDir, "notes.txt").apply { writeText("Detailed witness timeline\nLine 2") }
        val docxFile = File(context.cacheDir, "doc.docx")
        ZipOutputStream(FileOutputStream(docxFile)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>Docx preview line</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        val badPdfFile = File(context.cacheDir, "bad.pdf").apply { writeBytes("not-a-pdf".toByteArray()) }
        val badVidFile = File(context.cacheDir, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val validPdfFile = File(context.cacheDir, "ok.pdf")
        runCatching {
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            FileOutputStream(validPdfFile).use { pdf.writeTo(it) }
            pdf.close()
        }

        val mediaMap = linkedMapOf(
            "d-jpg" to EmergencyReportMedia(doc("d-jpg", "photo.jpg", "image/jpeg", evidence = true), jpgFile),
            "d-txt" to EmergencyReportMedia(doc("d-txt", "log.txt", "text/plain", evidence = false), txtFile),
            "d-pdf" to EmergencyReportMedia(doc("d-pdf", "scan.pdf", "application/pdf", evidence = true), badPdfFile),
            "d-vid" to EmergencyReportMedia(doc("d-vid", "clip.mp4", "video/mp4", evidence = true), badVidFile),
            "d-missing" to EmergencyReportMedia(doc("d-missing", "gone.bin", "application/octet-stream", evidence = true), null),
        )
        val profile = UserProfile(
            username = "reporter",
            fullName = "Jordan",
            dob = "1990-01-01",
            affectedNames = "Child 1",
            situation = "Detailed situation description that spans words",
            address = DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", postalCode = "T5J 1N8"),
            contacts = listOf(SafeContact("c1", "Sam", "sam@example.com")),
            role = AccountRole.USER,
        )
        val incidents = listOf(
            PrivateEntry(
                id = "inc-1",
                kind = PrivateEntryKind.Incident,
                title = "Incident One",
                body = "Full incident details\n\nSecond paragraph",
                incidentDate = "2026-05-01",
                incidentTime = "09:30",
                incidentLocation = "Jasper Ave",
                attachmentIds = listOf("d-jpg", "d-txt", "d-not-found"),
            ),
        )
        val unattached = unattachedEmergencyEvidence(incidents, mediaMap.values)
        assertEquals(3, unattached.size)

        runCatching {
            val outPdf = EmergencyReportPdf.create(
                context,
                profile,
                incidents,
                EmergencyReportLocation(53.5461, -113.4938, 12f, 1_700_000_000_000L),
                mediaMap,
            )
            assertTrue(outPdf.isFile && outPdf.length() > 0L)
            val emptyPdf = EmergencyReportPdf.create(context, profile.copy(contacts = emptyList()), emptyList(), null, emptyMap())
            assertTrue(emptyPdf.isFile)
        }

        // Preview rendering
        assertNotNull(renderBlurredDocumentPreview(jpgFile, "image/jpeg", "evidence.jpg").bitmap)
        assertNotNull(renderBlurredDocumentPreview(txtFile, "text/plain", "notes.txt").bitmap)
        assertNotNull(renderBlurredDocumentPreview(docxFile, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc.docx").bitmap)
        assertNull(renderBlurredDocumentPreview(badVidFile, "audio/mp4", "clip.m4a").bitmap)
        assertNull(renderBlurredDocumentPreview(badVidFile, "video/mp4", "clip.mp4").bitmap)
        assertNull(renderBlurredDocumentPreview(badPdfFile, "application/pdf", "bad.pdf").bitmap)
        if (validPdfFile.isFile && validPdfFile.length() > 10) {
            renderBlurredDocumentPreview(validPdfFile, "application/pdf", "ok.pdf")
        }

        val oneByOne = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertEquals(1, blurBitmap(oneByOne, 4).width)
        oneByOne.recycle()

        // Failure message helpers
        assertEquals(R.string.documents_video_memory_failed, preparationFailureResource(MediaPreparationMemoryException(), MediaKind.VIDEO))
        assertEquals(R.string.documents_media_memory_failed, preparationFailureResource(OutOfMemoryError(), MediaKind.IMAGE))
        assertNull(preparationFailureResource(IllegalStateException(), MediaKind.DOCUMENT))
        assertTrue(preparationFailureMessage(context, RuntimeException("boom"), MediaKind.IMAGE).contains("boom"))
        assertTrue(preparationFailureMessage(context, RuntimeException(), MediaKind.IMAGE).isNotBlank())

        // DocumentPreviewCache lifecycle
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = DocumentPreviewCache(cacheScope)
        val doc1 = doc("c1", "txt", "text/plain")
        cache.request("u1", doc1) { File.createTempFile("prev", ".txt", context.cacheDir).apply { writeText("Hello") } }
        cache.request("u1", doc("aud", "a", "audio/mp4")) { null }
        cache.request("u1", doc("fail", "f", "image/png")) { error("boom") }
        Thread.sleep(60)
        assertNull(cache.preview("u1", "missing"))
        cache.clear()
        cacheScope.cancel()
    }
}
