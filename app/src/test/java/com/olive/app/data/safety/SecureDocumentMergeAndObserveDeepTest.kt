package com.olive.app.data.safety

import android.app.Application
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.TestAndroidKeyStore
import java.security.KeyPairGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SecureDocumentMergeAndObserveDeepTest {

    @Test
    fun mergeSafetyPlanAndJsonSerializationCoverAllConflictAndChecklistBranches() {
        val baseChecklist = JSONArray().apply {
            put(JSONObject().put("id", "i1").put("label", "ID").put("checked", false).put("deleted", false))
            put(JSONObject().put("id", "i2").put("label", "Keys").put("checked", false).put("deleted", false))
        }.toString()
        val localChecklist = JSONArray().apply {
            put(JSONObject().put("id", "i1").put("label", "ID").put("checked", true).put("deleted", false))
            put(JSONObject().put("id", "i2").put("label", "Keys").put("checked", false).put("deleted", false))
        }.toString()
        val remoteChecklist = JSONArray().apply {
            put(JSONObject().put("id", "i1").put("label", "ID").put("checked", false).put("deleted", false))
            put(JSONObject().put("id", "i2").put("label", "Keys").put("checked", true).put("deleted", false))
        }.toString()
        val remoteConflictChecklist = JSONArray().apply {
            put(JSONObject().put("id", "i1").put("label", "ID updated").put("checked", false).put("deleted", false))
            put(JSONObject().put("id", "i2").put("label", "Keys").put("checked", true).put("deleted", false))
        }.toString()

        val current = SafetyPlan(
            ownerUid = "owner",
            ownerName = "Alex",
            fields = mapOf(
                "primary_destination" to SafetyField("Local newer rev", revision = 5, mutationId = "m-1"),
                "primary_travel" to SafetyField("No mut", revision = 1, mutationId = null),
                "primary_contacts" to SafetyField("Same mut", revision = 2, mutationId = "m-same"),
                "primary_essentials" to SafetyField("Pending conf", revision = 2, mutationId = "m-pend"),
                "primary_dependants" to SafetyField("Resolved mut", revision = 2, mutationId = "m-res"),
                "primary_aftercare" to SafetyField("Equal val", revision = 2, mutationId = "m-eq"),
                "primary_finances" to SafetyField("Sent match", revision = 2, mutationId = "m-sent"),
                "primary_documents" to SafetyField(localChecklist, revision = 1, mutationId = "m-chk-ok", baseValue = baseChecklist),
                "primary_grab" to SafetyField(localChecklist, revision = 1, mutationId = "m-chk-conf", baseValue = baseChecklist),
                "backup_destination" to SafetyField("Writer seq", revision = 2, mutationId = "m-seq", writerUid = "owner", writerId = "dev1", clientSequence = 9L),
                "backup_travel" to SafetyField("Real conflict", revision = 2, mutationId = "m-real"),
            ),
        )
        val sent = SafetyPlan(
            ownerUid = "owner",
            fields = mapOf("primary_finances" to SafetyField("Sent match", revision = 2, mutationId = "m-cloud-sent")),
        )
        val remote = SafetyPlan(
            ownerUid = "owner",
            fields = mapOf(
                "primary_destination" to SafetyField("Cloud older rev", revision = 3),
                "primary_travel" to SafetyField("Cloud wins clean", revision = 4),
                "primary_contacts" to SafetyField("Same mut", revision = 3, mutationId = "m-same"),
                "primary_essentials" to SafetyField("Server pend", revision = 3, mutationId = "other"),
                "primary_dependants" to SafetyField("Server res", revision = 4, mutationId = "other2"),
                "primary_aftercare" to SafetyField("Equal val", revision = 4, mutationId = "other3"),
                "primary_finances" to SafetyField("Sent match", revision = 3, mutationId = "m-cloud-sent"),
                "primary_documents" to SafetyField(remoteChecklist, revision = 3, mutationId = "other4"),
                "primary_grab" to SafetyField(remoteConflictChecklist, revision = 3, mutationId = "other5"),
                "backup_destination" to SafetyField("Stale writer echo", revision = 2, mutationId = "other6", writerUid = "owner", writerId = "dev1", clientSequence = 4L),
                "backup_travel" to SafetyField("Cloud edit", revision = 5, mutationId = "other7"),
            ),
            pendingConflicts = mapOf("primary_essentials" to SafetyConflict("Server pend", "m-pend")),
            resolvedConflicts = mapOf("primary_dependants" to listOf("m-res")),
            staffUid = "staff-1",
            staffName = "Staff",
            consultationRequested = true,
        )

        val mergedOwner = mergeSafetyPlan(current, sent, remote, ownerEditing = true)
        assertEquals("Local newer rev", mergedOwner.fields["primary_destination"]?.value)
        assertEquals("Cloud wins clean", mergedOwner.fields["primary_travel"]?.value)
        assertNull(mergedOwner.fields["primary_contacts"]?.mutationId)
        assertNull(mergedOwner.fields["primary_essentials"]?.mutationId)
        assertNull(mergedOwner.fields["primary_dependants"]?.mutationId)
        assertNull(mergedOwner.fields["primary_aftercare"]?.mutationId)
        assertEquals(3L, mergedOwner.fields["primary_finances"]?.revision)
        assertNotNull(mergedOwner.fields["primary_documents"]?.value)
        assertNotNull(mergedOwner.fields["primary_grab"]?.conflictValue)
        assertEquals("Writer seq", mergedOwner.fields["backup_destination"]?.value)
        assertEquals("Cloud edit", mergedOwner.fields["backup_travel"]?.conflictValue)
        assertTrue(mergedOwner.hasConflicts)

        val mergedStaff = mergeSafetyPlan(current, sent, remote, ownerEditing = false)
        assertNull(mergedStaff.fields["backup_travel"]?.conflictValue)

        assertEquals("Cloud wins clean", mergeVisibleSafetyPlan(null, remote, "owner").fields["primary_travel"]?.value)
        assertNotNull(mergeVisibleSafetyPlan(current, remote, "owner"))

        val roundTrip = SafetyPlan.fromJson(mergedOwner.toJson())
        assertEquals("owner", roundTrip.ownerUid)
        assertFalse(roundTrip.fields.isEmpty())
    }

    @Test
    fun observeAndUpdateAndRevokeSharedEntriesWorkAcrossDevicesAndErrorCases() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val driver = FirestoreTestDriver()
            val repo = SecureDocumentRepository(driver.db)
            val ownerPub = KeyManager.getOrCreatePublicKey()
            val recovery = AccountRecoveryKey.generate()
            val keyset = AccountKeysetEnvelope.create("reader", recovery)
            val unlocked = keyset.unlock("reader", recovery)
            val stalePeer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

            driver.records["accountKeyDirectories/reader"] = mapOf(
                "ownerUid" to "reader",
                "keyId" to keyset.keyId,
                "epoch" to keyset.epoch,
                "algorithm" to "RSA-OAEP-256",
                "publicKey" to keyset.publicKeyBase64,
            )

            // Save entry owned by "owner", shared with "reader", then update & revoke
            val entry = PrivateEntry(id = "n-shared", kind = PrivateEntryKind.Note, title = "Secret", body = "Initial")
            repo.saveMultiRecipientEntry(entry, "owner", mapOf("owner" to ownerPub, "reader" to stalePeer))
            repo.updateMultiRecipientEntry(entry.copy(body = "Edited"), "owner", mapOf("owner" to ownerPub, "reader" to stalePeer))
            val unlocker = repo.loadAndDecryptEntry("n-shared", "reader") { unlocked }
            assertEquals("Edited", unlocker.body)

            // Observe as owner -> populates ownedSharedRecipients
            val snapshots = mutableListOf<PrivateEntriesSnapshot>()
            val regOwner = repo.observeUserSharedEntries("owner", { snapshots += it })
            driver.emit("privateEntries")
            val deadline = System.nanoTime() + 3_000_000_000L
            while (snapshots.isEmpty() && System.nanoTime() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                delay(15)
            }
            assertTrue(snapshots.last().ownedSharedRecipients["n-shared"].orEmpty().contains("reader"))
            regOwner.remove()

            // Revoke all recipients deletes the shared cloud copy
            repo.revokeAllRecipients("n-shared", "owner")
            assertFalse(driver.records.containsKey("privateEntries/n-shared"))

            // Direct observe decode: owned entry, deleted entry skipped, legacy note without timestamp
            val aesKey = EncryptionUtils.generateSymmetricKey()
            val wrapped = KeyManager.wrapKey(aesKey, ownerPub)
            val legacyJson = JSONObject().put("title", "Legacy").put("body", "Text").toString()
            val deletedJson = PrivateEntry(id = "n-del", title = "Del", body = "Gone", deletedAt = 50L).toJson().toString()
            driver.records["privateEntries/n-legacy"] = mapOf(
                "ownerUid" to "other",
                "allowedUsers" to listOf("other", "owner"),
                "ciphertext" to EncryptionUtils.encryptText(legacyJson, aesKey),
                "wrappedKeys" to mapOf("owner" to wrapped),
            )
            driver.records["privateEntries/n-deleted"] = mapOf(
                "ownerUid" to "other",
                "allowedUsers" to listOf("other", "owner"),
                "ciphertext" to EncryptionUtils.encryptText(deletedJson, aesKey),
                "wrappedKeys" to mapOf("owner" to wrapped),
            )
            val received = mutableListOf<PrivateEntriesSnapshot>()
            var errMsg = ""
            val regReader = repo.observeUserSharedEntries("owner", { received += it }, { errMsg = it })
            driver.emit("privateEntries")
            val deadline2 = System.nanoTime() + 3_000_000_000L
            while ((received.isEmpty() || received.last().sharedWithMe.none { it.entry.id == "n-legacy" }) && System.nanoTime() < deadline2) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                delay(15)
            }
            assertTrue(received.last().sharedWithMe.any { it.entry.id == "n-legacy" && it.entry.title == "Legacy" })
            assertFalse(received.last().sharedWithMe.any { it.entry.id == "n-deleted" })
            driver.fail("privateEntries", FirebaseFirestoreException("perm denied", FirebaseFirestoreException.Code.PERMISSION_DENIED))
            assertTrue(errMsg.contains("perm denied"))
            regReader.remove()
        }
    }
}
