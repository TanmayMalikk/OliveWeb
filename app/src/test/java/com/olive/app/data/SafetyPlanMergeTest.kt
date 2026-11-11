// Tests offline safety-plan edits and conflict merging for users.
package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class SafetyPlanMergeTest {
    private val old =
        SafetyPlan("owner", "owner", mapOf("primary_destination" to SafetyField("Old", 1)))

    // Inputs: the values shown below. Expected: new prompts upgrade only untouched local placeholders.
    @Test
    fun newPromptsUpgradeOnlyUntouchedLocalPlaceholders() {
        val blank = writeSafetyChecklist(emptyList())
        val prompts =
            writeSafetyChecklist(listOf(SafetyChecklistItem("where", "Choose somewhere safe")))
        val original = mapOf(
            "primary_destination" to SafetyField(blank),
            "primary_travel" to SafetyField("My ride", 0, "draft"),
            "primary_contacts" to SafetyField(blank, 2),
            "primary_aftercare" to SafetyField(blank, 0, "deleted")
        )
        val defaults = original.keys.associateWith { prompts }
        val migrated = safetyFieldsWithDefaults(original, defaults)
        assertEquals(prompts, migrated.getValue("primary_destination").value)
        assertNotNull(migrated.getValue("primary_destination").mutationId)
        original.filterKeys { it != "primary_destination" }
            .forEach { (key, value) -> assertEquals(value, migrated[key]) }
        assertEquals(migrated, safetyFieldsWithDefaults(migrated, defaults))
    }

    // Inputs: the values shown below. Expected: independent remote change is accepted without discarding another pending section.
    @Test
    fun independentRemoteChangeIsAcceptedWithoutDiscardingAnotherPendingSection() {
        val local = old.copy(
            fields = old.fields + ("backup_destination" to SafetyField(
                "Draft",
                0,
                "pending"
            ))
        )
        val remote =
            old.copy(fields = mapOf("primary_destination" to SafetyField("Staff edit", 2, "staff")))
        val merged = mergeSafetyPlan(local, local, remote)
        assertEquals("Staff edit", merged.fields.getValue("primary_destination").value)
        assertEquals("Draft", merged.fields.getValue("backup_destination").value)
        assertTrue(merged.hasEdits)
    }

    // Inputs: the values shown below. Expected: competing offline edits keep local and remote versions.
    @Test
    fun competingOfflineEditsKeepLocalAndRemoteVersions() {
        val local =
            old.copy(fields = mapOf("primary_destination" to SafetyField("My draft", 1, "mine")))
        val remote = old.copy(
            fields = mapOf(
                "primary_destination" to SafetyField(
                    "Staff draft",
                    2,
                    "staff"
                )
            )
        )
        val merged = mergeSafetyPlan(local, local, remote).fields.getValue("primary_destination")
        assertEquals("My draft", merged.value)
        assertEquals("Staff draft", merged.conflictValue)
        assertEquals(2L, merged.conflictRevision)
    }

    @Test
    fun staleStaffEditStaysQueuedUntilItReachesTheOwner() {
        val key = "primary_destination"
        val staff = old.copy(fields = mapOf(key to SafetyField("Staff draft", 1, "staff-draft")))
        val remote = old.copy(fields = mapOf(key to SafetyField("Owner edit", 2, "owner-edit")))
        val queued = mergeSafetyPlan(staff, staff, remote, ownerEditing = false)
        assertTrue(queued.pending)
        assertNull(queued.fields.getValue(key).conflictRevision)

        val pending = remote.copy(pendingConflicts = mapOf(key to SafetyConflict("Staff draft", "staff-draft")))
        val acknowledged = mergeSafetyPlan(queued, queued, pending, ownerEditing = false)
        assertFalse(acknowledged.pending)
        assertEquals("Owner edit", acknowledged.fields.getValue(key).value)
        assertEquals("Staff draft", acknowledged.pendingConflicts.getValue(key).value)

        val resolved = remote.copy(resolvedConflicts = mapOf(key to listOf("staff-draft")))
        assertFalse(mergeSafetyPlan(queued, queued, resolved, ownerEditing = false).pending)
        assertEquals(resolved, SafetyPlan.fromJson(resolved.toJson()))
    }

    @Test
    fun finishedUploadAddsOnlyTheMatchingPlanAttachment() {
        val field = "backup_documents"
        val checklist = writeSafetyChecklist(listOf(
            SafetyChecklistItem("passport", "Passport", excludedAttachmentIds = listOf("removed")),
        ))
        val matching = SafetyDocument("uploaded", "owner", "", "Passport", "application/pdf", listOf("owner"),
            tags = listOf(safetyChecklistAttachmentTag(field, "passport")))
        val unrelated = matching.copy(id = "unrelated", tags = listOf("Other"))
        val updated = safetyChecklistWithPublishedDocuments(field, checklist, listOf(matching, unrelated, matching.copy(id = "removed")))
        assertEquals(listOf("uploaded"), readSafetyChecklist(updated).single().attachmentIds)
        assertEquals(updated, safetyChecklistWithPublishedDocuments(field, updated, listOf(matching)))
        assertEquals(checklist, safetyChecklistWithPublishedDocuments("primary_documents", checklist, listOf(matching)))
    }

    // Inputs: the values shown below. Expected: acknowledged edits clear the queue but newer typing survives.
    @Test
    fun acknowledgedEditsClearTheQueueButNewerTypingSurvives() {
        val sent = old.copy(fields = mapOf("primary_destination" to SafetyField("Sent", 1, "sent")))
        val remote =
            old.copy(fields = mapOf("primary_destination" to SafetyField("Sent", 2, "sent")))
        assertFalse(mergeSafetyPlan(sent, sent, remote).hasEdits)
        val current =
            sent.copy(fields = mapOf("primary_destination" to SafetyField("New typing", 1, "new")))
        val merged = mergeSafetyPlan(current, sent, remote).fields.getValue("primary_destination")
        assertEquals("New typing", merged.value)
        assertEquals(2L, merged.revision)
        assertEquals("new", merged.mutationId)
        assertNull(merged.conflictRevision)
    }

    // Inputs: the values shown below. Expected: late responses cannot roll back an already merged revision.
    @Test
    fun lateResponsesCannotRollBackAnAlreadyMergedRevision() {
        val current = old.copy(fields = mapOf("primary_destination" to SafetyField("Latest", 3)))
        assertEquals(
            "Latest",
            mergeSafetyPlan(current, old, old).fields.getValue("primary_destination").value
        )
    }

    // Inputs: the values shown below. Expected: encrypted record serialization keeps empty edits and conflicts.
    @Test
    fun encryptedRecordSerializationKeepsEmptyEditsAndConflicts() {
        val plan = old.copy(
            fields = mapOf("primary_destination" to SafetyField("", 3, "mine", "", 4)),
            shareAction = "stop"
        )
        assertEquals(plan, SafetyPlan.fromJson(plan.toJson()))
    }
}
