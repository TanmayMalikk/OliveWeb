// Tests that private-entry history syncs for safe-app users.
package com.olive.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateEntryHistorySyncTest {

    // Inputs: the values shown below. Expected: test offline history sync correctly parses PrivateEntry records.
    @Test
    fun `test offline history sync correctly parses PrivateEntry records`() {
        val entry = PrivateEntry(
            id = "entry-123",
            title = "Test Title",
            body = "Test Body"
        )

        val record = HistoryRecord("private_entries", entry.id, entry.toJson().toString())

        // The JSON round trip must keep the private entry fields.
        val parsedJson = JSONObject(record.json)
        val decodedEntry = PrivateEntry.fromJson(parsedJson)

        assertEquals("entry-123", decodedEntry.id)
        assertEquals("Test Title", decodedEntry.title)
        assertEquals("Test Body", decodedEntry.body)
    }
}
