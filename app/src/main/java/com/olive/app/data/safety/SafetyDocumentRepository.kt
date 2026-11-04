// Safe-app users and support staff: loads and saves a user’s safety documents.
package com.olive.app.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
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

enum class DocumentListSource { LOCAL_INDEX, FIRESTORE_CACHE, SERVER }

data class DocumentListSnapshot(
    val documents: List<SafetyDocument>,
    val source: DocumentListSource,
    val lastSyncedAt: Long? = null,
)

/** Retains device-encrypted key copies only for the exact authorized account set. */
internal fun retainAuthorizedDeviceKeys(
    keys: Map<String, String>,
    allowedUsers: Set<String>,
): Map<String, String> = keys.filterKeys { key ->
    allowedUsers.any { uid -> key.startsWith("$uid--") }
}

/** Selects only this account and device's document-key copies, preferring the device envelope. */
internal fun documentKeyCandidates(
    document: SafetyDocument,
    uid: String,
    deviceId: String,
): List<String> = listOfNotNull(
    document.deviceWrappedKeys["$uid--$deviceId"],
    document.wrappedKeys[uid],
).filter(String::isNotBlank).distinct()

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
    suspend fun listFor(uid: String): DocumentListSnapshot
    // Streams every document the account may open and reports storage-listener failures separately.
    fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription

    // Loads one allowed safety document by its ID.
    suspend fun getFor(uid: String, id: String): SafetyDocument
    /** Returns encrypted-at-rest index metadata without contacting Firebase. */
    fun cachedFor(uid: String, id: String): SafetyDocument? = null
    // Wraps the document key for new recipients before sharing it.
    suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>)
    suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
    ) { error("Encrypted media sharing is unavailable") }
    suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) {
        error("Encrypted media revocation is unavailable")
    }
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
    override suspend fun listFor(uid: String): DocumentListSnapshot {
        val snapshot = firestore.collection("files").whereArrayContains("allowed_users", uid)
            .get(Source.SERVER).await()
        return DocumentListSnapshot(
            snapshot.documents.mapNotNull { documentFrom(it, uid) }.sortedByDescending { it.createdAt },
            DocumentListSource.SERVER,
            System.currentTimeMillis(),
        )
    }

    // Converts live Firestore file updates into the user's readable document list.
    override fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        val listener = firestore.collection("files").whereArrayContains("allowed_users", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                val source = if (snapshot?.metadata?.isFromCache == false &&
                    snapshot.metadata.hasPendingWrites().not()) DocumentListSource.SERVER
                else DocumentListSource.FIRESTORE_CACHE
                onUpdate(DocumentListSnapshot(
                    snapshot?.documents.orEmpty().mapNotNull { documentFrom(it, uid) }
                        .sortedByDescending { it.createdAt },
                    source,
                    System.currentTimeMillis().takeIf { source == DocumentListSource.SERVER },
                ))
            }
        return ChatSubscription { listener.remove() }
    }

    // Loads the encrypted document records that this account may open.
    override suspend fun getFor(uid: String, id: String): SafetyDocument {
        try {
            val row = firestore.collection("files").document(id).get(Source.SERVER).await()
            val document = documentFrom(row, uid)
                ?: throw MediaAccessException(MediaAccessError.ACCESS_REVOKED)
            if (uid !in document.allowedUsers) {
                throw MediaAccessException(MediaAccessError.ACCESS_REVOKED)
            }
            return document
        } catch (failure: FirebaseFirestoreException) {
            val reason = when (failure.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.NOT_FOUND -> MediaAccessError.ACCESS_REVOKED
                FirebaseFirestoreException.Code.UNAVAILABLE -> MediaAccessError.NETWORK_REQUIRED
                else -> throw failure
            }
            throw MediaAccessException(reason, failure)
        }
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
            val access = encryptedAccess(current, ownerUid, recipients)
            media.updateAccess(current.id, access.accountKeys, access.deviceKeys)
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
    override suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
    ) {
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        val current = getFor(ownerUid, document.id)
        if (current.objectKey.isNotBlank()) {
            val documentKey = unwrapDocumentKey(current, ownerUid)
            val recipient = recipientAccess(documentKey, recipientUid, recipientUsername)
            val wrapped = current.wrappedKeys + (recipientUid to recipient.accountKey)
            val devices = retainAuthorizedDeviceKeys(current.deviceWrappedKeys, wrapped.keys) + recipient.deviceKeys
            media.updateAccess(current.id, wrapped, devices)
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
    override suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) {
        val document = getFor(ownerUid, documentId)
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        if (document.objectKey.isNotBlank()) {
            val wrapped = document.wrappedKeys - recipientUid
            require(ownerUid in wrapped) { "The owner encryption key cannot be removed" }
            val devices = retainAuthorizedDeviceKeys(document.deviceWrappedKeys, wrapped.keys)
            media.updateAccess(document.id, wrapped, devices)
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
        val documentKey = runCatching {
            unwrapDocumentKey(document, currentUid)
        }.getOrNull() ?: return
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

    /** Complete key replacement sent through one atomic owner-only access update. */
    private data class EncryptedAccess(
        val accountKeys: Map<String, String>,
        val deviceKeys: Map<String, String>,
    )

    private data class RecipientAccess(
        val accountKey: String,
        val deviceKeys: Map<String, String>,
    )

    /** Re-wraps the document key for the exact final recipient set using registered device keys. */
    private suspend fun encryptedAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
    ): EncryptedAccess {
        val ownerWrapped = document.wrappedKeys[ownerUid]
            ?: error("The owner account encryption key is unavailable")
        val documentKey = unwrapDocumentKey(document, ownerUid)
        val accountKeys = mutableMapOf(ownerUid to ownerWrapped)
        val deviceKeys = retainAuthorizedDeviceKeys(document.deviceWrappedKeys, setOf(ownerUid)).toMutableMap()
        recipients.forEach { (uid, username) ->
            require(uid != ownerUid) { "The owner cannot be added as a recipient" }
            if (username.isBlank() && document.wrappedKeys[uid] != null) {
                accountKeys[uid] = checkNotNull(document.wrappedKeys[uid])
                deviceKeys.putAll(retainAuthorizedDeviceKeys(document.deviceWrappedKeys, setOf(uid)))
            } else {
                val recipient = recipientAccess(documentKey, uid, username)
                accountKeys[uid] = recipient.accountKey
                deviceKeys.putAll(recipient.deviceKeys)
            }
        }
        return EncryptedAccess(accountKeys, deviceKeys)
    }

    /** Wraps the document key for both the directory identity and every registered recipient device. */
    private suspend fun recipientAccess(
        documentKey: javax.crypto.SecretKey,
        uid: String,
        username: String,
    ): RecipientAccess {
        val normalized = username.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Recipient username is invalid" }
        val directory = firestore.collection("messageDirectory").document(normalized)
        val row = directory.get().await()
        require(row.getString("uid") == uid) { "Recipient encryption identity is unavailable" }
        val encodedAccountKey = row.getString("publicKey").orEmpty()
            .ifBlank { error("Recipient encryption key is unavailable") }
        val devices = directory.collection("devices").get().await().documents.mapNotNull { device ->
            if (device.getString("uid") != uid) return@mapNotNull null
            val deviceId = device.getString("deviceId")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val encodedKey = device.getString("publicKey").orEmpty()
            val publicKey = runCatching { KeyManager.decodePublicKey(encodedKey) }.getOrNull()
                ?: return@mapNotNull null
            val digest = if (device.getString("mgf1Digest") == "SHA-256")
                MGF1ParameterSpec.SHA256 else MGF1ParameterSpec.SHA1
            Triple("$uid--$deviceId", KeyManager.wrapKey(documentKey, publicKey, digest),
                encodedKey to digest)
        }
        require(devices.isNotEmpty()) {
            "The recipient must open the updated app before encrypted files can be shared"
        }
        val accountDigest = devices.firstOrNull { it.third.first == encodedAccountKey }?.third?.second
            ?: MGF1ParameterSpec.SHA1
        return RecipientAccess(
            KeyManager.wrapKey(documentKey, KeyManager.decodePublicKey(encodedAccountKey), accountDigest),
            devices.associate { it.first to it.second },
        )
    }

    /** Uses this installation's key copy before the account envelope shared across installations. */
    private fun unwrapDocumentKey(document: SafetyDocument, uid: String): javax.crypto.SecretKey {
        val candidates = documentKeyCandidates(document, uid, KeyManager.deviceId())
        if (candidates.isEmpty()) error("The document encryption key is unavailable")
        return KeyManager.unwrapFirst(candidates, document.encryptionVersion)
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
            val key = KeyManager.unwrapFirst(
                listOfNotNull(deviceWrapped[deviceKey], wrapped[viewerUid]),
                encryptionVersion,
            )
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
