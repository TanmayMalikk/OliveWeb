// Emergency PDF tests: checks which profile, incident, and evidence details enter the report.
package com.olive.app.ui.users.user.documents

import com.olive.app.data.DeliveryAddress
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.SafetyDocument
import com.olive.app.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyReportPolicyTest {
    // Input: one full address and one city-only profile. Expected: the most useful available address is shown.
    @Test
    fun addressUsesStreetWhenPresentAndFallsBackToCityRegion() {
        val complete = profile(
            address = DeliveryAddress(
                line1 = "1 Olive Way",
                line2 = "Unit 2",
                city = "Edmonton",
                province = "Alberta",
                postalCode = "T1T 1T1",
                country = "Canada",
            ),
        )
        val regional = profile(
            region = "Central Alberta",
            address = DeliveryAddress(city = "Red Deer", country = "Canada"),
        )

        assertEquals("1 Olive Way, Unit 2, Edmonton, Alberta, T1T 1T1, Canada", emergencyHomeAddress(complete))
        assertEquals("Red Deer, Central Alberta, Canada", emergencyHomeAddress(regional))
    }

    // Input: notes, active incidents, and deleted incidents. Expected: only active incidents remain in date order.
    @Test
    fun reportIncludesEveryActiveIncidentOnceInDateOrder() {
        val entries = listOf(
            incident("later", "2026-11-02", timestamp = 3),
            PrivateEntry(title = "note", body = "not an incident", kind = PrivateEntryKind.Note),
            incident("deleted", "2026-01-01", timestamp = 1).copy(deletedAt = 9),
            incident("earlier", "2026-10-01", timestamp = 2),
        )

        assertEquals(listOf("earlier", "later"), emergencyIncidents(entries).map(PrivateEntry::title))
    }

    // Input: a typed address and a map-only incident. Expected: addresses stay readable and dropped pins use exact coordinates.
    @Test
    fun incidentLocationKeepsAddressesAndFormatsDroppedPins() {
        val address = incident("address", "2026-11-01").copy(
            incidentLocation = "1 Olive Way, Edmonton, Alberta",
            incidentLatitude = 53.5,
            incidentLongitude = -113.5,
        )
        val pin = incident("pin", "2026-11-02").copy(
            incidentLocation = "Dropped pin",
            incidentLatitude = 53.5461,
            incidentLongitude = -113.4938,
        )

        assertEquals("1 Olive Way, Edmonton, Alberta", emergencyIncidentLocation(address))
        assertEquals("53.546100, -113.493800", emergencyIncidentLocation(pin))
    }

    // Input: incident attachments plus tagged and untagged files. Expected: only unattached evidence appears at the end.
    @Test
    fun finalEvidenceSectionExcludesIncidentAttachments() {
        val incident = incident("one", "2026-11-01").copy(attachmentIds = listOf("attached"))
        val media = listOf(
            reportMedia("attached", evidence = true),
            reportMedia("standalone", evidence = true),
            reportMedia("ordinary", evidence = false),
        )

        val result = unattachedEmergencyEvidence(listOf(incident), media)

        assertEquals(listOf("standalone"), result.map { it.document.id })
        assertTrue(result.all { it.document.evidence })
    }

    private fun incident(title: String, date: String, timestamp: Long = 1) = PrivateEntry(
        timestamp = timestamp,
        updatedAt = timestamp,
        kind = PrivateEntryKind.Incident,
        title = title,
        body = "Incident details",
        incidentDate = date,
        incidentLocation = "Edmonton",
    )

    private fun reportMedia(id: String, evidence: Boolean) = EmergencyReportMedia(
        SafetyDocument(
            id = id,
            ownerUid = "owner",
            storagePath = "files/$id",
            name = "$id.jpg",
            mimeType = "image/jpeg",
            allowedUsers = listOf("owner"),
            evidence = evidence,
        ),
        file = null,
    )

    private fun profile(
        region: String = "Alberta",
        address: DeliveryAddress = DeliveryAddress(),
    ) = UserProfile(
        username = "demo_user",
        fullName = "Demo User",
        affectedNames = "",
        abuserName = "",
        dob = "2000-01-01",
        language = "English",
        situation = "",
        region = region,
        triggerHash = "hash",
        address = address,
    )
}
