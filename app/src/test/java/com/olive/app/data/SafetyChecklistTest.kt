// Tests the safety checklist test behavior.
package com.olive.app.data

import com.olive.app.ui.shared.*

import org.junit.Assert.*
import org.junit.Test

// Groups the data and actions for safety checklist test.
class SafetyChecklistTest {
    @Test
    fun existingMultilineNotesArePreservedAndDocumentsAreAddedOnFirstConversion() {
        val old = "My destination\nA second detail"
        val items = readSafetyChecklist(old, listOf("Photo ID", "Birth certificate"))
        assertEquals(old, items.first().text)
        assertEquals(3, items.size)
        assertTrue(items.all { !it.checked })
        assertEquals(items, readSafetyChecklist(old, listOf("Photo ID", "Birth certificate")))
    }

    @Test
    // Checks ed state unicode and user edits survive saving.
    fun checkedStateUnicodeAndUserEditsSurviveSaving() {
        val items = listOf(
            SafetyChecklistItem("one", "Passeport / جواز سفر", true),
            SafetyChecklistItem("two", "Birth certificate")
        )
        assertEquals(items, readSafetyChecklist(writeSafetyChecklist(items)))
    }

    @Test
    // Removes d items stay removed including an entirely empty checklist.
    fun removedItemsStayRemovedIncludingAnEntirelyEmptyChecklist() {
        val stored = writeSafetyChecklist(emptyList())
        assertEquals(
            emptyList<SafetyChecklistItem>(),
            readSafetyChecklist(stored, listOf("Photo ID"))
        )
    }

    @Test
    fun ordinaryJsonTextAndUnrecognizedFormatsAreNotDiscarded() {
        for (raw in listOf(
            "{\"personal note\":\"Keep this\"}",
            "{\"oliveSafetyChecklist\":1,\"items\":\"broken\"}"
        )) {
            assertEquals(raw, readSafetyChecklist(raw).single().text)
        }
    }

    @Test
    // Selects ed trusted contact survives plan storage without depending on the profile.
    fun selectedTrustedContactSurvivesPlanStorageWithoutDependingOnTheProfile() {
        val saved =
            safetyPlanContactItem(SafeContact("trusted-id", "Trusted Friend", "555-0100", true))
        val restored =
            readSafetyPlanContact(readSafetyChecklist(writeSafetyChecklist(listOf(saved))).single())
        assertEquals(SafetyPlanContact("trusted-id", "Trusted Friend", "555-0100"), restored)
        assertTrue(safetyChecklistPreview(writeSafetyChecklist(listOf(saved))).contains("Trusted Friend: 555-0100"))
    }
}
