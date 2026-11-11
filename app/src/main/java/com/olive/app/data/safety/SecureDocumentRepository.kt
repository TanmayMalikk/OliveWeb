// Safe-app users and support staff: opens encrypted documents for approved viewers.
package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.PublicKey
import javax.crypto.SecretKey
import java.util.UUID
import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import org.json.JSONArray


class SecureDocumentRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /**
     * Encrypts and uploads a Private Entry or Incident Report for multiple recipients.
     *
     * @param entry The full note or incident report that the recipient will read.
     * @param ownerUid The UID of the creator.
     * @param title The plaintext title.
     * @param body The plaintext body.
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

        @Suppress("UNCHECKED_CAST")
        val existingWrapped = existing?.get("wrappedKeys") as? Map<String, String> ?: emptyMap()
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
     */
    suspend fun revokeAccessToEntry(entryId: String, recipientUid: String) {
        firestore.collection("privateEntries").document(entryId).update(
            "wrappedKeys.${recipientUid}", FieldValue.delete(),
            "allowedUsers", FieldValue.arrayRemove(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Grants access to an existing encrypted entry for a new recipient.
     * Extracts the AES key using the owner's private key, wraps it for the recipient, and updates Firestore.
     */
    suspend fun grantAccessToEntry(
        entryId: String,
        ownerUid: String,
        recipientUid: String,
        recipientPublicKey: PublicKey
    ) {
        val documentRef = firestore.collection("privateEntries").document(entryId)
        val snapshot = documentRef.get().await()

        if (!snapshot.exists()) throw Exception("Document not found.")

        @Suppress("UNCHECKED_CAST")
        val wrappedKeys = snapshot.get("wrappedKeys") as? Map<String, String>
            ?: throw Exception("Encryption keys missing.")

        val ownerWrappedKey = wrappedKeys[ownerUid]
            ?: throw Exception("You do not have the cryptographic key to share this document.")

        // 1. Unwrap the AES key using the owner's hardware Private Key
        val documentKey = KeyManager.unwrapKey(ownerWrappedKey)

        // 2. Wrap the AES key using the new recipient's Public Key
        val newRecipientWrappedKey = KeyManager.wrapKey(documentKey, recipientPublicKey)

        // 3. Append the new key and update security rule arrays
        documentRef.update(
            "wrappedKeys.${recipientUid}", newRecipientWrappedKey,
            "allowedUsers", FieldValue.arrayUnion(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Retrieves and decrypts a shared Private Entry.
     */
    suspend fun loadAndDecryptEntry(entryId: String, currentUid: String): PrivateEntry {
        val snapshot = firestore.collection("privateEntries").document(entryId).get().await()

        if (!snapshot.exists()) throw Exception("Document not found.")

        @Suppress("UNCHECKED_CAST")
        val wrappedKeysMap = snapshot.get("wrappedKeys") as? Map<String, String>
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
     * Completely deletes an encrypted entry from the cloud.
     */
    suspend fun deleteEntry(entryId: String) {
        firestore.collection("privateEntries").document(entryId).delete().await()
    }

    /**
     * Retrieves all Private Entries that this user has access to.
     */
    suspend fun fetchMyEntries(currentUid: String): List<PrivateEntry> {
        val snapshot = firestore.collection("privateEntries")
            .whereArrayContains("allowedUsers", currentUid)
            .get()
            .await()

        val entries = mutableListOf<PrivateEntry>()
        for (doc in snapshot.documents) {
            // Use the fallback when fetch my entries is unavailable.
            try {
                // We reuse loadAndDecryptEntry but pass the already fetched snapshot ID
                val decryptedEntry = loadAndDecryptEntry(doc.id, currentUid)
                entries.add(decryptedEntry)
            } catch (_: Exception) { // Skip one unreadable entry so the rest of the private list can still load.
            }
        }
        return entries
    }
}

// Safe-app users and support staff: defines safety-plan fields and conflict data.

internal val SAFETY_SECTIONS = listOf(
    "destination",
    "travel",
    "contacts",
    "essentials",
    "dependants",
    "aftercare",
    "documents",
    "grab"
)
internal val SAFETY_CHECKLIST_SECTIONS =
    listOf("documents", "grab", "destination", "travel", "contacts", "aftercare")
internal val SAFETY_FIELD_KEYS =
    listOf("primary", "backup").flatMap { plan -> SAFETY_SECTIONS.map { "${plan}_$it" } }

internal data class SafetyField(
    val value: String, val revision: Long = 0, val mutationId: String? = null,
    val conflictValue: String? = null, val conflictRevision: Long? = null
) {
    // Safety: Converts this record into JSON.
    fun toJson(): JSONObject =
        JSONObject().put("value", value).put("revision", revision).put("mutationId", mutationId)
            .put("conflictValue", conflictValue).put("conflictRevision", conflictRevision)

    companion object {
        // Rebuilds this safety-plan record from protected JSON data.
        fun fromJson(json: JSONObject) = SafetyField(
            json.getString("value"),
            json.optLong("revision"),
            json.optString("mutationId").takeIf { it.isNotEmpty() },
            json.optString("conflictValue")
                .takeIf { json.has("conflictValue") && !json.isNull("conflictValue") },
            json.optLong("conflictRevision")
                .takeIf { json.has("conflictRevision") && !json.isNull("conflictRevision") })
    }
}

internal data class SafetyConflict(val value: String, val mutationId: String)

internal data class SafetyPlan(
    val ownerUid: String,
    val ownerName: String,
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
    // Safety: Converts this record into JSON.
    fun toJson(): JSONObject = JSONObject().put("ownerUid", ownerUid).put("ownerName", ownerName)
        .put(
            "fields",
            JSONObject().also { json ->
                fields.forEach { (key, field) ->
                    json.put(
                        key,
                        field.toJson()
                    )
                }
            })
        .put("staffUid", staffUid).put("staffName", staffName)
        .put("adminUid", adminUid).put("adminName", adminName)
        .put("consultationRequested", consultationRequested).put("shareAction", shareAction)
        .put("safeContactUids", org.json.JSONArray(safeContactUids))
        .put("safeContactNames", JSONObject(safeContactNames))
        .put("supportSafeContactUid", supportSafeContactUid)
        .put("consultationConversationId", consultationConversationId)
        .put("plansCompletedAt", plansCompletedAt)
        .put("pendingConflicts", JSONObject(pendingConflicts.mapValues { (_, conflict) ->
            JSONObject().put("value", conflict.value).put("mutationId", conflict.mutationId)
        }))
        .put("resolvedConflicts", JSONObject(resolvedConflicts.mapValues { (_, ids) -> org.json.JSONArray(ids) }))

    companion object {
        // Rebuilds a safety plan from its encrypted JSON fields.
        fun fromJson(json: JSONObject): SafetyPlan {
            val fields = json.getJSONObject("fields")
            return SafetyPlan(
                json.getString("ownerUid"),
                json.optString("ownerName"),
                SAFETY_FIELD_KEYS.mapNotNull { key ->
                    fields.optJSONObject(key)?.let { key to SafetyField.fromJson(it) }
                }.toMap(),
                json.optString("staffUid"),
                json.optString("staffName"),
                json.optString("adminUid"),
                json.optString("adminName"),
                json.optBoolean("consultationRequested"),
                json.optString("shareAction"),
                json.optJSONArray("safeContactUids")
                    ?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty(),
                json.optJSONObject("safeContactNames")
                    ?.let { names -> names.keys().asSequence().associateWith(names::getString) }
                    .orEmpty(),
                json.optString("supportSafeContactUid"),
                json.optString("consultationConversationId"),
                json.optJSONObject("pendingConflicts")?.let { conflicts ->
                    SAFETY_FIELD_KEYS.mapNotNull { key ->
                        conflicts.optJSONObject(key)?.let {
                            key to SafetyConflict(it.getString("value"), it.getString("mutationId"))
                        }
                    }.toMap()
                }.orEmpty(),
                json.optLong("plansCompletedAt"),
                json.optJSONObject("resolvedConflicts")?.let { resolved ->
                    SAFETY_FIELD_KEYS.mapNotNull { key ->
                        resolved.optJSONArray(key)?.let { ids ->
                            key to (0 until ids.length()).map(ids::getString)
                        }
                    }.toMap()
                }.orEmpty(),
            )
        }
    }
}

// Never discard a draft because another device or staff member changed the same section.
internal fun mergeSafetyPlan(
    current: SafetyPlan,
    sent: SafetyPlan,
    remote: SafetyPlan,
    ownerEditing: Boolean = true,
): SafetyPlan {
    val fields = remote.fields.mapValues { (_, field) -> field.copy(mutationId = null) } + current.fields.mapValues { (key, local) ->
        val cloud = remote.fields[key] ?: return@mapValues local
        // Chooses the newer safety-plan value when phone and cloud edits differ.
        when {
            cloud.revision < local.revision -> local
            local.mutationId == null -> cloud.copy(mutationId = null)
            local.mutationId == cloud.mutationId -> cloud.copy(mutationId = null)
            local.mutationId == remote.pendingConflicts[key]?.mutationId -> cloud.copy(mutationId = null)
            local.mutationId in remote.resolvedConflicts[key].orEmpty() -> cloud.copy(mutationId = null)
            local.value == cloud.value && local.revision < cloud.revision -> cloud.copy(mutationId = null)
            sent.fields[key]?.mutationId != null && sent.fields[key]?.mutationId == cloud.mutationId ->
                local.copy(revision = cloud.revision, conflictValue = null, conflictRevision = null)

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

// Gives the safety plan the safety fields with defaults it needs.
internal fun SafetyField.edit(value: String) =
    copy(value = value, mutationId = UUID.randomUUID().toString())

// Upgrade only untouched local placeholders. User-cleared checklists, drafts and remote revisions remain intact.
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
    fun read(uid: String): IndexedDocuments
    fun replace(uid: String, documents: List<SafetyDocument>, syncedAt: Long): IndexedDocuments
    fun remove(uid: String, documentId: String)
    fun purge(uid: String)
}

/** Keystore-encrypted, account-scoped metadata index; bulk ciphertext is stored separately. */
class DocumentIndexStore(private val vault: JsonVault) : DocumentIndexDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "document-index"))

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

    override fun remove(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        runCatching { vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(DOCUMENTS)?.remove(documentId)
        } }.onFailure { vault.clear() }
    }

    override fun purge(uid: String) {
        requireIdentity(uid)
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
            .onFailure { vault.clear() }
    }

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
        .put("deviceWrappedKeys", JSONObject(viewerDeviceKeys))
        .put("ciphertextSize", ciphertextSize)
        .put("ciphertextSha256", ciphertextSha256.lowercase())
        .put("encryptionVersion", encryptionVersion)
        .put("evidence", evidence)
        .put("tags", JSONArray(tags.distinct().take(20)))
        .apply {
            ocrText?.take(MAX_OCR_TEXT)?.let { put("ocrText", it) }
            ocrConfidence?.takeIf { it.isFinite() && it in 0f..1f }?.let { put("ocrConfidence", it.toDouble()) }
            ocrSuccessful?.let { put("ocrSuccessful", it) }
        }
}

private fun JSONObject.toDocument(viewerUid: String, id: String): SafetyDocument {
    requireIdentity(viewerUid, id)
    val allowed = getJSONArray("allowedUsers").let { values ->
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.distinct()
    require(viewerUid in allowed && allowed.size <= 25)
    val accountKey = optString("viewerWrappedKey").takeIf(String::isNotBlank)
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
        encryptedMetadata = optString("encryptedMetadata"),
        wrappedKeys = accountKey?.let { mapOf(viewerUid to it) }.orEmpty(),
        ciphertextSize = size,
        ciphertextSha256 = checksum,
        encryptionVersion = optInt("encryptionVersion"),
        deviceWrappedKeys = deviceKeys,
        evidence = optBoolean("evidence"),
        tags = tags,
    )
}

private fun JSONObject.child(name: String): JSONObject =
    optJSONObject(name) ?: JSONObject().also { put(name, it) }

private fun requireIdentity(uid: String, id: String? = null) {
    require(uid.isNotBlank() && uid.length <= MAX_ID_LENGTH)
    id?.let { require(it.isNotBlank() && it.length <= MAX_ID_LENGTH) }
}

private const val MAX_ID_LENGTH = 160
private const val MAX_OCR_TEXT = 50_000
private const val MAX_CIPHERTEXT_BYTES = 250L * 1024L * 1024L
private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
private val SHA_256_LOWER = Regex("^[0-9a-f]{64}$")
