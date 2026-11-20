package com.olive.app.security

import com.olive.app.data.account.*
import com.olive.app.support.TestAndroidKeyStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceActivityStoreTest {
    @Test
    fun purgingAnIncidentErasesSensitiveFieldsAndAnOldCloudCopyCannotResurrectIt() {
        withStore { store ->
            val deleted = PrivateEntry(
                id = "incident", timestamp = 1_000, deletedAt = 2_000, kind = PrivateEntryKind.Incident,
                title = "Synthetic title", body = "Synthetic description", incidentDate = "2020-01-01",
                incidentTime = "15:25", incidentLocation = "Synthetic location",
                incidentLatitude = 53.0, incidentLongitude = -113.0, attachmentIds = listOf("evidence-id"),
                edits = listOf(edit("edit", 1_500, "Original", "Synthetic description")),
            )
            store.savePrivateEntry("owner", deleted)
            val prePurge = store.pendingHistory("owner").single()
            val deadline = 2_000 + PRIVATE_ENTRY_TRASH_RETENTION_MS
            assertFalse(store.purgeExpiredPrivateEntries("owner", deadline - 1))
            assertTrue(store.purgeExpiredPrivateEntries("owner", deadline))
            assertFalse(store.purgeExpiredPrivateEntries("owner", deadline + 1))
            store.markHistorySynced("owner", prePurge)
            store.mergeHistory("owner", listOf(prePurge))
            assertTrue(store.privateEntries("owner").isEmpty())
            val record = store.pendingHistory("owner").single()
            val tombstone = PrivateEntry.fromJson(JSONObject(record.json))
            assertEquals(
                PrivateEntry(id = deleted.id, timestamp = deleted.timestamp, updatedAt = deadline,
                    title = "", body = "", deletedAt = deleted.deletedAt, purgedAt = deadline),
                tombstone,
            )
            store.markHistorySynced("owner", record)
            assertTrue(store.pendingHistory("owner").isEmpty())
        }
    }

    @Test
    fun mergingHistoryKeepsLocalEditsAndShowsAnAcceptedPinOnlyAfterAFailedAttempt() {
        withStore { store ->
            val clean = TriggerEvent(id = "clean", source = "fixture")
            store.recordTrigger("owner", clean)
            store.recordTriggerAttempt("owner", clean.id, true)
            assertEquals(clean, store.triggerHistory("owner").single())
            store.recordTriggerAttempt("owner", clean.id, false)
            store.mergeHistory("owner", listOf(HistoryRecord("triggers", clean.id, clean.toJson().toString())))
            assertEquals("Incorrect PIN", store.triggerHistory("owner").single().lastResult)
            store.recordTriggerAttempt("owner", clean.id, true)
            assertEquals("Accepted", store.triggerHistory("owner").single().lastResult)
            assertEquals(2, store.triggerHistory("owner").single().pinAttempts)
            assertTrue(store.triggerHistory("another-account").isEmpty())

            val first = edit("first", 2000, "A", "B")
            val second = edit("second", 3000, "B", "C")
            val local = PrivateEntry(id = "note", timestamp = 1000, updatedAt = 2000,
                title = "Local note", body = "B", edits = listOf(first))
            val remote = local.copy(updatedAt = 3000, body = "C", edits = listOf(second))
            store.savePrivateEntry("owner", local)
            store.mergeHistory("owner", listOf(HistoryRecord("private_entries", remote.id, remote.toJson().toString())))
            val merged = store.privateEntries("owner").single()
            assertEquals("C", merged.body)
            assertEquals(listOf(first, second), merged.edits)
            assertTrue(store.pendingHistory("owner").any { it.id == remote.id })
        }
    }

    private fun edit(id: String, timestamp: Long, before: String, after: String) =
        PrivateEntryEdit(id, timestamp, listOf(FieldChange("Body", before, after)))

    private fun withStore(test: (DeviceActivityStore) -> Unit) {
        TestAndroidKeyStore.install().use {
            test(DeviceActivityStore(EncryptedVault(RuntimeEnvironment.getApplication(), "test-history")))
        }
    }
}
