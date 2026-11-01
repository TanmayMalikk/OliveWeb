// Tests safety-plan state, serialization, defaults, and merge boundaries.
package com.olive.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafetyPlanStateTest {
    private fun plan(
        fields: Map<String, SafetyField> = mapOf("primary_destination" to SafetyField("Home")),
        shareAction: String = "",
    ) = SafetyPlan("owner-1", "Olive User", fields, shareAction = shareAction)

    // Input: clean, edited, shared, and conflicted plans. Expected: each derived state reports only its real condition.
    @Test
    fun derivedStatesDistinguishEditsSharesAndConflicts() {
        val clean = plan()
        assertFalse(clean.hasEdits)
        assertFalse(clean.hasConflicts)
        assertFalse(clean.pending)

        assertTrue(plan(fields = mapOf("primary_destination" to SafetyField("Draft", mutationId = "m1"))).hasEdits)
        assertTrue(plan(shareAction = "share").pending)
        assertTrue(plan(fields = mapOf("primary_destination" to SafetyField("Mine", conflictRevision = 2))).hasConflicts)
    }

    // Input: every participant and safe-contact field. Expected: encrypted JSON round-trips without dropping access data.
    @Test
    fun jsonRoundTripPreservesParticipantsAndSharing() {
        val original = plan().copy(
            staffUid = "staff-1",
            staffName = "Staff Name",
            adminUid = "admin-1",
            adminName = "Admin Name",
            consultationRequested = true,
            shareAction = "share",
            safeContactUids = listOf("safe-1", "safe-2"),
            safeContactNames = mapOf("safe-1" to "First", "safe-2" to "Second"),
            supportSafeContactUid = "safe-2",
        )
        assertEquals(original, SafetyPlan.fromJson(original.toJson()))
    }

    // Input: an older JSON record without optional fields. Expected: safe empty defaults keep it readable.
    @Test
    fun olderJsonWithoutOptionalFieldsStillLoads() {
        val restored = SafetyPlan.fromJson(
            JSONObject()
                .put("ownerUid", "owner-1")
                .put("fields", JSONObject().put("primary_destination", SafetyField("Home").toJson()))
        )
        assertEquals("owner-1", restored.ownerUid)
        assertEquals("Home", restored.fields.getValue("primary_destination").value)
        assertTrue(restored.safeContactUids.isEmpty())
        assertTrue(restored.safeContactNames.isEmpty())
        assertFalse(restored.consultationRequested)
    }

    // Input: an unknown field plus one supported field. Expected: unknown future or hostile keys are not loaded.
    @Test
    fun unknownSafetyFieldKeysAreIgnored() {
        val fields = JSONObject()
            .put("primary_destination", SafetyField("Home").toJson())
            .put("unknown_private_field", SafetyField("Do not load").toJson())
        val restored = SafetyPlan.fromJson(
            JSONObject().put("ownerUid", "owner-1").put("fields", fields)
        )
        assertEquals(setOf("primary_destination"), restored.fields.keys)
    }

    // Input: two edits with the same text. Expected: each edit receives its own mutation identity.
    @Test
    fun repeatedEditsReceiveUniqueMutationIds() {
        val first = SafetyField("Old").edit("New")
        val second = SafetyField("Old").edit("New")
        assertEquals("New", first.value)
        assertNotNull(first.mutationId)
        assertNotEquals(first.mutationId, second.mutationId)
    }

    // Input: a local field absent from the server response. Expected: a partial response cannot erase the draft.
    @Test
    fun partialRemoteResponseCannotEraseLocalFields() {
        val local = plan(
            mapOf(
                "primary_destination" to SafetyField("Home"),
                "backup_destination" to SafetyField("Shelter", mutationId = "draft"),
            )
        )
        val remote = plan(mapOf("primary_destination" to SafetyField("Updated home", revision = 2)))
        val merged = mergeSafetyPlan(local, local, remote)
        assertEquals("Updated home", merged.fields.getValue("primary_destination").value)
        assertEquals("Shelter", merged.fields.getValue("backup_destination").value)
        assertTrue(merged.hasEdits)
    }

    // Input: newer server assignment metadata. Expected: roles update while local field text remains intact.
    @Test
    fun mergeUpdatesAssignmentMetadataWithoutReplacingLocalDraft() {
        val local = plan(mapOf("primary_destination" to SafetyField("My draft", 3, "mine")))
        val remote = plan(mapOf("primary_destination" to SafetyField("Old server", 2))).copy(
            staffUid = "staff-2",
            adminUid = "admin-2",
            consultationRequested = true,
            safeContactUids = listOf("safe-1"),
        )
        val merged = mergeSafetyPlan(local, local, remote)
        assertEquals("My draft", merged.fields.getValue("primary_destination").value)
        assertEquals("staff-2", merged.staffUid)
        assertEquals("admin-2", merged.adminUid)
        assertTrue(merged.consultationRequested)
        assertEquals(listOf("safe-1"), merged.safeContactUids)
    }

    // Input: defaults for absent and customized fields. Expected: only absent keys are added; custom text is untouched.
    @Test
    fun defaultsAddMissingFieldsWithoutReplacingCustomText() {
        val existing = mapOf("primary_destination" to SafetyField("My chosen place", revision = 4))
        val defaults = mapOf(
            "primary_destination" to "Suggested place",
            "backup_destination" to "Suggested backup",
        )
        val result = safetyFieldsWithDefaults(existing, defaults)
        assertEquals("My chosen place", result.getValue("primary_destination").value)
        assertEquals(4L, result.getValue("primary_destination").revision)
        assertEquals("Suggested backup", result.getValue("backup_destination").value)
    }

    // Input: empty safe-contact arrays and maps. Expected: JSON remains structurally valid and restores emptiness.
    @Test
    fun emptySharingCollectionsRoundTripAsEmptyCollections() {
        val restored = SafetyPlan.fromJson(plan().toJson())
        assertTrue(restored.safeContactUids.isEmpty())
        assertTrue(restored.safeContactNames.isEmpty())
        assertEquals("", restored.supportSafeContactUid)
    }
}
