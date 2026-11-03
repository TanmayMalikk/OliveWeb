// Safe-app users and support staff: loads and saves a user’s safety documents.
package com.olive.app.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.security.spec.MGF1ParameterSpec

data class SafetyDocument(
    val id: String,
    val ownerUid: String,
    val storagePath: String,
    val name: String,
    val mimeType: String,
    val allowedUsers: List<String>,
    val createdAt: Long = 0,
    val ocrText: String? = null,
    val ocrConfidence: Float? = null,
    val ocrSuccessful: Boolean? = null,
    val objectKey: String = "",
    val encryptedMetadata: String = "",
    val wrappedKeys: Map<String, String> = emptyMap(),
    val ciphertextSize: Long = 0,
    val ciphertextSha256: String = "",
    val encryptionVersion: Int = 0,
    val deviceWrappedKeys: Map<String, String> = emptyMap(),
    val evidence: Boolean = false,
)

data class DocumentOcrMetadata(val text: String, val confidence: Float, val successful: Boolean)

/** Accepts OCR only after the containing metadata has been decrypted on the client. */
internal fun documentOcrMetadataFrom(metadata: JSONObject?): DocumentOcrMetadata? {
    metadata ?: return null
    val text = metadata.opt("ocrText") as? String ?: return null
    val confidence = (metadata.opt("ocrConfidence") as? Number)?.toFloat() ?: return null
    val successful = metadata.opt("ocrSuccessful") as? Boolean ?: return null
    if (text.length > MAX_OCR_TEXT_LENGTH || !confidence.isFinite() || confidence !in 0f..1f) return null
    return DocumentOcrMetadata(text, confidence, successful)
}

interface SafetyDocumentDataSource {
    // Lists every safety document this account is allowed to open.
    suspend fun listFor(uid: String): List<SafetyDocument>
    // Streams every document the account may open and reports storage-listener failures separately.
    fun observeFor(
        uid: String,
        onUpdate: (List<SafetyDocument>) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription

    // Loads one allowed safety document by its ID.
    suspend fun getFor(uid: String, id: String): SafetyDocument
    // Wraps the document key for new recipients before sharing it.
    suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>)
    // Deletes a safety document owned by the current user.
    suspend fun delete(document: SafetyDocument, ownerUid: String)
    // Marks an owned file as evidence or returns it to a normal document.
    suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) = Unit
    // Downloads and decrypts a safety document for in-app viewing.
    suspend fun download(context: Context, document: SafetyDocument): File
    /** Gives this account's registered phones and browsers encrypted copies of the document key. */
    suspend fun approveCurrentAccountDevices(document: SafetyDocument, currentUid: String, username: String)
}

class SafetyDocumentRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val media: MediaTicketDataSource,
) : SafetyDocumentDataSource {
    // Reads every Firebase document this account is allowed to open.
    override suspend fun listFor(uid: String): List<SafetyDocument> = firestore.collection("files")
        .whereArrayContains("allowed_users", uid).get().await().documents.mapNotNull { documentFrom(it, uid) }
        .sortedByDescending { it.createdAt }

    // Converts live Firestore file updates into the user's readable document list.
    override fun observeFor(
        uid: String,
        onUpdate: (List<SafetyDocument>) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        val listener = firestore.collection("files").whereArrayContains("allowed_users", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                onUpdate(
                    snapshot?.documents.orEmpty().mapNotNull { documentFrom(it, uid) }
                        .sortedByDescending { it.createdAt })
            }
        return ChatSubscription { listener.remove() }
    }

    // Loads the encrypted document records that this account may open.
    override suspend fun getFor(uid: String, id: String): SafetyDocument {
        val document = documentFrom(firestore.collection("files").document(id).get().await(), uid)
            ?: error("File is no longer available")
        require(uid in document.allowedUsers) { "You no longer have access to this file" }
        return document
    }

    // Adds encrypted document access for each selected recipient.
    override suspend fun share(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>
    ) {
        require(document.ownerUid == ownerUid && recipients.size <= 10 && ownerUid !in recipients)
        val current = getFor(ownerUid, document.id)
        if (current.objectKey.isNotBlank()) {
            media.updateAccess(current.id, encryptedAccess(current, ownerUid, recipients))
            return
        }
        val updated = (listOf(ownerUid) + recipients.keys).distinct()
        updateStorageAccess(current, current.allowedUsers.toSet(), updated.toSet())
        // Turn an unexpected share failure into a readable message.
        try {
            firestore.collection("files").document(document.id).update("allowed_users", updated)
                .await()
        } catch (failure: Exception) {
            runCatching {
                updateStorageAccess(
                    current,
                    updated.toSet(),
                    current.allowedUsers.toSet()
                )
            }
            throw failure
        }
    }

    // Wraps the document key for another approved account without exposing the raw key.
    suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
    ) {
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        val current = getFor(ownerUid, document.id)
        if (current.objectKey.isNotBlank()) {
            if (recipientUid in current.allowedUsers && current.wrappedKeys[recipientUid]?.isNotBlank() == true) return
            val ownerWrapped = current.wrappedKeys[ownerUid]
                ?: error("The owner encryption key is unavailable")
            val documentKey = KeyManager.unwrapKey(ownerWrapped)
            val wrapped = current.wrappedKeys + (
                recipientUid to KeyManager.wrapKey(
                    documentKey,
                    directoryPublicKey(recipientUid, recipientUsername),
                )
            )
            media.updateAccess(current.id, wrapped)
            return
        }
        val updated = current.allowedUsers.toSet() + recipientUid
        updateStorageAccess(current, current.allowedUsers.toSet(), updated)
        // Turn an unexpected grant access failure into a readable message.
        try {
            firestore.collection("files").document(document.id)
                .update("allowed_users", FieldValue.arrayUnion(recipientUid)).await()
        } catch (failure: Exception) {
            runCatching { updateStorageAccess(current, updated, current.allowedUsers.toSet()) }
            throw failure
        }
    }

    // Removes access from the safety plan.
    suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) {
        val document = getFor(ownerUid, documentId)
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        if (document.objectKey.isNotBlank()) {
            val wrapped = document.wrappedKeys - recipientUid
            require(ownerUid in wrapped) { "The owner encryption key cannot be removed" }
            media.updateAccess(document.id, wrapped)
            return
        }
        val updated = document.allowedUsers.toSet() - recipientUid
        updateStorageAccess(document, document.allowedUsers.toSet(), updated)
        // Use the fallback when revoke access is unavailable.
        try {
            firestore.collection("files").document(documentId)
                .update("allowed_users", FieldValue.arrayRemove(recipientUid)).await()
        } catch (failure: Exception) {
            runCatching { updateStorageAccess(document, updated, document.allowedUsers.toSet()) }
            throw failure
        }
    }

    // Removes an owned document from cloud storage and Firebase.
    override suspend fun delete(document: SafetyDocument, ownerUid: String) {
        require(document.ownerUid == ownerUid)
        if (document.objectKey.isNotBlank()) {
            media.deleteMedia(document.id)
            return
        }
        storage.reference.child(document.storagePath).delete().await()
        firestore.collection("files").document(document.id).delete().await()
    }

    // Stores evidence as document metadata, so the same setting appears on every signed-in device.
    override suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) {
        require(document.ownerUid == ownerUid)
        if (document.objectKey.isNotBlank()) {
            media.updateEvidence(document.id, evidence)
        } else {
            firestore.collection("files").document(document.id).update("evidence", evidence).await()
        }
    }

    // Safety: Downloads the selected file.
    override suspend fun download(context: Context, document: SafetyDocument): File {
        require(document.objectKey.isBlank()) { "Encrypted media downloads use MediaTransferController" }
        val folder = File(context.cacheDir, "opened-documents").apply { mkdirs() }
        folder.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > CACHE_LIFETIME }
            ?.forEach(File::delete)
        val extension = document.name.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val target = File(folder, document.id + (extension?.let { ".$it" } ?: ""))
        storage.reference.child(document.storagePath).getFile(target).await()
        return target
    }

    // Gives every registered device on this account its own wrapped document key.
    override suspend fun approveCurrentAccountDevices(
        document: SafetyDocument,
        currentUid: String,
        username: String,
    ) {
        if (document.objectKey.isBlank() || currentUid !in document.allowedUsers) return
        val ownDeviceKey = "$currentUid--${KeyManager.deviceId()}"
        val wrapped = document.deviceWrappedKeys[ownDeviceKey] ?: document.wrappedKeys[currentUid] ?: return
        val documentKey = runCatching { KeyManager.unwrapKey(wrapped, document.encryptionVersion) }.getOrNull() ?: return
        val normalized = username.trim().lowercase()
        if (!normalized.matches(Regex("[a-z0-9_]{4,24}"))) return
        val directory = firestore.collection("messageDirectory").document(normalized)
        if (directory.get().await().getString("uid") != currentUid) return
        val additions = directory.collection("devices").get().await().documents.mapNotNull { device ->
            if (device.getString("uid") != currentUid) return@mapNotNull null
            val deviceId = device.getString("deviceId") ?: return@mapNotNull null
            val mapKey = "$currentUid--$deviceId"
            if (document.deviceWrappedKeys.containsKey(mapKey)) return@mapNotNull null
            val publicKey = runCatching {
                KeyManager.decodePublicKey(device.getString("publicKey").orEmpty())
            }.getOrNull() ?: return@mapNotNull null
            val digest = if (device.getString("mgf1Digest") == "SHA-256")
                MGF1ParameterSpec.SHA256 else MGF1ParameterSpec.SHA1
            mapKey to KeyManager.wrapKey(documentKey, publicKey, digest)
        }.toMap()
        if (additions.isNotEmpty()) media.updateDeviceKeys(document.id, additions)
    }

    // Grants or removes encrypted-file access for the selected document recipients.
    private suspend fun updateStorageAccess(
        document: SafetyDocument,
        before: Set<String>,
        after: Set<String>
    ) {
        val metadata = StorageMetadata.Builder().apply {
            after.forEach { setCustomMetadata(accessKey(it), "true") }
            (before - after).forEach { setCustomMetadata(accessKey(it), null) }
        }.build()
        storage.reference.child(document.storagePath).updateMetadata(metadata).await()
    }

    /** Re-wraps the document key for the exact final recipient set using registered device keys. */
    private suspend fun encryptedAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
    ): Map<String, String> {
        val ownerWrapped = document.wrappedKeys[ownerUid]
            ?: error("The owner encryption key is unavailable")
        val documentKey = KeyManager.unwrapKey(ownerWrapped)
        return buildMap {
            put(ownerUid, ownerWrapped)
            recipients.forEach { (uid, username) ->
                require(uid != ownerUid) { "The owner cannot be added as a recipient" }
                val existing = document.wrappedKeys[uid]
                put(
                    uid,
                    if (username.isBlank() && existing != null) existing
                    else KeyManager.wrapKey(documentKey, directoryPublicKey(uid, username)),
                )
            }
        }
    }

    /** Loads one exact messaging-directory entry and verifies it still belongs to the expected UID. */
    private suspend fun directoryPublicKey(uid: String, username: String): java.security.PublicKey {
        val normalized = username.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Recipient username is invalid" }
        val row = firestore.collection("messageDirectory").document(normalized).get().await()
        require(row.getString("uid") == uid) { "Recipient encryption identity is unavailable" }
        return KeyManager.decodePublicKey(
            row.getString("publicKey").orEmpty().ifBlank { error("Recipient encryption key is unavailable") }
        )
    }

    // Converts a stored document record into the model shown by the Safe App.
    private fun documentFrom(row: DocumentSnapshot, viewerUid: String): SafetyDocument? {
        val owner = row.getString("owner_id") ?: return null
        val encryptedMetadata = row.getString("encrypted_metadata").orEmpty()
        val encryptionVersion = row.getLong("encryption_version")?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val wrapped = (row.get("wrapped_keys") as? Map<String, String>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val deviceWrapped = (row.get("device_wrapped_keys") as? Map<String, String>).orEmpty()
        val metadata = if (encryptedMetadata.isBlank()) null else runCatching {
            val deviceKey = "$viewerUid--${KeyManager.deviceId()}"
            val key = (deviceWrapped[deviceKey] ?: wrapped[viewerUid])?.let {
                KeyManager.unwrapKey(it, encryptionVersion)
            }
                ?: error("Encryption key is unavailable")
            JSONObject(EncryptionUtils.decryptText(encryptedMetadata, key))
        }.getOrNull()
        val ocr = documentOcrMetadataFrom(metadata)
        return SafetyDocument(
            id = row.id,
            ownerUid = owner,
            storagePath = row.getString("storage_path").orEmpty(),
            name = metadata?.optString("name")?.takeIf(String::isNotBlank)
                ?: row.getString("file_name").orEmpty().ifBlank { "Encrypted document" },
            mimeType = metadata?.optString("mimeType")?.takeIf(String::isNotBlank)
                ?: row.getString("file_type").orEmpty().ifBlank { "application/octet-stream" },
            allowedUsers = (row.get("allowed_users") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            createdAt = row.getTimestamp("created_at")?.toDate()?.time ?: 0,
            ocrText = ocr?.text,
            ocrConfidence = ocr?.confidence,
            ocrSuccessful = ocr?.successful,
            objectKey = row.getString("object_key").orEmpty(),
            encryptedMetadata = encryptedMetadata,
            wrappedKeys = wrapped,
            ciphertextSize = row.getLong("ciphertext_size") ?: 0,
            ciphertextSha256 = row.getString("ciphertext_sha256").orEmpty(),
            encryptionVersion = encryptionVersion,
            deviceWrappedKeys = deviceWrapped,
            evidence = row.getBoolean("evidence") ?: false,
        )
    }

    private companion object {
        const val CACHE_LIFETIME = 60L * 60L * 1000L
        // Uses one protected-storage key for this account's document access data.
        fun accessKey(uid: String) = "access_$uid"
    }
}

private const val MAX_OCR_TEXT_LENGTH = 50_000
