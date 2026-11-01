// Tests user checklist edits, saved checks, and old note conversion.
package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class SafetyChecklistTest {

    // Stores SQL-like punctuation as a checklist sentence and expects an exact text round trip with no execution.
    @Test
    fun injectionLikeChecklistTextRemainsPlainUserData() {
        val injectionText = "'); DROP TABLE safety_plans; --"
        val saved = writeSafetyChecklist(listOf(SafetyChecklistItem("item", injectionText, false)))

        assertEquals(injectionText, readSafetyChecklist(saved).single().text)
    }
    // Inputs: the values shown below. Expected: existing multiline notes are preserved and documents are added on first conversion.
    @Test
    fun existingMultilineNotesArePreservedAndDocumentsAreAddedOnFirstConversion() {
        val old = "My destination\nA second detail"
        val items = readSafetyChecklist(old, listOf("Photo ID", "Birth certificate"))
        assertEquals(old, items.first().text)
        assertEquals(3, items.size)
        assertTrue(items.all { !it.checked })
        assertEquals(items, readSafetyChecklist(old, listOf("Photo ID", "Birth certificate")))
    }

    // Inputs: the values shown below. Expected: checked state unicode and user edits survive saving.
    @Test
    fun checkedStateUnicodeAndUserEditsSurviveSaving() {
        val items = listOf(
            SafetyChecklistItem("one", "Passeport / جواز سفر", true),
            SafetyChecklistItem("two", "Birth certificate")
        )
        assertEquals(items, readSafetyChecklist(writeSafetyChecklist(items)))
    }

    // Inputs: the values shown below. Expected: removed items stay removed including an entirely empty checklist.
    @Test
    fun removedItemsStayRemovedIncludingAnEntirelyEmptyChecklist() {
        val stored = writeSafetyChecklist(emptyList())
        assertEquals(
            emptyList<SafetyChecklistItem>(),
            readSafetyChecklist(stored, listOf("Photo ID"))
        )
    }

    // Inputs: the values shown below. Expected: ordinary json text and unrecognized formats are not discarded.
    @Test
    fun ordinaryJsonTextAndUnrecognizedFormatsAreNotDiscarded() {
        for (raw in listOf(
            "{\"personal note\":\"Keep this\"}",
            "{\"oliveSafetyChecklist\":1,\"items\":\"broken\"}"
        )) {
            assertEquals(raw, readSafetyChecklist(raw).single().text)
        }
    }

    // Inputs: the values shown below. Expected: selected trusted contact survives plan storage without depending on the profile.
    @Test
    fun selectedTrustedContactSurvivesPlanStorageWithoutDependingOnTheProfile() {
        val saved =
            safetyPlanContactItem(SafeContact("trusted-id", "Trusted Friend", "555-0100", true))
        val restored =
            readSafetyPlanContact(readSafetyChecklist(writeSafetyChecklist(listOf(saved))).single())
        assertEquals(SafetyPlanContact("trusted-id", "Trusted Friend", "555-0100"), restored)
        assertTrue(safetyChecklistPreview(writeSafetyChecklist(listOf(saved))).contains("Trusted Friend: 555-0100"))
    }

    // Inputs: the values shown below. Expected: duplicate ids wrong field types and oversized lists fall back without crashing.
    @Test
    fun duplicateIdsWrongFieldTypesAndOversizedListsFallBackWithoutCrashing() {
        val duplicate = """{"oliveSafetyChecklist":1,"items":[
            {"id":"same","text":"One","checked":false},
            {"id":"same","text":"Two","checked":true}
        ]}""".trimIndent()
        assertEquals(duplicate, readSafetyChecklist(duplicate).single().text)

        val wrongTypes = """{"oliveSafetyChecklist":1,"items":[{"id":1,"text":2,"checked":"yes"}]}"""
        assertEquals(wrongTypes, readSafetyChecklist(wrongTypes).single().text)

        val tooMany = org.json.JSONObject().put("oliveSafetyChecklist", 1).put(
            "items",
            org.json.JSONArray().also { rows ->
                repeat(81) { index ->
                    rows.put(org.json.JSONObject()
                        .put("id", index.toString())
                        .put("text", "Item $index")
                        .put("checked", false))
                }
            },
        ).toString()
        assertEquals(tooMany, readSafetyChecklist(tooMany).single().text)
    }

    // Inputs: the values shown below. Expected: malformed trusted contact rows are never shown as valid contacts.
    @Test
    fun malformedTrustedContactRowsAreNeverShownAsValidContacts() {
        assertNull(readSafetyPlanContact(SafetyChecklistItem("ordinary", "{}")))
        assertNull(readSafetyPlanContact(SafetyChecklistItem("trusted-contact:id", "not json")))
        assertNull(readSafetyPlanContact(SafetyChecklistItem("trusted-contact:id", "{\"name\":\"Missing details\"}")))
    }
}
