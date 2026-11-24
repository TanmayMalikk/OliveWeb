package com.olive.app.ui.users.user.documents

import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.withDecryptedMetadata
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.security.UnlockedAccountKeyset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal data class MediaAccessRecoveryResult(
    val documents: List<SafetyDocument>,
    val failedIds: Set<String>,
    val pendingSyncIds: Set<String>,
)

/**
 * Selects authorized media whose account wrapper can provision the current device.
 *
 * @param documents Encrypted media records visible to the account.
 * @param viewerUid Active Firebase account identifier.
 * @param deviceId Stable identifier derived from the current device encryption key.
 * @return Distinct media records missing this device's wrapped DEK.
 */
internal fun recoverableMediaDocuments(
    documents: List<SafetyDocument>,
    viewerUid: String,
    deviceId: String,
): List<SafetyDocument> = documents.filter { document ->
    document.objectKey.isNotBlank() &&
        viewerUid in document.allowedUsers &&
        document.accountWrappedKeys[viewerUid] != null &&
        document.deviceWrappedKeys["$viewerUid--$deviceId"].isNullOrBlank()
}.distinctBy(SafetyDocument::id)

/** Restores media DEKs for only the current device while keeping payload bytes untouched. */
internal class EncryptedMediaAccessRecovery(
    private val repository: MediaTicketDataSource,
) {
    /**
     * Restores authenticated metadata and device wrappers with one unlocked account keyset.
     *
     * @param documents Authorized encrypted media records visible to the account.
     * @param viewerUid Active Firebase account identifier.
     * @param keyset Account private key unlocked for this foreground recovery operation.
     * @return Locally restored records, rejected IDs, and wrappers awaiting server synchronization.
     */
    suspend fun restore(
        documents: List<SafetyDocument>,
        viewerUid: String,
        keyset: UnlockedAccountKeyset,
    ): MediaAccessRecoveryResult = coroutineScope {
        val deviceId = KeyManager.deviceId()
        val deviceMapKey = "$viewerUid--$deviceId"
        val publicKey = KeyManager.getOrCreatePublicKey()
        val gate = Semaphore(4)
        val candidates = recoverableMediaDocuments(documents, viewerUid, deviceId)
        val outcomes = candidates.map { document ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val accountWrapper = checkNotNull(document.accountWrappedKeys[viewerUid])
                        check(accountWrapper.keyId == keyset.keyId && accountWrapper.epoch == keyset.epoch) {
                            "Account keyset does not match encrypted media"
                        }
                        val documentKey = accountWrapper.unwrap(keyset)
                        val deviceWrapper = KeyManager.wrapKey(documentKey, publicKey)
                        val metadataResolved = if (document.encryptedMetadata.isBlank()) document else {
                            document.withDecryptedMetadata(
                                JSONObject(EncryptionUtils.decryptText(document.encryptedMetadata, documentKey)),
                            )
                        }
                        val resolved = metadataResolved.copy(
                            deviceWrappedKeys = document.deviceWrappedKeys + (deviceMapKey to deviceWrapper),
                        )
                        val synced = runCatching {
                            repository.updateDeviceKeys(document.id, mapOf(deviceMapKey to deviceWrapper))
                        }.isSuccess
                        Triple(resolved, synced, document.id)
                    }
                }
            }
        }.awaitAll()
        val restored = outcomes.mapNotNull { it.getOrNull() }
        val restoredIds = restored.mapTo(mutableSetOf()) { it.third }
        MediaAccessRecoveryResult(
            documents = restored.map { it.first },
            failedIds = candidates.mapTo(mutableSetOf(), SafetyDocument::id) - restoredIds,
            pendingSyncIds = restored.filterNot { it.second }.mapTo(mutableSetOf()) { it.third },
        )
    }
}
