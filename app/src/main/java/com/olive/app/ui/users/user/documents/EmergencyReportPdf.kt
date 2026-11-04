// Safe-app users: builds the emergency PDF that combines profile details, incidents, and evidence.
package com.olive.app.ui.users.user.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.olive.app.data.PrivateEntry
import com.olive.app.data.SafetyDocument
import com.olive.app.data.UserProfile
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

internal data class EmergencyReportLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAt: Long,
)

internal data class EmergencyReportMedia(
    val document: SafetyDocument,
    val file: File?,
)

// Trashed records are left out because the user has already chosen to remove them from active notes.
internal fun emergencyIncidents(entries: List<PrivateEntry>) = entries.filter {
    it.kind == com.olive.app.data.PrivateEntryKind.Incident && it.deletedAt == null && it.purgedAt == null
}.sortedWith(
    compareBy<PrivateEntry> { it.incidentDate.ifBlank { "9999" } }
        .thenBy { it.incidentTime }
        .thenBy { it.timestamp },
)

// A complete street address is preferred, with city and region used when the street was not saved.
internal fun emergencyHomeAddress(profile: UserProfile): String {
    val address = profile.address
    val parts = if (address.line1.isNotBlank()) {
        listOf(
            address.line1,
            address.line2,
            address.city,
            address.province,
            address.postalCode,
            address.country,
        )
    } else {
        listOf(address.city, address.province, profile.region, address.country)
    }
    return parts.filter(String::isNotBlank).distinct().joinToString(", ").ifBlank { "Not provided" }
}

// Typed addresses stay readable; map-only incidents use the exact stored pin coordinates.
internal fun emergencyIncidentLocation(incident: PrivateEntry): String {
    val coordinates = if (incident.incidentLatitude != null && incident.incidentLongitude != null) {
        emergencyCoordinates(incident.incidentLatitude, incident.incidentLongitude)
    } else ""
    val location = incident.incidentLocation.trim()
    return when {
        location.isBlank() -> coordinates.ifBlank { "Not provided" }
        location.startsWith("Dropped pin", ignoreCase = true) && coordinates.isNotBlank() -> coordinates
        else -> location
    }
}

// Evidence already linked to any incident is not repeated in the final standalone-evidence section.
internal fun unattachedEmergencyEvidence(
    incidents: List<PrivateEntry>,
    media: Collection<EmergencyReportMedia>,
): List<EmergencyReportMedia> {
    val attachedIds = incidents.flatMapTo(linkedSetOf()) { it.attachmentIds }
    return media.filter { it.document.evidence && it.document.id !in attachedIds }
}

private fun emergencyCoordinates(latitude: Double, longitude: Double) =
    "${"%.6f".format(Locale.CANADA, latitude)}, ${"%.6f".format(Locale.CANADA, longitude)}"

