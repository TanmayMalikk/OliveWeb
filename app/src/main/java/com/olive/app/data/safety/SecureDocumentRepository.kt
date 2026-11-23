// Safe-app users and support staff: opens encrypted documents for approved viewers.
package com.olive.app.data.safety

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.PublicKey
import javax.crypto.SecretKey
import java.util.UUID
import android.content.Context
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.media.requireIdentity
import com.olive.app.data.network.cloudStringMap
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import kotlinx.serialization.Serializable
import org.json.JSONArray
import kotlin.collections.iterator


class SecureDocumentRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /**
     * Encrypts and uploads a Private Entry or Incident Report for multiple recipients.
     *
     * @param entry The full note or incident report that the recipient will read.
     * @param ownerUid The UID of the creator.
     * @param recipientPublicKeys Map of UID -> RSA PublicKey for everyone who needs access (including the owner).
     */
    suspend fun saveMultiRecipientEntry(
        entry: PrivateEntry,
        ownerUid: String,
        recipientPublicKeys: Map<String, PublicKey>
    ) {
        // Encrypt the full report so chat recipients see its date, time, location, body, and links.
        val payload = entry.toJson().toString()

        val documentRef = firestore.collection("privateEntries").document(entry.id)
        // A first-time read of a missing protected document is denied by Firestore rules.
        // Treat that specific result as a new share; existing owner documents remain readable.
        val existing = try {
            documentRef.get().await()
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw error
        }

        val existingWrapped = existing?.get("wrappedKeys").cloudStringMap().orEmpty()
        val documentKey: SecretKey = existingWrapped[ownerUid]?.let(KeyManager::unwrapKey)
            ?: EncryptionUtils.generateSymmetricKey()

        // 3. Encrypt the payload using the AES key
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)

        // 4. Wrap the AES key using the RSA public key of EVERY recipient
        val wrappedKeysMap = existingWrapped.toMutableMap()
        for ((uid, publicKey) in recipientPublicKeys) {
            wrappedKeysMap[uid] = KeyManager.wrapKey(documentKey, publicKey)
        }

        // 5. Construct the secure cloud entity
        val secureEntry = mapOf(
            "id" to entry.id,
            "ownerUid" to ownerUid,
            "ciphertext" to ciphertext,
            "wrappedKeys" to wrappedKeysMap,
            "allowedUsers" to wrappedKeysMap.keys.toList(),
            "createdAt" to (existing?.get("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        // 6. Upload to Firestore (using set with merge to handle updates cleanly)
        documentRef.set(secureEntry).await()
    }

    /**
     * Revokes a recipient's access to a shared entry.
     * Deletes their wrapped AES key and removes them from the Firestore security rules array.
     *
     * @param entryId Unique identifier of the private entry or note.
     * @param recipientUid Firebase UID of the recipient account.
     */
    suspend fun revokeAccessToEntry(entryId: String, recipientUid: String) {
        firestore.collection("privateEntries").document(entryId).update(
            "wrappedKeys.${recipientUid}", FieldValue.delete(),
            "allowedUsers", FieldValue.arrayRemove(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Retrieves and decrypts a shared Private Entry.
     *
     * @param entryId Unique identifier of the private entry or note.
     * @param currentUid Current user ID supplied to [loadAndDecryptEntry].
     * @return The resulting [PrivateEntry] for load and decrypt entry.
     */
    suspend fun loadAndDecryptEntry(entryId: String, currentUid: String): PrivateEntry {
        val snapshot = firestore.collection("privateEntries").document(entryId).get().await()

        if (!snapshot.exists()) throw Exception("Document not found.")

        val wrappedKeysMap = snapshot.get("wrappedKeys").cloudStringMap()
            ?: throw Exception("Encryption keys missing from document.")

        // 1. Find the current user's specific wrapped key
        val myWrappedKey = wrappedKeysMap[currentUid]
            ?: throw Exception("You do not have the cryptographic key to read this document.")

        // 2. Unwrap the AES key using the hardware Private Key
        val documentKey = KeyManager.unwrapKey(myWrappedKey)

        // 3. Decrypt the ciphertext
        val ciphertext = snapshot.getString("ciphertext") ?: throw Exception("Ciphertext missing.")
        val plaintextJson = EncryptionUtils.decryptText(ciphertext, documentKey)

        // 4. Parse the JSON back into the local data model
        val jsonObject = JSONObject(plaintextJson)
        return if (jsonObject.has("timestamp")) PrivateEntry.fromJson(jsonObject)
        else PrivateEntry(
            id = entryId,
            title = jsonObject.optString("title", "Decryption Error"),
            body = jsonObject.optString("body", "Decryption Error"),
        )
    }

    /**
     * Replaces the non-owner recipient list for a shared private entry, or deletes the cloud copy when
     * all recipients are revoked so revoked contacts immediately lose access.
     */
    suspend fun updateMultiRecipientEntry(
        entry: PrivateEntry,
        ownerUid: String,
        recipientPublicKeys: Map<String, PublicKey>,
    ) {
        val nonOwnerRecipients = recipientPublicKeys.filterKeys { it != ownerUid }
        if (nonOwnerRecipients.isEmpty()) {
            revokeAllRecipients(entry.id, ownerUid)
            return
        }
        val documentRef = firestore.collection("privateEntries").document(entry.id)
        val existing = try {
            documentRef.get().await()
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw error
        }
        val existingWrapped = existing?.get("wrappedKeys").cloudStringMap().orEmpty()
        val documentKey: SecretKey = existingWrapped[ownerUid]?.let { runCatching { KeyManager.unwrapKey(it) }.getOrNull() }
            ?: EncryptionUtils.generateSymmetricKey()
        val payload = entry.toJson().toString()
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)
        val ownerPublicKey = recipientPublicKeys[ownerUid] ?: KeyManager.getOrCreatePublicKey()
        val wrappedKeysMap = linkedMapOf(
            ownerUid to (existingWrapped[ownerUid] ?: KeyManager.wrapKey(documentKey, ownerPublicKey)),
        )
        for ((uid, publicKey) in nonOwnerRecipients) {
            wrappedKeysMap[uid] = KeyManager.wrapKey(documentKey, publicKey)
        }
        val secureEntry = mapOf(
            "id" to entry.id,
            "ownerUid" to ownerUid,
            "ciphertext" to ciphertext,
            "wrappedKeys" to wrappedKeysMap,
            "allowedUsers" to wrappedKeysMap.keys.toList(),
            "createdAt" to (existing?.get("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        documentRef.set(secureEntry).await()
    }

    /**
     * Deletes the shared cloud record for an owned entry when the entry is trashed or all recipients are revoked.
     */
    suspend fun revokeAllRecipients(entryId: String, ownerUid: String) {
        val documentRef = firestore.collection("privateEntries").document(entryId)
        try {
            val snapshot = documentRef.get().await()
            if (snapshot.exists() && snapshot.getString("ownerUid") == ownerUid) {
                documentRef.delete().await()
            }
        } catch (error: FirebaseFirestoreException) {
            if (error.code !in setOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )) throw error
        }
    }

    /**
     * Grants access to any current conversation participants (such as an escalated Admin or newly added friend)
     * who were not yet wrapped on a previously shared private entry.
     */
    suspend fun ensureRecipientsHaveAccess(
        entryId: String,
        callerUid: String,
        recipientPublicKeys: Map<String, PublicKey>,
    ) {
        val documentRef = firestore.collection("privateEntries").document(entryId)
        val snapshot = try {
            documentRef.get().await()
        } catch (error: FirebaseFirestoreException) {
            if (error.code in setOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.NOT_FOUND,
                )) return
            throw error
        }
        val existingAllowed = (snapshot.get("allowedUsers") as? List<*>)
            .orEmpty().filterIsInstance<String>().toSet()
        if (!snapshot.exists() || callerUid !in existingAllowed) return
        val existingWrapped = snapshot.get("wrappedKeys").cloudStringMap().orEmpty()
        val missing = recipientPublicKeys.filterKeys { uid ->
            uid != callerUid && (uid !in existingWrapped || uid !in existingAllowed)
        }
        if (missing.isEmpty()) return
        val callerWrappedKey = existingWrapped[callerUid] ?: return
        val documentKey = runCatching { KeyManager.unwrapKey(callerWrappedKey) }.getOrNull() ?: return
        val updatedWrapped = existingWrapped.toMutableMap()
        for ((uid, publicKey) in missing) {
            updatedWrapped[uid] = KeyManager.wrapKey(documentKey, publicKey)
        }
        documentRef.update(
            mapOf(
                "wrappedKeys" to updatedWrapped,
                "allowedUsers" to updatedWrapped.keys.toList(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /**
     * Streams private entries shared with [currentUid] alongside the recipient map for entries owned by [currentUid].
     */
    internal fun observeUserSharedEntries(
        currentUid: String,
        onUpdate: (PrivateEntriesSnapshot) -> Unit,
        onError: (String) -> Unit = {},
    ): ListenerRegistration {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var job: Job? = null
        val registration = firestore.collection("privateEntries")
            .whereArrayContains("allowedUsers", currentUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                job?.cancel()
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                job = scope.launch {
                    val parsed = withContext(Dispatchers.Default) {
                        decodeSharedEntriesSnapshot(docs, currentUid)
                    }
                    onUpdate(parsed)
                }
            }
        return ListenerRegistration {
            registration.remove()
            scope.cancel()
        }
    }

    /** Unwraps and decrypts shared private entries visible to [currentUid]. */
    private fun decodeSharedEntriesSnapshot(
        documents: List<DocumentSnapshot>,
        currentUid: String,
    ): PrivateEntriesSnapshot {
        val sharedWithMe = mutableListOf<SharedPrivateEntry>()
        val ownedRecipients = linkedMapOf<String, List<String>>()
        for (doc in documents) {
            val ownerUid = doc.getString("ownerUid").orEmpty()
            if (ownerUid.isBlank()) continue
            val allowed = (doc.get("allowedUsers") as? List<*>)
                .orEmpty().filterIsInstance<String>()
            if (currentUid !in allowed) continue
            if (ownerUid == currentUid) {
                val others = allowed.filter { it != currentUid }.distinct()
                if (others.isNotEmpty()) ownedRecipients[doc.id] = others
                continue
            }
            val wrappedKeys = doc.get("wrappedKeys").cloudStringMap() ?: continue
            val wrapped = wrappedKeys[currentUid] ?: continue
            val ciphertext = doc.getString("ciphertext")?.takeIf(String::isNotBlank) ?: continue
            val entry = runCatching {
                val key = KeyManager.unwrapKey(wrapped)
                val plaintext = EncryptionUtils.decryptText(ciphertext, key)
                val json = JSONObject(plaintext)
                if (json.has("timestamp")) PrivateEntry.fromJson(json)
                else PrivateEntry(
                    id = doc.id,
                    title = json.optString("title", ""),
                    body = json.optString("body", ""),
                )
            }.getOrNull() ?: continue
            if (entry.deletedAt == null) {
                sharedWithMe += SharedPrivateEntry(entry = entry, ownerUid = ownerUid)
            }
        }
        sharedWithMe.sortByDescending { it.entry.updatedAt }
        return PrivateEntriesSnapshot(
            sharedWithMe = sharedWithMe,
            ownedSharedRecipients = ownedRecipients,
        )
    }

}

internal data class SharedPrivateEntry(
    val entry: PrivateEntry,
    val ownerUid: String,
)

internal data class PrivateEntriesSnapshot(
    val sharedWithMe: List<SharedPrivateEntry> = emptyList(),
    val ownedSharedRecipients: Map<String, List<String>> = emptyMap(),
)

// Safe-app users and support staff: defines safety-plan fields and conflict data.

internal val SAFETY_SECTIONS = listOf(
    "destination",
    "travel",
    "contacts",
    "essentials",
    "dependants",
    "aftercare",
    "documents",
    "grab",
    "finances",
)
internal val SAFETY_CHECKLIST_SECTIONS =
    listOf("documents", "grab", "destination", "travel", "contacts", "finances", "aftercare")
internal val SAFETY_FIELD_KEYS =
    listOf("primary", "backup").flatMap { plan -> SAFETY_SECTIONS.map { "${plan}_$it" } }

@Serializable
internal data class SafetyField(
    val value: String, val revision: Long = 0, val mutationId: String? = null,
    val conflictValue: String? = null, val conflictRevision: Long? = null,
    val writerId: String? = null, val writerUid: String? = null,
    val clientSequence: Long = 0,
    val baseValue: String? = null,
) {
    /**
     * Safety: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Rebuilds this safety-plan record from protected JSON data.
         *
         * @param json JSON object containing serialized fields.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(json: JSONObject): SafetyField = json.decodeJsonObject<SafetyField>().let {
            it.copy(
                mutationId = it.mutationId?.takeIf(String::isNotEmpty),
                writerId = it.writerId?.takeIf(String::isNotEmpty),
                writerUid = it.writerUid?.takeIf(String::isNotEmpty),
            )
        }
    }
}

@Serializable
internal data class SafetyConflict(val value: String, val mutationId: String)

@Serializable
internal data class SafetyPlan(
    val ownerUid: String,
    val ownerName: String = "",
    val fields: Map<String, SafetyField>,
    val staffUid: String = "",
    val staffName: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val consultationRequested: Boolean = false,
    val shareAction: String = "",
    val safeContactUids: List<String> = emptyList(),
    val safeContactNames: Map<String, String> = emptyMap(),
    val supportSafeContactUid: String = "",
    val consultationConversationId: String = "",
    val pendingConflicts: Map<String, SafetyConflict> = emptyMap(),
    val plansCompletedAt: Long = 0L,
    val resolvedConflicts: Map<String, List<String>> = emptyMap(),
) {
    val hasEdits get() = fields.values.any { it.mutationId != null }
    val hasConflicts get() = pendingConflicts.isNotEmpty() || fields.values.any { it.conflictRevision != null }
    val pending get() = fields.values.any { it.mutationId != null && it.conflictRevision == null } ||
        (shareAction.isNotEmpty() && (shareAction == "stop" || !hasEdits))
    /**
     * Safety: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Rebuilds a safety plan from its encrypted JSON fields.
         *
         * @param json JSON object containing serialized fields.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(json: JSONObject): SafetyPlan {
            val decoded = json.decodeJsonObject<SafetyPlan>()
            return decoded.copy(
                fields = decoded.fields.filterKeys { it in SAFETY_FIELD_KEYS },
                pendingConflicts = decoded.pendingConflicts.filterKeys { it in SAFETY_FIELD_KEYS },
                resolvedConflicts = decoded.resolvedConflicts.filterKeys { it in SAFETY_FIELD_KEYS },
            )
        }
    }
}

/**
 * Never discard a draft because another device or staff member changed the same section.
 *
 * @param current Current supplied to [mergeSafetyPlan].
 * @param sent Sent supplied to [mergeSafetyPlan].
 * @param remote Remote supplied to [mergeSafetyPlan].
 * @param ownerEditing Whether to apply owner editing.
 * @return The resulting [SafetyPlan] for merge safety plan.
 */
internal fun mergeSafetyPlan(
    current: SafetyPlan,
    sent: SafetyPlan,
    remote: SafetyPlan,
    ownerEditing: Boolean = true,
): SafetyPlan {
    val fields = remote.fields.mapValues { (_, field) -> field.copy(mutationId = null) } + current.fields.mapValues { (key, local) ->
        val cloud = remote.fields[key] ?: return@mapValues local
        val checklistMerge = if (local.mutationId != null && local.revision < cloud.revision)
            mergeSafetyChecklist(local.baseValue, local.value, cloud.value) else null
        // Chooses the newer safety-plan value when phone and cloud edits differ.
        when {
            cloud.revision < local.revision -> local
            local.mutationId == null -> cloud.copy(mutationId = null)
            local.mutationId == cloud.mutationId -> cloud.copy(mutationId = null)
            local.mutationId == remote.pendingConflicts[key]?.mutationId -> cloud.copy(mutationId = null)
            local.mutationId in remote.resolvedConflicts[key].orEmpty() -> cloud.copy(mutationId = null)
            local.value == cloud.value && local.revision < cloud.revision -> cloud.copy(mutationId = null)
            sent.fields[key]?.mutationId != null && sent.fields[key]?.mutationId == cloud.mutationId ->
                local.copy(revision = cloud.revision, conflictValue = null, conflictRevision = null,
                    baseValue = cloud.value)

            checklistMerge != null -> {
                if (checklistMerge.localValue == cloud.value && !checklistMerge.conflicted) cloud.copy(mutationId = null)
                else local.copy(
                    value = checklistMerge.localValue,
                    revision = if (checklistMerge.conflicted) local.revision else cloud.revision,
                    baseValue = if (checklistMerge.conflicted) local.baseValue else cloud.value,
                    conflictValue = checklistMerge.remoteValue.takeIf { checklistMerge.conflicted && ownerEditing },
                    conflictRevision = cloud.revision.takeIf { checklistMerge.conflicted && ownerEditing },
                )
            }

            local.writerUid != null && local.writerUid == cloud.writerUid &&
                    local.writerId != null && local.writerId == cloud.writerId &&
                    local.clientSequence > cloud.clientSequence -> local.copy(
                revision = maxOf(local.revision, cloud.revision),
                conflictValue = null,
                conflictRevision = null,
            )

            ownerEditing && local.revision != cloud.revision -> local.copy(
                conflictValue = cloud.value,
                conflictRevision = cloud.revision
            )

            !ownerEditing -> local.copy(conflictValue = null, conflictRevision = null)
            else -> local
        }
    }
    return current.copy(
        fields = fields,
        staffUid = remote.staffUid,
        staffName = remote.staffName,
        adminUid = remote.adminUid,
        adminName = remote.adminName,
        consultationRequested = remote.consultationRequested,
        consultationConversationId = remote.consultationConversationId,
        safeContactUids = remote.safeContactUids,
        safeContactNames = remote.safeContactNames,
        supportSafeContactUid = remote.supportSafeContactUid,
        pendingConflicts = remote.pendingConflicts,
        plansCompletedAt = remote.plansCompletedAt,
        resolvedConflicts = remote.resolvedConflicts,
    )
}

/**
 * Keeps an optimistic on-screen draft above intermediate listener echoes until its latest mutation is acknowledged.
 *
 * @param visible Whether the UI component should be visible, or null when not provided.
 * @param stored Stored supplied to [mergeVisibleSafetyPlan].
 * @param actorUid Firebase UID of the account performing the action.
 * @return The resulting [SafetyPlan] for merge visible safety plan.
 */
internal fun mergeVisibleSafetyPlan(
    visible: SafetyPlan?,
    stored: SafetyPlan,
    @Suppress("UNUSED_PARAMETER") actorUid: String,
): SafetyPlan = visible?.let {
    mergeSafetyPlan(it, it, stored)
} ?: stored

/**
 * Gives the safety plan the safety fields with defaults it needs.
 *
 * @param value Input value to store, parse, or format.
 * @param activeWriterId Active writer ID supplied to [edit].
 * @param activeWriterUid Active writer user ID supplied to [edit].
 * @return The resulting [SafetyField] instance.
 */
internal fun SafetyField.edit(
    value: String,
    activeWriterId: String = UUID.randomUUID().toString(),
    activeWriterUid: String = "",
): SafetyField {
    val activeUid = activeWriterUid.takeIf(String::isNotBlank)
    val nextWriterId = if (mutationId != null) writerId ?: activeWriterId else activeWriterId
    val nextWriterUid = if (mutationId != null) writerUid ?: activeUid else activeUid
    val nextSequence = if (writerId == nextWriterId && writerUid == nextWriterUid) clientSequence + 1 else 1
    return copy(
        value = value,
        mutationId = UUID.randomUUID().toString(),
        writerId = nextWriterId,
        writerUid = nextWriterUid,
        clientSequence = nextSequence,
        baseValue = if (mutationId == null) this.value else baseValue,
    )
}

/**
 * Upgrade only untouched local placeholders. User-cleared checklists, drafts and remote revisions remain intact.
 *
 * @param existing Key-value mapping of existing entries.
 * @param defaults Key-value mapping of defaults entries.
 * @return Map containing the resulting safety fields with defaults entries.
 */
internal fun safetyFieldsWithDefaults(
    existing: Map<String, SafetyField>,
    defaults: Map<String, String>
): Map<String, SafetyField> {
    val fields = defaults.mapValues { SafetyField(it.value) } + existing
    return fields.mapValues { (key, field) ->
        val suggested = defaults[key]
        if (key in existing && key.substringAfter('_') in listOf(
                "destination",
                "travel",
                "contacts",
                "aftercare"
            ) &&
            field.revision == 0L && field.mutationId == null && field.conflictRevision == null &&
            field.value == writeSafetyChecklist(emptyList()) && suggested != null && readSafetyChecklist(
                suggested
            ).isNotEmpty()
        )
            field.edit(suggested) else field
    }
}

internal data class SafetyConsultation(
    val ownerUid: String,
    val ownerName: String,
    val conversationId: String = "",
    val staffUid: String = "",
    val staffName: String = "",
    val ticketNumber: Long = -1L,
)
internal data class SharedSafetyPlan(
    val ownerUid: String,
    val ownerName: String,
    val updatedAt: Long = 0
)


data class IndexedDocuments(
    val documents: List<SafetyDocument> = emptyList(),
    val lastSyncedAt: Long? = null,
)

interface DocumentIndexDataSource {
    /**
     * Reads the document index data source.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [IndexedDocuments] instance.
     */
    fun read(uid: String): IndexedDocuments
    /**
     * Computes replace.
     *
     * @param uid Firebase UID of the target user account.
     * @param documents Collection of documents values to process.
     * @param syncedAt Synced at supplied to [replace].
     * @return The resulting [IndexedDocuments] instance.
     */
    fun replace(uid: String, documents: List<SafetyDocument>, syncedAt: Long): IndexedDocuments
    /**
     * Removes the document index data source.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    fun remove(uid: String, documentId: String)
    /**
     * Purges the document index data source.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun purge(uid: String)
}

/** Keystore-encrypted, account-scoped metadata index; bulk ciphertext is stored separately. */
class DocumentIndexStore(private val vault: JsonVault) : DocumentIndexDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "document-index"))

    /**
     * Reads the document index store.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [IndexedDocuments] instance.
     */
    override fun read(uid: String): IndexedDocuments {
        requireIdentity(uid)
        val account = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?: return IndexedDocuments()
        val rows = account.optJSONObject(DOCUMENTS) ?: JSONObject()
        val documents = rows.keys().asSequence().mapNotNull { id ->
            runCatching { rows.getJSONObject(id).toDocument(uid, id) }.getOrNull()
        }.sortedByDescending(SafetyDocument::createdAt).toList()
        return IndexedDocuments(documents, account.optLong(LAST_SYNCED_AT).takeIf { it > 0 })
    }

    /**
     * Computes replace.
     *
     * @param uid Firebase UID of the target user account.
     * @param documents Collection of documents values to process.
     * @param syncedAt Synced at supplied to [replace].
     * @return The resulting [IndexedDocuments] instance.
     */
    override fun replace(uid: String, documents: List<SafetyDocument>, syncedAt: Long): IndexedDocuments {
        requireIdentity(uid)
        require(syncedAt > 0)
        val accessible = documents.filter { uid in it.allowedUsers }.distinctBy(SafetyDocument::id)
        vault.update { root ->
            val accounts = root.child(ACCOUNTS)
            val rows = JSONObject()
            accessible.forEach { rows.put(it.id, it.toIndexJson(uid)) }
            accounts.put(uid, JSONObject().put(LAST_SYNCED_AT, syncedAt).put(DOCUMENTS, rows))
        }
        return IndexedDocuments(accessible.sortedByDescending(SafetyDocument::createdAt), syncedAt)
    }

    /**
     * Removes the document index store.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    override fun remove(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        runCatching { vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(DOCUMENTS)?.remove(documentId)
        } }.onFailure { vault.clear() }
    }

    /**
     * Purges the document index store.
     *
     * @param uid Firebase UID of the target user account.
     */
    override fun purge(uid: String) {
        requireIdentity(uid)
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
            .onFailure { vault.clear() }
    }

    /**
     * Returns the safe root.
     *
     * @return JSON object representing safe root.
     */
    private fun safeRoot(): JSONObject = try { vault.read() } catch (_: Exception) {
        vault.clear()
        JSONObject()
    }

    private companion object {
        const val ACCOUNTS = "accounts"
        const val DOCUMENTS = "documents"
        const val LAST_SYNCED_AT = "lastSyncedAt"
    }
}

/**
 * Converts safety document into index JSON.
 *
 * @param viewerUid Firebase UID of the viewing account.
 * @return JSON object representing to index JSON.
 */
private fun SafetyDocument.toIndexJson(viewerUid: String): JSONObject {
    requireIdentity(viewerUid, id)
    require(ownerUid.isNotBlank() && ownerUid.length <= MAX_ID_LENGTH)
    require(viewerUid in allowedUsers && allowedUsers.size <= 25)
    require(name.isNotBlank() && name.length <= 512)
    require(mimeType.isNotBlank() && mimeType.length <= 160)
    require(ciphertextSize in 0..MAX_CIPHERTEXT_BYTES)
    require(ciphertextSha256.isBlank() || ciphertextSha256.matches(SHA_256))
    val viewerDeviceKeys = deviceWrappedKeys.filterKeys { it.startsWith("$viewerUid--") }
    require(viewerDeviceKeys.size <= 25 && viewerDeviceKeys.values.all { it.length <= 4096 })
    return JSONObject()
        .put("ownerUid", ownerUid)
        .put("storagePath", storagePath.take(1024))
        .put("name", name)
        .put("mimeType", mimeType)
        .put("allowedUsers", JSONArray(allowedUsers.distinct()))
        .put("createdAt", createdAt)
        .put("objectKey", objectKey.take(1024))
        .put("encryptedMetadata", encryptedMetadata.take(64 * 1024))
        .put("viewerWrappedKey", wrappedKeys[viewerUid].orEmpty())
        .put("viewerAccountWrappedKey", accountWrappedKeys[viewerUid]?.toJson())
        .put("deviceWrappedKeys", JSONObject(viewerDeviceKeys))
        .put("ciphertextSize", ciphertextSize)
        .put("ciphertextSha256", ciphertextSha256.lowercase())
        .put("encryptionVersion", encryptionVersion)
        .put("accountRecoveryReady", accountRecoveryReady)
        .put("evidence", evidence)
        .put("chatOnly", chatOnly)
        .put("tags", JSONArray(tags.distinct().take(20)))
        .apply {
            ocrText?.take(MAX_OCR_TEXT)?.let { put("ocrText", it) }
            ocrConfidence?.takeIf { it.isFinite() && it in 0f..1f }?.let { put("ocrConfidence", it.toDouble()) }
            ocrSuccessful?.let { put("ocrSuccessful", it) }
        }
}

/**
 * Converts JSON object into document.
 *
 * @param viewerUid Firebase UID of the viewing account.
 * @param id ID supplied to [toDocument].
 * @return The resulting [SafetyDocument] for to document.
 */
private fun JSONObject.toDocument(viewerUid: String, id: String): SafetyDocument {
    requireIdentity(viewerUid, id)
    val allowed = getJSONArray("allowedUsers").let { values ->
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.distinct()
    require(viewerUid in allowed && allowed.size <= 25)
    val accountKey = optString("viewerWrappedKey").takeIf(String::isNotBlank)
    val accountWrappedKey = optJSONObject("viewerAccountWrappedKey")?.let {
        AccountKeysetWrappedKey.fromJson(it)
    }
    val deviceKeys = optJSONObject("deviceWrappedKeys")?.let { json ->
        json.keys().asSequence().filter { it.startsWith("$viewerUid--") }
            .associateWith(json::getString)
    }.orEmpty()
    val size = optLong("ciphertextSize")
    val checksum = optString("ciphertextSha256")
    val tags = optJSONArray("tags")?.let { values ->
        (0 until minOf(values.length(), 20)).mapNotNull {
            values.optString(it).trim().take(80).takeIf(String::isNotBlank)
        }
    }.orEmpty().distinct()
    require(size in 0..MAX_CIPHERTEXT_BYTES)
    require(checksum.isBlank() || checksum.matches(SHA_256_LOWER))
    return SafetyDocument(
        id = id,
        ownerUid = getString("ownerUid"),
        storagePath = optString("storagePath"),
        name = getString("name"),
        mimeType = getString("mimeType"),
        allowedUsers = allowed,
        createdAt = optLong("createdAt"),
        ocrText = optString("ocrText").takeIf { has("ocrText") },
        ocrConfidence = optDouble("ocrConfidence").takeIf { has("ocrConfidence") }?.toFloat(),
        ocrSuccessful = optBoolean("ocrSuccessful").takeIf { has("ocrSuccessful") },
        objectKey = optString("objectKey"),
        accountRecoveryReady = optBoolean("accountRecoveryReady"),
        encryptedMetadata = optString("encryptedMetadata"),
        wrappedKeys = accountKey?.let { mapOf(viewerUid to it) }.orEmpty(),
        ciphertextSize = size,
        ciphertextSha256 = checksum,
        encryptionVersion = optInt("encryptionVersion"),
        deviceWrappedKeys = deviceKeys,
        evidence = optBoolean("evidence"),
        tags = tags,
        accountWrappedKeys = accountWrappedKey?.let { mapOf(viewerUid to it) }.orEmpty(),
        chatOnly = optBoolean("chatOnly"),
    )
}

private const val MAX_ID_LENGTH = 160
private const val MAX_OCR_TEXT = 50_000
private const val MAX_CIPHERTEXT_BYTES = 250L * 1024L * 1024L
private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
private val SHA_256_LOWER = Regex("^[0-9a-f]{64}$")
