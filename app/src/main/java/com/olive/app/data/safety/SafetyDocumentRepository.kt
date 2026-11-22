// Safe-app users and support staff: loads and saves a user’s safety documents.
package com.olive.app.data.safety

import com.olive.app.data.network.cloudStringMap

import android.content.Context
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import java.io.File
import java.security.spec.MGF1ParameterSpec
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class SafetyDocument(
    val id: String, val ownerUid: String, val storagePath: String, val name: String,
    val mimeType: String, val allowedUsers: List<String>, val createdAt: Long = 0,
    val ocrText: String? = null, val ocrConfidence: Float? = null, val ocrSuccessful: Boolean? = null,
    val objectKey: String = "", val encryptedMetadata: String = "",
    val wrappedKeys: Map<String, String> = emptyMap(), val ciphertextSize: Long = 0,
    val ciphertextSha256: String = "", val encryptionVersion: Int = 0,
    val deviceWrappedKeys: Map<String, String> = emptyMap(), val evidence: Boolean = false,
    val tags: List<String> = emptyList(),
    val accountRecoveryReady: Boolean = false,
    val accountWrappedKeys: Map<String, AccountKeysetWrappedKey> = emptyMap(),
)

enum class DocumentListSource { LOCAL_INDEX, FIRESTORE_CACHE, SERVER }

data class DocumentListSnapshot(
    val documents: List<SafetyDocument>,
    val source: DocumentListSource,
    val lastSyncedAt: Long? = null,
)

/**
 * Retains device-encrypted key copies only for the exact authorized account set.
 *
 * @param keys Key-value mapping of keys entries.
 * @param allowedUsers Collection of allowed users values to process.
 * @return Map containing the resulting retain authorized device keys entries.
 */
internal fun retainAuthorizedDeviceKeys(
    keys: Map<String, String>,
    allowedUsers: Set<String>,
): Map<String, String> = keys.filterKeys { key ->
    allowedUsers.any { uid -> key.startsWith("$uid--") }
}

/**
 * Selects only this account and device's document-key copies, preferring the device envelope.
 *
 * @param document Safety document metadata record.
 * @param uid Firebase UID of the target user account.
 * @param deviceId Stable identifier for this device installation.
 * @return List of resulting document key candidates items.
 */
internal fun documentKeyCandidates(
    document: SafetyDocument,
    uid: String,
    deviceId: String,
): List<String> = listOfNotNull(
    document.deviceWrappedKeys["$uid--$deviceId"],
    document.wrappedKeys[uid],
).filter(String::isNotBlank).distinct()

data class DocumentOcrMetadata(val text: String, val confidence: Float, val successful: Boolean)

/**
 * Accepts OCR only after the containing metadata has been decrypted on the client.
 *
 * @param metadata Optional metadata supplied to [documentOcrMetadataFrom].
 * @return The resulting [DocumentOcrMetadata], or `null` if unavailable.
 */
internal fun documentOcrMetadataFrom(metadata: JSONObject?): DocumentOcrMetadata? {
    metadata ?: return null
    val text = metadata.opt("ocrText") as? String ?: return null
    val confidence = (metadata.opt("ocrConfidence") as? Number)?.toFloat() ?: return null
    val successful = metadata.opt("ocrSuccessful") as? Boolean ?: return null
    if (text.length > MAX_OCR_TEXT_LENGTH || !confidence.isFinite() || confidence !in 0f..1f) return null
    return DocumentOcrMetadata(text, confidence, successful)
}

/**
 * Reads small private organization tags only after the encrypted metadata is opened on-device.
 *
 * @param metadata Optional metadata supplied to [documentTagsFrom].
 * @return List of resulting document tags from items.
 */
internal fun documentTagsFrom(metadata: JSONObject?): List<String> {
    val values = metadata?.optJSONArray("tags") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(values.length(), 20)) {
            values.optString(index).trim().take(80).takeIf(String::isNotBlank)?.let(::add)
        }
    }.distinct()
}