/** Creates a readable PDF and saves it in Olive Branch private app storage. */
internal object EmergencyReportPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val CONTENT_BOTTOM = 790f

    // The report follows a fixed order so a reader can find profile facts before incident evidence.
    fun create(
        context: Context,
        profile: UserProfile,
        incidents: List<PrivateEntry>,
        pressedLocation: EmergencyReportLocation?,
        media: Map<String, EmergencyReportMedia>,
    ): File {
        val directory = File(context.cacheDir, "emergency-reports").apply { mkdirs() }
        // Remove abandoned temporary reports before creating the PDF that will be encrypted into Documents.
        directory.listFiles { file -> file.name.startsWith("emergency-report-") && file.extension == "pdf" }
            ?.forEach(File::delete)
        val output = File(directory, "emergency-report-${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val writer = ReportWriter(pdf)
        try {
            writer.startPage("Emergency information report")
            writer.paragraph("Created ${formatTimestamp(System.currentTimeMillis())}", muted = true)
            writer.paragraph(
                "This report contains private profile information and evidence selected by the account owner.",
                muted = true,
            )
            writer.section("User information")
            writer.field("Name", profile.fullName.ifBlank { "Not provided" })
            writer.field("Username", profile.username.ifBlank { "Not provided" })
            writer.field("Date of birth", profile.dob.ifBlank { "Not provided" })
            writer.field("Abuser name", profile.abuserName.ifBlank { "Not provided" })
            if (profile.affectedNames.isNotBlank()) {
                writer.field("Other victims or household members", profile.affectedNames)
            }
            writer.field("Home address", emergencyHomeAddress(profile))
            writer.field("Emergency button coordinates", pressedLocationText(pressedLocation))
            writer.field("Situation", profile.situation.ifBlank { "Not provided" })

            val orderedIncidents = emergencyIncidents(incidents)
            if (orderedIncidents.isEmpty()) {
                writer.section("Incident reports")
                writer.paragraph("No incident reports were saved on this account.")
            }
            orderedIncidents.forEachIndexed { index, incident ->
                writer.startPage("Incident report ${index + 1} of ${orderedIncidents.size}")
                writer.field("Title", incident.title.ifBlank { "Untitled incident" })
                writer.field("Date of incident", incident.incidentDate.ifBlank { "Not provided" })
                writer.field("Approximate time", incident.incidentTime.ifBlank { "Not provided" })
                writer.field("Location of incident", emergencyIncidentLocation(incident))
                writer.section("Description")
                writer.paragraph(incident.body.ifBlank { "No description was saved." })
                writer.section("Attached media")
                if (incident.attachmentIds.isEmpty()) {
                    writer.paragraph("No media was attached to this incident.")
                } else {
                    incident.attachmentIds.distinct().forEach { mediaId ->
                        writer.media(media[mediaId], "Incident ${index + 1} attachment")
                    }
                }
            }

            val unattachedEvidence = unattachedEmergencyEvidence(orderedIncidents, media.values)
            writer.startPage("Other evidence")
            if (unattachedEvidence.isEmpty()) {
                writer.paragraph("No additional evidence was tagged outside an incident report.")
            } else {
                writer.paragraph(
                    "These files were marked as evidence but were not attached to an incident report.",
                    muted = true,
                )
                unattachedEvidence.forEach { writer.media(it, "Unattached evidence") }
            }
            writer.finish()
            FileOutputStream(output).use(pdf::writeTo)
            return output
        } finally {
            writer.closeOpenPage()
            pdf.close()
        }
    }

    // The button location includes accuracy and capture time so coordinates are not presented without context.
    private fun pressedLocationText(location: EmergencyReportLocation?): String {
        location ?: return "Unavailable - location permission or a GPS fix was not available."
        val accuracy = location.accuracyMeters?.let { " (accuracy about ${it.toInt()} m)" }.orEmpty()
        return "${emergencyCoordinates(location.latitude, location.longitude)}$accuracy\nCaptured ${formatTimestamp(location.capturedAt)}"
    }

    private fun formatTimestamp(timestamp: Long): String = DateTimeFormatter
        .ofPattern("MMM d, yyyy 'at' h:mm a z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

    /** Draws flowing text and media previews without allowing content to overlap the page footer. */
    private class ReportWriter(private val document: PdfDocument) {
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 51, 31)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        private val bold = Paint(body).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        private val heading = Paint(bold).apply { textSize = 21f }
        private val section = Paint(bold).apply { textSize = 15f }
        private val muted = Paint(body).apply { color = Color.rgb(91, 105, 77); textSize = 9.5f }
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNumber = 0
        private var y = MARGIN

        // Every major report section starts on a clean page with the same heading and footer layout.
        fun startPage(title: String) {
            closeOpenPage()
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
            )
            canvas = page!!.canvas
            y = MARGIN
            drawWrapped(title, heading, 28f)
            line()
        }

        fun closeOpenPage() {
            val open = page ?: return
            canvas?.drawText("Olive Branch emergency report - page $pageNumber", MARGIN, 822f, muted)
            document.finishPage(open)
            page = null
            canvas = null
        }

        fun finish() = closeOpenPage()

        // A section heading separates profile, description, attachment, and evidence content.
        fun section(value: String) {
            ensure(35f)
            y += 10f
            drawWrapped(value, section, 22f)
        }

        // Labels are bold while their values can wrap across as many pages as needed.
        fun field(label: String, value: String) {
            ensure(42f)
            drawWrapped(label, bold, 16f)
            drawWrapped(value, body, 16f)
            y += 6f
        }

        fun paragraph(value: String, muted: Boolean = false) {
            drawWrapped(value, if (muted) this.muted else body, 16f)
            y += 6f
        }

        // Media gets a labelled preview page; unsupported files remain listed by name and type.
        fun media(value: EmergencyReportMedia?, group: String) {
            if (value == null) {
                paragraph("An attached file could not be found in this account.")
                return
            }
            startPage(group)
            field("File", value.document.name)
            field("Type", value.document.mimeType.ifBlank { "Unknown" })
            field("Evidence tag", if (value.document.evidence) "Evidence" else "Not marked as evidence")
            val file = value.file
            if (file == null || !file.isFile) {
                paragraph("The encrypted file could not be downloaded while this report was created.")
                return
            }
            when {
                value.document.mimeType.startsWith("image/") -> drawBitmap(decodeBounded(file))
                value.document.mimeType.startsWith("video/") -> drawVideoPreview(file)
                value.document.mimeType == "application/pdf" || file.extension.equals("pdf", true) -> drawPdf(file)
                value.document.mimeType.startsWith("text/") -> drawTextPreview(file)
                else -> paragraph("This file type cannot be displayed inside a PDF. Its encrypted account record remains available in Documents.")
            }
        }

        // Large images are sampled before drawing so an emergency export cannot exhaust device memory.
        private fun decodeBounded(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
            return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private fun drawVideoPreview(file: File) {
            val retriever = MediaMetadataRetriever()
            val frame = runCatching {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            retriever.release()
            paragraph("Video preview. The PDF cannot contain a playable copy of the encrypted video.", muted = true)
            drawBitmap(frame)
        }

        // Existing PDF attachments are rendered page-by-page into the emergency report.
        private fun drawPdf(file: File) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        repeat(renderer.pageCount) { index ->
                            renderer.openPage(index).use { source ->
                                startPage("Attached PDF - page ${index + 1} of ${renderer.pageCount}")
                                val width = 1000
                                val height = ceil(width * source.height.toDouble() / source.width).toInt()
                                    .coerceIn(1, 1500)
                                val bitmap = createBitmap(width, height)
                                bitmap.eraseColor(Color.WHITE)
                                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                drawBitmap(bitmap)
                            }
                        }
                    }
                }
            }.onFailure { paragraph("The attached PDF preview could not be rendered.") }
        }

        private fun drawTextPreview(file: File) {
            val text = runCatching { file.bufferedReader().use { it.readText().take(50_000) } }.getOrNull()
            if (text == null) paragraph("The attached text file could not be read.")
            else {
                section("Text preview")
                paragraph(text)
            }
        }

        private fun drawBitmap(bitmap: Bitmap?) {
            if (bitmap == null) {
                paragraph("A preview could not be created for this file.")
                return
            }
            val availableWidth = PAGE_WIDTH - MARGIN * 2
            val availableHeight = CONTENT_BOTTOM - y
            val scale = min(availableWidth / bitmap.width, availableHeight / bitmap.height)
            if (scale <= 0f) {
                startPage("Media preview")
                drawBitmap(bitmap)
                return
            }
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            canvas!!.drawBitmap(bitmap, null, RectF(MARGIN, y, MARGIN + width, y + height), null)
            y += height + 10f
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        private fun line() {
            ensure(16f)
            canvas!!.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, muted)
            y += 16f
        }

        // Long text is broken on word boundaries and continues on a new page when required.
        private fun drawWrapped(value: String, paint: Paint, lineHeight: Float) {
            val maximum = PAGE_WIDTH - MARGIN * 2
            value.replace("\r", "").split("\n").forEach { paragraph ->
                if (paragraph.isBlank()) {
                    ensure(lineHeight)
                    y += lineHeight
                    return@forEach
                }
                var remaining = paragraph.trim()
                while (remaining.isNotEmpty()) {
                    ensure(lineHeight)
                    var count = paint.breakText(remaining, true, maximum, null).coerceAtLeast(1)
                    if (count < remaining.length) {
                        val breakAt = remaining.substring(0, count).lastIndexOf(' ')
                        if (breakAt > 0) count = breakAt
                    }
                    val line = remaining.substring(0, count).trim()
                    canvas!!.drawText(line, MARGIN, y + paint.textSize, paint)
                    y += lineHeight
                    remaining = remaining.substring(count).trimStart()
                }
            }
        }

        private fun ensure(height: Float) {
            if (page == null) startPage("Emergency information report")
            if (y + height > CONTENT_BOTTOM) startPage("Emergency information report - continued")
        }
    }
}
