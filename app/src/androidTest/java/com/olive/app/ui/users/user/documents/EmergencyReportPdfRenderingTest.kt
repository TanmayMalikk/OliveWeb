// Emergency PDF device test: uses Android's real PDF engine to create and check a sample report.
package com.olive.app.ui.users.user.documents

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.UserProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmergencyReportPdfRenderingTest {
    // A complete profile and one incident should create a real PDF that starts with the PDF file header.
    @Test
    fun completeProfileAndIncidentCreateAReadablePdfFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profile = UserProfile(
            username = "demo_user",
            fullName = "Demo User",
            affectedNames = "Another household member",
            abuserName = "Example Name",
            dob = "2000-01-01",
            language = "English",
            situation = "Sample situation text used to check report wrapping and spacing.",
            region = "Alberta",
            triggerHash = "hash",
            address = DeliveryAddress(
                line1 = "1 Olive Way",
                city = "Edmonton",
                province = "Alberta",
                postalCode = "T1T 1T1",
                country = "Canada",
            ),
        )
        val incident = PrivateEntry(
            kind = PrivateEntryKind.Incident,
            title = "Sample incident",
            body = "This sample confirms that each incident description is included in the report.",
            incidentDate = "2026-11-02",
            incidentTime = "13:30",
            incidentLocation = "1 Olive Way, Edmonton, Alberta",
        )

        val generated = EmergencyReportPdf.create(
            context,
            profile,
            listOf(incident),
            EmergencyReportLocation(53.5461, -113.4938, 8f, 1_793_568_600_000L),
            emptyMap(),
        )
        assertTrue(generated.parentFile == File(context.cacheDir, "emergency-reports"))
        val preview = File(context.getExternalFilesDir(null), "emergency-report-sample.pdf")
        generated.copyTo(preview, overwrite = true)

        assertTrue(preview.length() > 1_000)
        assertTrue(preview.inputStream().use { input -> String(input.readNBytes(4)) } == "%PDF")
    }
}
