package com.olive.app.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
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
        val directory = mapOf(
            "ownerUid" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        firestore.batch().apply {
            set(firestore.collection(COLLECTION).document(envelope.accountId), payload, SetOptions.merge())
            set(firestore.collection(DIRECTORY).document(envelope.accountId), directory)
        }.commit().await()
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

    suspend fun isPasswordRecoveryEnrolled(accountId: String): Boolean {
        require(auth.currentUser?.uid == accountId) { "Sign in again before checking recovery" }
        val snapshot = firestore.collection(COLLECTION).document(accountId).get(Source.SERVER).await()
        if (!snapshot.exists()) return false
        val data = snapshot.data ?: return false
        return data["passwordRecovery"] is Map<*, *>
    }

    companion object {
        private const val COLLECTION = "accountKeysets"
        private const val DIRECTORY = "accountKeyDirectories"
    }
}

internal fun interface AccountRecoveryCallable {
    suspend fun call(name: String, data: Map<String, Any?>): Any?
}

/** Confirms a sensitive recovery-factor action with Firebase without exposing a password to a callable. */
internal fun interface AccountRecoveryAuthenticator {
    suspend fun reauthenticate(accountId: String, password: String)
}

internal class FirebaseAccountRecoveryAuthenticator(
    private val authProvider: () -> FirebaseAuth = FirebaseAuth::getInstance,
) : AccountRecoveryAuthenticator {
    override suspend fun reauthenticate(accountId: String, password: String) {
        require(password.isNotEmpty()) { "Enter your account password" }
        val auth = authProvider()
        val user = auth.currentUser ?: error("Sign in again before managing account recovery")
        check(user.uid == accountId) { "Sign in again before managing account recovery" }
        val email = user.email ?: error("This account has no sign-in email")
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        // Force the newly-issued auth_time claim into the callable's token.
        user.getIdToken(true).await()
    }
}

internal class FirebaseAccountRecoveryCallable(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) : AccountRecoveryCallable {
    override suspend fun call(name: String, data: Map<String, Any?>): Any? =
        functions.getHttpsCallable(name).call(data).await().data
}
