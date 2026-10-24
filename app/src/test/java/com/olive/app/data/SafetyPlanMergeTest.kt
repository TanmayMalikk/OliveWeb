// Tests the safety plan merge test behavior.
package com.olive.app.data

import com.olive.app.ui.shared.*

import org.junit.Assert.*
import org.junit.Test

// Groups the data and actions for safety plan merge test.
class SafetyPlanMergeTest {
    private val old =
        SafetyPlan("owner", "owner", mapOf("primary_destination" to SafetyField("Old", 1)))

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

    @Test
    fun lateResponsesCannotRollBackAnAlreadyMergedRevision() {
        val current = old.copy(fields = mapOf("primary_destination" to SafetyField("Latest", 3)))
        assertEquals(
            "Latest",
            mergeSafetyPlan(current, old, old).fields.getValue("primary_destination").value
        )
    }

    @Test
    fun encryptedRecordSerializationKeepsEmptyEditsAndConflicts() {
        val plan = old.copy(
            fields = mapOf("primary_destination" to SafetyField("", 3, "mine", "", 4)),
            shareAction = "stop"
        )
        assertEquals(plan, SafetyPlan.fromJson(plan.toJson()))
    }
}
