package com.olive.app.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Reads and writes only the ARK-encrypted account-keyset envelope; it never receives an ARK. */
internal class AccountKeysetRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    /** Publishes a ciphertext-only envelope for the authenticated envelope owner. */
    suspend fun publish(envelope: AccountKeysetEnvelope) {
        require(auth.currentUser?.uid == envelope.accountId) { "Sign in again before saving account keys" }
        val payload = mapOf(
            "version" to envelope.version,
            "accountId" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "iv" to envelope.ivBase64,
            "ciphertext" to envelope.ciphertextBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        firestore.collection(COLLECTION).document(envelope.accountId).set(payload).await()
    }

    /** Fetches the latest envelope from the server, bypassing Firestore's cache for recovery. */
    suspend fun fetch(accountId: String): AccountKeysetEnvelope? {
        require(auth.currentUser?.uid == accountId) { "Sign in again before recovering account keys" }
        val snapshot = firestore.collection(COLLECTION).document(accountId).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return try {
            AccountKeysetEnvelope.fromJson(JSONObject().apply {
                snapshot.data.orEmpty().forEach { (name, value) ->
                    if (name != "updatedAt") put(name, value)
                }
            })
        } catch (failure: Exception) {
            throw AccountKeysetException("Account keyset is invalid or unavailable", failure)
        }
    }

    companion object {
        private const val COLLECTION = "accountKeysets"
    }
}
