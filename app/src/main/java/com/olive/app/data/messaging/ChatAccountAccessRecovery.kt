package com.olive.app.data.messaging

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.KeyManager
import com.olive.app.security.UnlockedAccountKeyset
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await

internal data class RecoveredChatAccess(
    val conversation: ChatConversation,
    val wrappedRoomKey: String,
)

internal data class ChatAccessRecoveryResult(
    val recovered: List<RecoveredChatAccess>,
    val failedIds: Set<String>,
)

/** Restores current-device chat envelopes without reading or downloading message ciphertext. */
internal class ChatAccountAccessRecovery(
    private val firestore: FirebaseFirestore,
) {
    /**
     * Finds conversations whose account wrapper can restore a missing current-device room key.
     *
     * @param conversations Conversations currently visible to the signed-in account.
     * @param currentUid Active Firebase account identifier.
     * @return Server-confirmed conversations that can be recovered without another device.
     */
    suspend fun candidates(
        conversations: List<ChatConversation>,
        currentUid: String,
    ): List<ChatConversation> = coroutineScope {
        val deviceKeyId = "$currentUid--${KeyManager.deviceId()}"
        val gate = Semaphore(4)
        conversations.distinctBy(ChatConversation::id).map { conversation ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val snapshot = firestore.collection("conversations").document(conversation.id)
                            .get(Source.SERVER).await()
                        val participants = snapshot.participantIds()
                        if (currentUid !in participants || participants != conversation.participantIds.toSet()) {
                            return@runCatching null
                        }
                        if (snapshot.accountRecoveryKeys()[currentUid] == null) return@runCatching null
                        val deviceKey = snapshot.reference.collection("deviceKeys")
                            .document(deviceKeyId).get(Source.SERVER).await()
                        conversation.takeIf { deviceKey.getString("wrappedKey").isNullOrBlank() }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Restores only this device's room-key envelopes with one unlocked account keyset.
     *
     * @param conversations Server-visible conversations selected for recovery.
     * @param currentUid Active Firebase account identifier.
     * @param keyset Account private key held only for this foreground recovery operation.
     * @return Successfully recovered cache access and isolated conversation failures.
     */
    suspend fun restore(
        conversations: List<ChatConversation>,
        currentUid: String,
        keyset: UnlockedAccountKeyset,
    ): ChatAccessRecoveryResult = coroutineScope {
        val localPublicKey = KeyManager.getOrCreatePublicKey()
        val encodedLocalKey = Base64.getEncoder().encodeToString(localPublicKey.encoded)
        val deviceId = KeyManager.deviceId(localPublicKey)
        val gate = Semaphore(4)
        val candidates = conversations.distinctBy(ChatConversation::id)
        val outcomes = candidates.map { conversation ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val reference = firestore.collection("conversations").document(conversation.id)
                        val snapshot = reference.get(Source.SERVER).await()
                        val participants = snapshot.participantIds()
                        check(currentUid in participants && participants == conversation.participantIds.toSet()) {
                            "Conversation membership changed"
                        }
                        val currentDevice = reference.collection("deviceKeys")
                            .document("$currentUid--$deviceId")
                        currentDevice.get(Source.SERVER).await().getString("wrappedKey")
                            ?.takeIf(String::isNotBlank)?.let { existing ->
                                return@runCatching RecoveredChatAccess(conversation, existing)
                            }
                        val roomKey = checkNotNull(snapshot.accountRecoveryKeys()[currentUid]).unwrap(keyset)
                        val username = checkNotNull(conversation.participantUsernames[currentUid])
                            .trim().lowercase()
                        val device = firestore.collection("messageDirectory").document(username)
                            .collection("devices").document(deviceId).get(Source.SERVER).await()
                        check(device.getString("uid") == currentUid && device.getString("deviceId") == deviceId) {
                            "Current device is not registered for this account"
                        }
                        check(device.getString("publicKey") == encodedLocalKey) {
                            "Registered device key does not match this device"
                        }
                        val digest = if (device.getString("mgf1Digest") == "SHA-256") {
                            MGF1ParameterSpec.SHA256
                        } else MGF1ParameterSpec.SHA1
                        val wrapped = KeyManager.wrapKey(roomKey, localPublicKey, digest)
                        currentDevice.set(
                            mapOf(
                                "uid" to currentUid,
                                "deviceId" to deviceId,
                                "publicKey" to encodedLocalKey,
                                "wrappedKey" to wrapped,
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                        ).await()
                        RecoveredChatAccess(conversation, wrapped)
                    }
                }
            }
        }.awaitAll()
        val recovered = outcomes.mapNotNull(Result<RecoveredChatAccess>::getOrNull)
        val restoredIds = recovered.mapTo(mutableSetOf()) { it.conversation.id }
        ChatAccessRecoveryResult(
            recovered = recovered,
            failedIds = candidates.mapTo(mutableSetOf(), ChatConversation::id) - restoredIds,
        )
    }

    private fun DocumentSnapshot.participantIds(): Set<String> =
        (get("participantIds") as? List<*>).orEmpty().filterIsInstance<String>().toSet()

    private fun DocumentSnapshot.accountRecoveryKeys(): Map<String, AccountKeysetWrappedKey> =
        (get("accountRoomKeys") as? Map<*, *>)?.mapNotNull { (uid, value) ->
            val owner = uid as? String ?: return@mapNotNull null
            val fields = value as? Map<*, *> ?: return@mapNotNull null
            runCatching { owner to AccountKeysetWrappedKey.fromMap(fields) }.getOrNull()
        }?.toMap().orEmpty()
}
