package com.olive.app.ui

import com.olive.app.data.HistoryRecord
import com.olive.app.data.PrivateEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHistorySyncTest {

    @Test
    fun `test offline history sync correctly parses PrivateEntry records`() {
        // Create a fake offline PrivateEntry
        val entry = PrivateEntry(
            id = "entry-123",
            title = "Test Title",
            body = "Test Body"
        )

        val record = HistoryRecord("private_entries", entry.id, entry.toJson().toString())

        // Simply parsing it to ensure our JSON mapping matches the object model
        val parsedJson = JSONObject(record.json)
        val decodedEntry = PrivateEntry.fromJson(parsedJson)

        assertEquals("entry-123", decodedEntry.id)
        assertEquals("Test Title", decodedEntry.title)
        assertEquals("Test Body", decodedEntry.body)
    }
}
