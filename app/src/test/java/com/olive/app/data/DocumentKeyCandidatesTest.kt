package com.olive.app.data

import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.documentKeyCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentKeyCandidatesTest {
    @Test
    fun `current device envelope is preferred before account fallback`() {
        val document = document(
            accountKeys = mapOf("owner" to "account-envelope"),
            deviceKeys = mapOf("owner--phone-b" to "device-envelope"),
        )

        assertEquals(
            listOf("device-envelope", "account-envelope"),
            documentKeyCandidates(document, "owner", "phone-b"),
        )
    }

    @Test
    fun `account envelope remains available when this device has no copy`() {
        val document = document(
            accountKeys = mapOf("owner" to "account-envelope"),
            deviceKeys = mapOf("owner--phone-a" to "other-device-envelope"),
        )

        assertEquals(
            listOf("account-envelope"),
            documentKeyCandidates(document, "owner", "phone-b"),
        )
    }

    @Test
    fun `candidate selection does not cross account boundaries`() {
        val document = document(
            accountKeys = mapOf("other" to "other-account-envelope"),
            deviceKeys = mapOf("other--phone-b" to "other-device-envelope"),
        )

        assertTrue(documentKeyCandidates(document, "owner", "phone-b").isEmpty())
    }

    @Test
    fun `blank and duplicate envelopes are removed`() {
        val document = document(
            accountKeys = mapOf("owner" to "same-envelope"),
            deviceKeys = mapOf(
                "owner--phone-a" to "",
                "owner--phone-b" to "same-envelope",
            ),
        )

        assertEquals(
            listOf("same-envelope"),
            documentKeyCandidates(document, "owner", "phone-a"),
        )
        assertEquals(
            listOf("same-envelope"),
            documentKeyCandidates(document, "owner", "phone-b"),
        )
    }

    private fun document(
        accountKeys: Map<String, String>,
        deviceKeys: Map<String, String>,
    ) = SafetyDocument(
        id = "media-1",
        ownerUid = "owner",
        storagePath = "",
        name = "Encrypted document",
        mimeType = "application/octet-stream",
        allowedUsers = accountKeys.keys.toList(),
        wrappedKeys = accountKeys,
        deviceWrappedKeys = deviceKeys,
    )
}
