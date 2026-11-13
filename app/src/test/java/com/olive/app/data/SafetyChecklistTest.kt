// Tests user checklist edits, saved checks, and old note conversion.
package com.olive.app.data

import com.olive.app.data.account.SafeContact
import com.olive.app.data.safety.SafetyChecklistItem
import com.olive.app.data.safety.SafetyChecklistChange
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyPlanContact
import com.olive.app.data.safety.readSafetyChecklist
import com.olive.app.data.safety.readSafetyPlanContact
import com.olive.app.data.safety.safetyChecklistAttachmentTag
import com.olive.app.data.safety.safetyChecklistAttachments
import com.olive.app.data.safety.safetyChecklistIncomingChanges
import com.olive.app.data.safety.safetyChecklistPreview
import com.olive.app.data.safety.safetyPlanContactItem
import com.olive.app.data.safety.writeSafetyChecklist
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

    @Test
    fun attachmentExclusionsSurviveSavingAndTagsIdentifyOneChecklistRow() {
        val item = SafetyChecklistItem(
            id = "item-id",
            text = "Passport",
            excludedAttachmentIds = listOf("document-1"),
        )

        assertEquals(item, readSafetyChecklist(writeSafetyChecklist(listOf(item))).single())
        assertEquals(
            "safety-plan:primary_documents:item-id",
            safetyChecklistAttachmentTag("primary_documents", "item-id"),
        )
        assertNotEquals(
            safetyChecklistAttachmentTag("primary_documents", "item-id"),
            safetyChecklistAttachmentTag("primary_documents", "other-item"),
        )
        val attached = SafetyDocument(
            id = "document-2",
            ownerUid = "owner",
            storagePath = "",
            name = "Passport.pdf",
            mimeType = "application/pdf",
            allowedUsers = listOf("owner"),
            tags = listOf(safetyChecklistAttachmentTag("primary_documents", "item-id")),
        )
        assertEquals(
            listOf(attached),
            safetyChecklistAttachments("primary_documents", item, listOf(attached)),
        )
        assertTrue(
            safetyChecklistAttachments(
                "primary_documents",
                item.copy(excludedAttachmentIds = listOf(attached.id)),
                listOf(attached),
            ).isEmpty(),
        )
    }

    @Test
    fun savedOliveStorageAttachmentSurvivesSavingWithoutAnUploadTag() {
        val item = SafetyChecklistItem(
            id = "item-id",
            text = "Passport",
            attachmentIds = listOf("saved-document"),
        )
        val restored = readSafetyChecklist(writeSafetyChecklist(listOf(item))).single()
        val savedDocument = SafetyDocument(
            id = "saved-document",
            ownerUid = "owner",
            storagePath = "",
            name = "Existing passport.pdf",
            mimeType = "application/pdf",
            allowedUsers = listOf("owner"),
        )

        assertEquals(listOf("saved-document"), restored.attachmentIds)
        assertEquals(
            listOf(savedDocument),
            safetyChecklistAttachments("primary_documents", restored, listOf(savedDocument)),
        )
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
        assertNull(
            readSafetyPlanContact(
                SafetyChecklistItem(
                    "trusted-contact:id",
                    "{\"name\":\"Missing details\"}"
                )
            )
        )
    }

    @Test
    fun incomingChecklistDiffShowsOnlySemanticChanges() {
        val local = listOf(
            SafetyChecklistItem("license", "Driver's license", checked = false),
            SafetyChecklistItem("medication", "Medication list"),
            SafetyChecklistItem("rename", "Old name"),
            SafetyChecklistItem("attachments", "Passport", excludedAttachmentIds = listOf("old-document")),
            SafetyChecklistItem("unchanged", "Emergency cash"),
        )
        val remote = listOf(
            SafetyChecklistItem("unchanged", "Emergency cash"),
            SafetyChecklistItem("license", "Driver's license", checked = true),
            SafetyChecklistItem("rename", "Passport"),
            SafetyChecklistItem("attachments", "Passport", excludedAttachmentIds = listOf("new-document")),
            SafetyChecklistItem("new", "Phone charger"),
        )

        assertEquals(
            listOf(
                SafetyChecklistChange.CompletionChanged("license", "Driver's license", false, true),
                SafetyChecklistChange.Removed("medication", "Medication list"),
                SafetyChecklistChange.TextChanged("rename", "Old name", "Passport"),
                SafetyChecklistChange.AttachmentsChanged("attachments", "Passport"),
                SafetyChecklistChange.Added("new", "Phone charger", false),
            ),
            safetyChecklistIncomingChanges(local, remote),
        )
    }

    @Test
    fun incomingChecklistDiffIgnoresReorderingAndIdenticalItems() {
        val first = SafetyChecklistItem("first", "Driver's license", checked = true)
        val second = SafetyChecklistItem("second", "Emergency cash")

        assertTrue(safetyChecklistIncomingChanges(listOf(first, second), listOf(second, first)).isEmpty())
    }
}
