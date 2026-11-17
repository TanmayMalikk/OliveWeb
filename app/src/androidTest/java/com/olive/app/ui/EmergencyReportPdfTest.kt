package com.olive.app.ui

import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.users.user.documents.EmergencyReportLocation
import com.olive.app.ui.users.user.documents.EmergencyReportMedia
import com.olive.app.ui.users.user.documents.EmergencyReportPdf
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion = 35)
class EmergencyReportPdfTest {
    @Test
    fun reportIncludesContactsIncidentsAndEvidenceWithEmbeddedPdfPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = UserProfile(
            "fixture_owner", "Synthetic owner", "", "", "2000-01-01", "English", "", "", "",
            contacts = listOf(SafeContact("contact", "Test contact", "Synthetic contact details")),
        )
        val incident = PrivateEntry(
            title = "Synthetic incident",
            body = "Synthetic details " + "long incident description ".repeat(220),
            kind = PrivateEntryKind.Incident,
            attachmentIds = listOf("attached"),
        )
        val note = incident.copy(kind = PrivateEntryKind.Note, title = "Private note should be omitted")
        val text = File.createTempFile("report-evidence-", ".txt", context.cacheDir).apply {
            writeText("Unattached synthetic evidence contents")
        }
        val attachment = File.createTempFile("report-attachment-", ".pdf", context.cacheDir)
        var report: File? = null
        try {
            writeAttachment(attachment)
            report = EmergencyReportPdf.create(
                context, profile, listOf(incident, note),
                EmergencyReportLocation(53.0, -113.0, 15f, 1_700_000_000_000),
                mapOf(
                    "attached" to media("attached", "Synthetic attachment", "application/pdf", attachment, false),
                    "evidence" to media("evidence", "Evidence file", "text/plain", text, true),
                    "ordinary" to media("ordinary", "Ordinary document should be omitted", "text/plain", text, false),
                ),
            )
            PdfRenderer(ParcelFileDescriptor.open(report, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                val contents = (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page -> page.textContents.joinToString(" ") { it.text } }
                }
                assertTrue(contents.first().contains("Synthetic contact details"))
                assertTrue(contents.first().contains("53.000000"))
                assertTrue(contents.any { it.contains("Synthetic incident") })
                assertTrue(contents.any { it.contains("continued") })
                assertTrue(contents.any { it.contains("Attached PDF - page 2 of 2") })
                assertTrue(contents.last().contains("Unattached synthetic evidence contents"))
                assertFalse(contents.any { it.contains("should be omitted") })
            }
        } finally {
            report?.delete()
            text.delete()
            attachment.delete()
        }
    }

    private fun media(id: String, name: String, type: String, file: File, evidence: Boolean) =
        EmergencyReportMedia(SafetyDocument(id, "owner", "", name, type, listOf("owner"), evidence = evidence), file)

    private fun writeAttachment(file: File) {
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 200, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
    }
}