interface SafetyDocumentDataSource {
    /**
     * Lists every safety document this account is allowed to open.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [DocumentListSnapshot] for list for.
     */
    suspend fun listFor(uid: String): DocumentListSnapshot
    /**
     * Streams every document the account may open and reports storage-listener failures separately.
     *
     * @param uid Firebase UID of the target user account.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription

    /**
     * Loads one allowed safety document by its ID.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [getFor].
     * @return The resulting [SafetyDocument] for get for.
     */
    suspend fun getFor(uid: String, id: String): SafetyDocument
    /**
     * Returns encrypted-at-rest index metadata without contacting Firebase.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [cachedFor].
     * @return The resulting [SafetyDocument], or `null` if unavailable.
     */
    fun cachedFor(uid: String, id: String): SafetyDocument? = null
    /**
     * Wraps the document key for new recipients before sharing it.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     */
    suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>)
    /**
     * Shares in chat.
     *
     * @param document Safety document metadata record.
     * @param outgoing Outgoing supplied to [shareInChat].
     * @param recipients Key-value mapping of recipients entries.
     */
    suspend fun shareInChat(document: SafetyDocument, outgoing: OutgoingMessage, recipients: Map<String, String>) {
        error("Chat attachment sharing is unavailable")
    }
    /**
     * Grants access.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     * @param recipientUsername Recipient username supplied to [grantAccess].
     * @param forPlan Whether to apply for plan.
     */
    suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
        forPlan: Boolean = false,
    ) { error("Encrypted media sharing is unavailable") }
    /**
     * Revokes access.
     *
     * @param documentId Unique identifier of the safety document.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     */
    suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) {
        error("Encrypted media revocation is unavailable")
    }
    /**
     * Deletes a safety document owned by the current user.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     */
    suspend fun delete(document: SafetyDocument, ownerUid: String)
    /**
     * Marks an owned file as evidence or returns it to a normal document.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param evidence Whether to apply evidence.
     */
    suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) = Unit
    /**
     * Downloads and decrypts a safety document for in-app viewing.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param document Safety document metadata record.
     * @return The resulting [File] instance.
     */
    suspend fun download(context: Context, document: SafetyDocument): File
    /**
     * Gives this account's registered phones and browsers encrypted copies of the document key.
     *
     * @param document Safety document metadata record.
     * @param currentUid Current user ID supplied to [approveCurrentAccountDevices].
     * @param username Account username.
     */
    suspend fun approveCurrentAccountDevices(document: SafetyDocument, currentUid: String, username: String)
}

class SafetyDocumentRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val media: MediaTicketDataSource,
    private val authorizePeer: suspend (String) -> Unit = { com.olive.app.data.messaging.authorizeMessagingPeer(it) },
) : SafetyDocumentDataSource {
    /**
     * Reads every Firebase document this account is allowed to open.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [DocumentListSnapshot] for list for.
     */
    override suspend fun listFor(uid: String): DocumentListSnapshot {
        val snapshot = firestore.collection("files").whereArrayContains("allowed_users", uid)
            .get(Source.SERVER).await()
        return DocumentListSnapshot(
            resolveDocuments(snapshot.documents, uid),
            DocumentListSource.SERVER,
            System.currentTimeMillis(),
        )
    }

    /**
     * Converts live Firestore file updates into the user's readable document list.
     *
     * @param uid Firebase UID of the target user account.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    override fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ChatSubscription {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var resolution: Job? = null
        val listener = firestore.collection("files").whereArrayContains("allowed_users", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                resolution?.cancel()
                if (error != null) {
                    onError(error.localizedMessage.orEmpty())
                    return@addSnapshotListener
                }
                val source = if (snapshot?.metadata?.isFromCache == false &&
                    snapshot.metadata.hasPendingWrites().not()) DocumentListSource.SERVER
                else DocumentListSource.FIRESTORE_CACHE
                val rows = snapshot?.documents.orEmpty()
                resolution = scope.launch {
                    val documents = if (source == DocumentListSource.SERVER) resolveDocuments(rows, uid)
                    else rows.mapNotNull { documentFrom(it, uid) }.sortedByDescending { it.createdAt }
                    onUpdate(DocumentListSnapshot(
                        documents, source, System.currentTimeMillis().takeIf { source == DocumentListSource.SERVER },
                    ))
                }
            }
        return ChatSubscription { listener.remove(); scope.cancel() }
    }

    /**
     * Loads the encrypted document records that this account may open.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [getFor].
     * @return The resulting [SafetyDocument] for get for.
     */
    override suspend fun getFor(uid: String, id: String): SafetyDocument {
        try {
            val row = firestore.collection("files").document(id).get(Source.SERVER).await()
            val document = resolveDocument(row, uid)
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

    /**
     * Adds encrypted document access for each selected recipient.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     */
    override suspend fun share(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>
    ) {
        require(document.ownerUid == ownerUid && recipients.size <= 10 && ownerUid !in recipients)
        val current = getFor(ownerUid, document.id)
        if (current.objectKey.isNotBlank()) {
            val access = encryptedAccess(current, ownerUid, recipients)
            media.updateAccess(
                current.id, access.accountKeys, access.deviceKeys, access.accountKeysetKeys,
                grantIndependentRecipients = recipients.keys.toList(),
            )
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

    /**
     * Shares in chat.
     *
     * @param document Safety document metadata record.
     * @param outgoing Outgoing supplied to [shareInChat].
     * @param recipients Key-value mapping of recipients entries.
     */
    override suspend fun shareInChat(
        document: SafetyDocument,
        outgoing: OutgoingMessage,
        recipients: Map<String, String>,
    ) {
        require(document.ownerUid == outgoing.ownerUid)
        val current = getFor(outgoing.ownerUid, document.id)
        val access = if (current.objectKey.isNotBlank()) {
            encryptedAccess(current, outgoing.ownerUid, recipients)
        } else {
            EncryptedAccess(emptyMap(), emptyMap(), emptyMap())
        }
        media.shareInChat(
            current.id,outgoing,recipients.keys.toList(),access.accountKeys,access.deviceKeys,
            access.accountKeysetKeys,
        )
    }

    /**
     * Wraps the document key for another approved account without exposing the raw key.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     * @param recipientUsername Recipient username supplied to [grantAccess].
     * @param forPlan Whether to apply for plan.
     */
    override suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
        forPlan: Boolean,
    ) {
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        val current = getFor(ownerUid, document.id)
        if (current.objectKey.isNotBlank()) {
            val documentKey = unwrapDocumentKey(current, ownerUid)
            val recipient = recipientAccess(documentKey, recipientUid, recipientUsername)
            if (forPlan) {
                if (recipientUid in current.allowedUsers &&
                    current.deviceWrappedKeys.keys.containsAll(recipient.deviceKeys.keys)) return
                media.updatePlanAccess(current.id, mapOf(
                    ownerUid to checkNotNull(current.wrappedKeys[ownerUid]),
                    recipientUid to recipient.accountKey,
                ), recipient.deviceKeys, mapOf(
                    ownerUid to (current.accountWrappedKeys[ownerUid]
                        ?: accountKeysetAccess(documentKey,ownerUid)),
                    recipientUid to recipient.accountKeysetKey,
                ))
                return
            }
            val wrapped = current.wrappedKeys + (recipientUid to recipient.accountKey)
            val devices = retainAuthorizedDeviceKeys(current.deviceWrappedKeys, wrapped.keys) + recipient.deviceKeys
            val accountWrapped = current.accountWrappedKeys + (recipientUid to recipient.accountKeysetKey) +
                (ownerUid to (current.accountWrappedKeys[ownerUid]
                    ?: accountKeysetAccess(documentKey,ownerUid)))
            media.updateAccess(
                current.id, wrapped, devices, accountWrapped,
                grantIndependentRecipients = listOf(recipientUid),
            )
            return
        }
        check(!forPlan) { "Upload an encrypted copy before attaching this legacy document." }
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

    /**
     * Removes access from the safety plan.
     *
     * @param documentId Unique identifier of the safety document.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     */
    override suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) {
        val document = getFor(ownerUid, documentId)
        require(document.ownerUid == ownerUid && recipientUid != ownerUid)
        if (document.objectKey.isNotBlank()) {
            val wrapped = document.wrappedKeys - recipientUid
            require(ownerUid in wrapped) { "The owner encryption key cannot be removed" }
            val devices = retainAuthorizedDeviceKeys(document.deviceWrappedKeys, wrapped.keys)
            val documentKey=unwrapDocumentKey(document,ownerUid)
            val accountWrapped=wrapped.keys.associateWith { uid ->
                document.accountWrappedKeys[uid] ?: accountKeysetAccess(documentKey,uid)
            }
            media.updateAccess(
                document.id, wrapped, devices, accountWrapped,
                revokeIndependentRecipients = listOf(recipientUid),
            )
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

    /**
     * Removes an owned document from cloud storage and Firebase.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     */
    override suspend fun delete(document: SafetyDocument, ownerUid: String) {
        require(document.ownerUid == ownerUid)
        if (document.objectKey.isNotBlank()) {
            media.deleteMedia(document.id)
            return
        }
        storage.reference.child(document.storagePath).delete().await()
        firestore.collection("files").document(document.id).delete().await()
    }

    /**
     * Stores evidence as document metadata, so the same setting appears on every signed-in device.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param evidence Whether to apply evidence.
     */
    override suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) {
        require(document.ownerUid == ownerUid)
        if (document.objectKey.isNotBlank()) {
            media.updateEvidence(document.id, evidence)
        } else {
            firestore.collection("files").document(document.id).update("evidence", evidence).await()
        }
    }

    /**
     * Safety: Downloads the selected file.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param document Safety document metadata record.
     * @return The resulting [File] instance.
     */
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

    /**
     * Gives every registered device on this account its own wrapped document key.
     *
     * @param document Safety document metadata record.
     * @param currentUid Current user ID supplied to [approveCurrentAccountDevices].
     * @param username Account username.
     */
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

    /**
     * Grants or removes encrypted-file access for the selected document recipients.
     *
     * @param document Safety document metadata record.
     * @param before Collection of before values to process.
     * @param after Collection of after values to process.
     */
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
        val accountKeysetKeys: Map<String, AccountKeysetWrappedKey>,
    )

    private data class RecipientAccess(
        val accountKey: String,
        val deviceKeys: Map<String, String>,
        val accountKeysetKey: AccountKeysetWrappedKey,
    )

    /**
     * Re-wraps the document key for the exact final recipient set using registered device keys.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     * @return The resulting [EncryptedAccess] for encrypted access.
     */
    private suspend fun encryptedAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
    ): EncryptedAccess {
        val ownerWrapped = document.wrappedKeys[ownerUid]
            ?: error("The owner account encryption key is unavailable")
        val documentKey = unwrapDocumentKey(document, ownerUid)
        val accountKeys = mutableMapOf(ownerUid to ownerWrapped)
        val accountKeysetKeys = mutableMapOf(
            ownerUid to (document.accountWrappedKeys[ownerUid]
                ?: accountKeysetAccess(documentKey,ownerUid)),
        )
        val deviceKeys = retainAuthorizedDeviceKeys(document.deviceWrappedKeys, setOf(ownerUid)).toMutableMap()
        recipients.forEach { (uid, username) ->
            require(uid != ownerUid) { "The owner cannot be added as a recipient" }
            if (username.isBlank() && document.wrappedKeys[uid] != null) {
                accountKeys[uid] = checkNotNull(document.wrappedKeys[uid])
                accountKeysetKeys[uid] = document.accountWrappedKeys[uid]
                    ?: accountKeysetAccess(documentKey,uid)
                deviceKeys.putAll(retainAuthorizedDeviceKeys(document.deviceWrappedKeys, setOf(uid)))
            } else {
                val recipient = recipientAccess(documentKey, uid, username)
                accountKeys[uid] = recipient.accountKey
                accountKeysetKeys[uid] = recipient.accountKeysetKey
                deviceKeys.putAll(recipient.deviceKeys)
            }
        }
        return EncryptedAccess(accountKeys, deviceKeys, accountKeysetKeys)
    }

    /**
     * Wraps the document key for both the directory identity and every registered recipient device.
     *
     * @param documentKey Document key supplied to [recipientAccess].
     * @param uid Firebase UID of the target user account.
     * @param username Account username.
     * @return The resulting [RecipientAccess] for recipient access.
     */
    private suspend fun recipientAccess(
        documentKey: SecretKey,
        uid: String,
        username: String,
    ): RecipientAccess {
        val normalized = username.trim().lowercase()
        require(normalized.matches(Regex("[a-z0-9_]{4,24}"))) { "Recipient username is invalid" }
        authorizePeer(normalized)
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
            accountKeysetAccess(documentKey,uid),
        )
    }

    /** Wraps a document key with the recipient's public account key; no private material is read. */
    private suspend fun accountKeysetAccess(documentKey:SecretKey,uid:String):AccountKeysetWrappedKey {
        val row=firestore.collection("accountKeyDirectories").document(uid).get(Source.SERVER).await()
        require(row.exists() && row.getString("ownerUid")==uid) {
            "The recipient must enable encrypted account recovery before files can be shared"
        }
        val keyId=row.getString("keyId").orEmpty()
        val epoch=row.getLong("epoch") ?: 0
        val algorithm=row.getString("algorithm").orEmpty()
        val publicKey=row.getString("publicKey").orEmpty()
        return AccountKeysetWrappedKey(
            keyId,epoch,algorithm,
            KeyManager.wrapForAccountKeyset(documentKey,KeyManager.decodePublicKey(publicKey)),
        )
    }

    /**
     * Uses this installation's key copy before the account envelope shared across installations.
     *
     * @param document Safety document metadata record.
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SecretKey] for unwrap document key.
     */
    private suspend fun unwrapDocumentKey(document: SafetyDocument, uid: String): SecretKey {
        val candidates = documentKeyCandidates(document, uid, KeyManager.deviceId())
        val key = runCatching { KeyManager.unwrapFirst(candidates, document.encryptionVersion) }.getOrNull()
        return key ?: error("The document encryption key is unavailable")
    }

    /**
     * Resolves documents.
     *
     * @param rows Collection of rows values to process.
     * @param uid Firebase UID of the target user account.
     * @return List of resulting resolve documents items.
     */
    private suspend fun resolveDocuments(rows: List<DocumentSnapshot>, uid: String): List<SafetyDocument> =
        coroutineScope {
            rows.map { row -> async(Dispatchers.IO) { resolveDocument(row, uid) } }
                .awaitAll().filterNotNull().sortedByDescending { it.createdAt }
        }

    /**
     * Resolves document.
     *
     * @param row Row supplied to [resolveDocument].
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SafetyDocument], or `null` if unavailable.
     */
    private suspend fun resolveDocument(row: DocumentSnapshot, uid: String): SafetyDocument? {
        val local = documentFrom(row, uid) ?: return null
        return local
    }

    /**
     * Converts a stored document record into the model shown by the Safe App.
     *
     * @param row Row supplied to [documentFrom].
     * @param viewerUid Firebase UID of the viewing account.
     * @param recovered Optional recovered supplied to [documentFrom].
     * @return The resulting [SafetyDocument], or `null` if unavailable.
     */
    private fun documentFrom(row: DocumentSnapshot, viewerUid: String, recovered: SecretKey? = null): SafetyDocument? {
        val owner = row.getString("owner_id") ?: return null
        val encryptedMetadata = row.getString("encrypted_metadata").orEmpty()
        val encryptionVersion = row.getLong("encryption_version")?.toInt() ?: 0
        val wrapped = row.get("wrapped_keys").cloudStringMap().orEmpty()
        val deviceWrapped = row.get("device_wrapped_keys").cloudStringMap().orEmpty()
        val accountWrapped = (row.get("account_wrapped_keys") as? Map<*, *>)?.mapNotNull { (uid, value) ->
            val id = uid as? String ?: return@mapNotNull null
            val fields = value as? Map<*, *> ?: return@mapNotNull null
            runCatching { id to AccountKeysetWrappedKey.fromMap(fields) }.getOrNull()
        }?.toMap().orEmpty()
        val deviceKey = "$viewerUid--${KeyManager.deviceId()}"
        val metadata = if (encryptedMetadata.isBlank()) null else runCatching {
            val key = recovered ?: KeyManager.unwrapFirst(
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
            allowedUsers = ((row.get("allowed_users") as? List<*>)?.filterIsInstance<String>().orEmpty() +
                if (!row.getString("emergency_id").isNullOrBlank()) listOf(viewerUid) else emptyList()).distinct(),
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
            deviceWrappedKeys = if (recovered == null) deviceWrapped else deviceWrapped +
                (deviceKey to KeyManager.wrapKey(recovered, KeyManager.getOrCreatePublicKey())),
            evidence = row.getBoolean("evidence") ?: false,
            tags = documentTagsFrom(metadata),
            accountRecoveryReady = row.getBoolean("account_recovery_ready") == true,
            accountWrappedKeys = accountWrapped,
        )
    }

    private companion object {
        const val CACHE_LIFETIME = 60L * 60L * 1000L
        /**
         * Uses one protected-storage key for this account's document access data.
         *
         * @param uid Firebase UID of the target user account.
         * @return The resulting access key.
         */
        fun accessKey(uid: String) = "access_$uid"
    }
}

private const val MAX_OCR_TEXT_LENGTH = 50_000


/** Emits the encrypted local index immediately and mutates it only from server-confirmed results. */
class IndexedSafetyDocumentRepository(
    private val remote: SafetyDocumentDataSource,
    private val index: DocumentIndexDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : SafetyDocumentDataSource {
    /**
     * Sets evidence.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param evidence Whether to apply evidence.
     */
    override suspend fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) {
        remote.setEvidence(document, ownerUid, evidence)
        // The acknowledged write must also survive an offline fallback before the listener catches up.
        val cached = index.read(ownerUid)
        if (cached.documents.any { it.id == document.id }) index.replace(
            ownerUid,
            cached.documents.map { if (it.id == document.id) it.copy(evidence = evidence) else it },
            cached.lastSyncedAt ?: clock(),
        )
    }

    /**
     * Computes list for.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [DocumentListSnapshot] for list for.
     */
    override suspend fun listFor(uid: String): DocumentListSnapshot = try {
        persist(uid, remote.listFor(uid))
    } catch (failure: Exception) {
        val local = index.read(uid)
        if (local.documents.isEmpty()) throw failure
        local.snapshot()
    }

    /**
     * Starts observing for.
     *
     * @param uid Firebase UID of the target user account.
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    override fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription {
        onUpdate(index.read(uid).snapshot())
        return remote.observeFor(uid, { incoming ->
            when (incoming.source) {
                DocumentListSource.SERVER -> onUpdate(persist(uid, incoming))
                DocumentListSource.FIRESTORE_CACHE -> {
                    val durable = index.read(uid)
                    val merged = (durable.documents + incoming.documents)
                        .associateBy(SafetyDocument::id).values.sortedByDescending(SafetyDocument::createdAt)
                    onUpdate(DocumentListSnapshot(merged, DocumentListSource.FIRESTORE_CACHE, durable.lastSyncedAt))
                }
                DocumentListSource.LOCAL_INDEX -> onUpdate(incoming)
            }
        }, { message ->
            val local = index.read(uid)
            if (local.documents.isEmpty()) onError(message) else onUpdate(local.snapshot())
        })
    }

    /**
     * Server reads never silently fall back after an authorization failure.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [getFor].
     * @return The resulting [SafetyDocument] for get for.
     */
    override suspend fun getFor(uid: String, id: String): SafetyDocument = remote.getFor(uid, id)

    /**
     * Computes cached for.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [cachedFor].
     * @return The resulting [SafetyDocument], or `null` if unavailable.
     */
    override fun cachedFor(uid: String, id: String): SafetyDocument? =
        index.read(uid).documents.firstOrNull { it.id == id && uid in it.allowedUsers }

    /**
     * Shares the indexed safety document repository.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     */
    override suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>) =
        remote.share(document, ownerUid, recipients)

    /**
     * Shares in chat.
     *
     * @param document Safety document metadata record.
     * @param outgoing Outgoing supplied to [shareInChat].
     * @param recipients Key-value mapping of recipients entries.
     */
    override suspend fun shareInChat(document: SafetyDocument, outgoing: OutgoingMessage, recipients: Map<String, String>) =
        remote.shareInChat(document, outgoing, recipients)

    /**
     * Grants access.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     * @param recipientUsername Recipient username supplied to [grantAccess].
     * @param forPlan Whether to apply for plan.
     */
    override suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
        forPlan: Boolean,
    ) = remote.grantAccess(document, ownerUid, recipientUid, recipientUsername, forPlan)

    /**
     * Revokes access.
     *
     * @param documentId Unique identifier of the safety document.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipientUid Firebase UID of the recipient account.
     */
    override suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) =
        remote.revokeAccess(documentId, ownerUid, recipientUid)

    /**
     * Deletes the indexed safety document repository.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     */
    override suspend fun delete(document: SafetyDocument, ownerUid: String) {
        remote.delete(document, ownerUid)
        index.remove(ownerUid, document.id)
    }

    /**
     * Downloads the indexed safety document repository.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param document Safety document metadata record.
     * @return The resulting [File] instance.
     */
    override suspend fun download(context: Context, document: SafetyDocument): File = remote.download(context, document)

    /**
     * Approves current account devices.
     *
     * @param document Safety document metadata record.
     * @param currentUid Current user ID supplied to [approveCurrentAccountDevices].
     * @param username Account username.
     */
    override suspend fun approveCurrentAccountDevices(document: SafetyDocument, currentUid: String, username: String) =
        remote.approveCurrentAccountDevices(document, currentUid, username)

    /**
     * Persists the indexed safety document repository.
     *
     * @param uid Firebase UID of the target user account.
     * @param snapshot Firestore or data snapshot containing the latest state.
     * @return The resulting [DocumentListSnapshot] instance.
     */
    private fun persist(uid: String, snapshot: DocumentListSnapshot): DocumentListSnapshot {
        if (snapshot.source != DocumentListSource.SERVER) return snapshot
        val saved = index.replace(uid, snapshot.documents, snapshot.lastSyncedAt ?: clock())
        return DocumentListSnapshot(saved.documents, DocumentListSource.SERVER, saved.lastSyncedAt)
    }
}

/**
 * Computes snapshot for this indexed documents.
 *
 * @return The resulting snapshot.
 */
private fun IndexedDocuments.snapshot() =
    DocumentListSnapshot(documents, DocumentListSource.LOCAL_INDEX, lastSyncedAt)
