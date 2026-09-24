package com.olive.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

// The UI talks to this one doorway whether accounts are local test accounts or real Firebase accounts.
interface AccountRepository {
    suspend fun create(username: String, password: String): AccountIdentity
    suspend fun authenticate(username: String, password: String): AccountIdentity
    suspend fun profile(identity: AccountIdentity): UserProfile?
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    suspend fun changePassword(identity: AccountIdentity, currentPassword: String, newPassword: String)
    fun signOut()
}

// Handy for emulator testing: same account flow, but nothing leaves this installation.
class LocalAccountRepository(private val vault: EncryptedVault) : AccountRepository {
    override suspend fun create(username: String, password: String) = withContext(Dispatchers.IO) {
        val identity = AccountIdentity(UUID.randomUUID().toString(), InputPolicy.username(username))
        val verifier = SecretVerifier.create(password)
        vault.update { root ->
            val accounts = root.child("accounts")
            check(!accounts.has(identity.username)) { "Account unavailable" }
            accounts.put(identity.username, JSONObject().put("uid",identity.uid).put("passwordVerifier",verifier))
        }
        identity
    }
    override suspend fun authenticate(username: String, password: String) = withContext(Dispatchers.IO) {
        val normalized = InputPolicy.username(username)
        val account = vault.read().optJSONObject("accounts")?.optJSONObject(normalized)
        // Perform the KDF for unknown usernames too, avoiding a cheap username timing oracle.
        val verifier = account?.getString("passwordVerifier") ?: SecretVerifier.create("unavailable-account")
        check(SecretVerifier.matches(password, verifier) && account != null) { "Sign-in failed" }
        AccountIdentity(account!!.getString("uid"), normalized)
    }
    override suspend fun profile(identity: AccountIdentity) = withContext(Dispatchers.IO) {
        vault.read().optJSONObject("profiles")?.optJSONObject(identity.uid)?.let(UserProfile::fromJson)
    }
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) = withContext(Dispatchers.IO) {
        vault.update { it.child("profiles").put(identity.uid,profile.toJson()) }
    }
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) = Unit
    override suspend fun historyRecords(identity: AccountIdentity) = emptyList<HistoryRecord>()
    override suspend fun changePassword(identity: AccountIdentity, currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        vault.update { root ->
            val account=root.getJSONObject("accounts").getJSONObject(identity.username)
            check(account.getString("uid")==identity.uid && SecretVerifier.matches(currentPassword,account.getString("passwordVerifier"))) { "Current password is incorrect" }
            account.put("passwordVerifier",SecretVerifier.create(newPassword))
        }
    }
    override fun signOut() = Unit
}

// Real accounts use Firebase Auth, while each user's private profile gets its own Storage file.
class FirebaseAccountRepository : AccountRepository {
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()
    // Change this path together with firebase/storage.rules and its tests.
    private fun profileObject(uid: String) = storage.reference.child("accounts/$uid/profile.json")
    private fun historyFolder(uid: String, category: String) = storage.reference.child("accounts/$uid/history/$category")
    // Change this alias only with an account migration or existing usernames will stop signing in.
    private fun email(username: String) = "${InputPolicy.username(username)}@users.olive.invalid"
    override suspend fun create(username: String, password: String): AccountIdentity {
        val user = auth.createUserWithEmailAndPassword(email(username),password).await().user ?: error("Missing account")
        return AccountIdentity(user.uid,InputPolicy.username(username))
    }
    override suspend fun authenticate(username: String, password: String): AccountIdentity {
        val user = auth.signInWithEmailAndPassword(email(username),password).await().user ?: error("Missing account")
        return AccountIdentity(user.uid,InputPolicy.username(username))
    }
    override suspend fun profile(identity: AccountIdentity): UserProfile? {
        check(auth.currentUser?.uid == identity.uid) { "Sign in again before syncing" }
        return try {
            val bytes = profileObject(identity.uid).getBytes(MAX_PROFILE_BYTES).await()
            try { UserProfile.fromJson(JSONObject(String(bytes, Charsets.UTF_8))) }
            finally { bytes.fill(0) }
        } catch (error: StorageException) {
            if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null else throw error
        }
    }
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) {
        check(auth.currentUser?.uid == identity.uid) { "Sign in again before syncing" }
        check(profile.username == identity.username) { "Profile account mismatch" }
        val bytes = profile.toJson().toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_PROFILE_BYTES) { "Profile is too large" }
        try {
            val metadata = StorageMetadata.Builder()
                .setContentType("application/json")
                .setCustomMetadata("schemaVersion", "1")
                .build()
            profileObject(identity.uid).putBytes(bytes, metadata).await()
        } finally { bytes.fill(0) }
    }
    // One object per event avoids overwriting another device's history.
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) {
        check(auth.currentUser?.uid==identity.uid)
        require(record.category in setOf("settings","triggers","checkouts"))
        require(record.id.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val bytes=record.json.toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_EVENT_BYTES)
        try {
            historyFolder(identity.uid,record.category).child("${record.id}.json").putBytes(bytes,
                StorageMetadata.Builder().setContentType("application/json").build()).await()
        } finally { bytes.fill(0) }
    }
    override suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord> {
        check(auth.currentUser?.uid==identity.uid)
        return listOf("settings","triggers","checkouts").flatMap { category ->
            historyFolder(identity.uid,category).listAll().await().items.map { item ->
                val bytes=item.getBytes(MAX_EVENT_BYTES.toLong()).await()
                try { HistoryRecord(category,item.name.removeSuffix(".json"),String(bytes,Charsets.UTF_8)) }
                finally { bytes.fill(0) }
            }
        }
    }
    override suspend fun changePassword(identity: AccountIdentity, currentPassword: String, newPassword: String) {
        val user=auth.currentUser ?: error("Sign in again before changing your password")
        check(user.uid==identity.uid) { "Sign in again before changing your password" }
        user.reauthenticate(EmailAuthProvider.getCredential(email(identity.username),currentPassword)).await()
        user.updatePassword(newPassword).await()
    }
    override fun signOut() = auth.signOut()

    private companion object { const val MAX_PROFILE_BYTES = 64L * 1024L; const val MAX_EVENT_BYTES = 64 * 1024 }
}
