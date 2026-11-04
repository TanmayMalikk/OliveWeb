// All account roles: loads and saves Firebase account profiles.
package com.olive.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.functions.FirebaseFunctions
import android.util.Log
import com.olive.app.data.account.AccountSyncRepository
import com.olive.app.security.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

// Real accounts use Firebase Auth, while each user's private profile gets its own Storage file.
class FirebaseAccountRepository : AccountRepository {
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val functions = FirebaseFunctions.getInstance("northamerica-northeast1")
    private val accountSync = AccountSyncRepository()

    // Change this path together with firebase/storage.rules and its tests.
    private fun profileObject(uid: String) = storage.reference.child("accounts/$uid/profile.json")
    // Returns the Firebase Storage folder used by this account's history.
    private fun historyFolder(uid: String, category: String) =
        storage.reference.child("accounts/$uid/history/$category")

    // Change this alias only with an account migration or existing usernames will stop signing in.
    override suspend fun create(
        username: String,
        password: String,
        role: AccountRole
    ): AccountIdentity {
        require(InputPolicy.validUsername(username)) { "Invalid username" }
        require(InputPolicy.validAccountPassword(password, username)) { "Invalid password" }
        require(role == AccountRole.USER || role == AccountRole.SAFE_CONTACT) {
            "Staff and Admin accounts must be created by an administrator"
        }
        val normalized = InputPolicy.username(username)
        val result = functions.getHttpsCallable("createPublicAccount").call(
            mapOf("username" to normalized, "password" to password, "role" to role.name)
        ).await().data as? Map<*, *> ?: error("Missing account")
        check(result["uid"]?.toString()?.isNotBlank() == true) { "Missing account" }
        val user = auth.signInWithEmailAndPassword(
            AccountPolicy.signInEmail(normalized, role),
            password
        ).await().user ?: error("Missing account")
        return AccountIdentity(user.uid, normalized)
    }

    // Account: Authenticates the account.
    override suspend fun authenticate(
        username: String,
        password: String,
        role: AccountRole
    ): AccountIdentity {
        val primary = AccountPolicy.signInEmail(username, role)
        val user = try {
            auth.signInWithEmailAndPassword(primary, password).await().user
        } catch (primaryError: Exception) {
            // Staff and admin email sign-ins can use their protected organization username as an alias.
            if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
                val result = functions.getHttpsCallable("resolveOrganizationLogin")
                    .call(mapOf("identifier" to username.trim().lowercase(), "role" to role.name))
                    .await().data as? Map<*, *>
                val alias = result?.get("authEmail")?.toString().orEmpty()
                if (alias.isBlank()) throw primaryError
                auth.signInWithEmailAndPassword(alias, password).await().user
            } else {
                // Accounts created before role aliases existed used the User domain; keep those logins working during migration.
                if (role == AccountRole.USER) throw primaryError
                auth.signInWithEmailAndPassword(AccountPolicy.legacyUserEmail(username), password)
                    .await().user
            }
        } ?: error("Missing account")
        // Sign-in has already succeeded. Refreshing the older email alias must not block access if that optional update fails.
        if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
            runCatching {
                functions.getHttpsCallable("restoreOrganizationLoginAlias").call().await()
                user.reload().await()
            }.onFailure { Log.w("OliveAuth", "Organization login alias refresh failed", it) }
        }
        val accountName = user.displayName?.takeIf(InputPolicy::validExistingUsername)
            ?: user.email?.substringBefore('@')?.let(InputPolicy::username).orEmpty()
        return AccountIdentity(user.uid, accountName)
    }

    // Loads the signed-in account profile from this repository.
    override suspend fun profile(identity: AccountIdentity): UserProfile? {
        check(auth.currentUser?.uid == identity.uid) { "Sign in again before syncing" }
        return try {
            val bytes = profileObject(identity.uid).getBytes(MAX_PROFILE_BYTES).await()
            // Turn an unexpected profile failure into a readable message.
            try {
                val current = String(bytes, Charsets.UTF_8)
                val profile = UserProfile.fromJson(JSONObject(current))
                val readable = profile.toJson().toString(2)
                runCatching {
                    rewriteIfNeeded(
                        profileObject(identity.uid),
                        current,
                        readable,
                        MAX_PROFILE_BYTES.toInt()
                    )
                }
                profile
            } finally {
                bytes.fill(0)
            }
        } catch (error: StorageException) {
            if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null else throw error
        }
    }

    // Uploads the latest account profile to Firebase.
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) {
        check(auth.currentUser?.uid == identity.uid) { "Sign in again before syncing" }
        check(profile.username == identity.username) { "Profile account mismatch" }
        // Pretty JSON stays structured for the app while remaining easy to inspect in Firebase Storage.
        val bytes = profile.toJson().toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_PROFILE_BYTES) { "Profile is too large" }
        try {
            profileObject(identity.uid).putBytes(bytes, jsonMetadata()).await()
            accountSync.notifyChanged(identity.uid)
        } finally {
            bytes.fill(0)
        }
    }

    // One object per event avoids overwriting another device's history.
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) {
        check(auth.currentUser?.uid == identity.uid)
        require(record.category in setOf("settings", "triggers", "checkouts", "private_entries"))
        require(record.id.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val bytes = JSONObject(record.json).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EVENT_BYTES)
        // Use the fallback when save history record is unavailable.
        try {
            historyFolder(identity.uid, record.category).child("${record.id}.json")
                .putBytes(bytes, jsonMetadata()).await()
            accountSync.notifyChanged(identity.uid)
        } finally {
            bytes.fill(0)
        }
    }

    // Loads private activity-history records for this account.
    override suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord> {
        check(auth.currentUser?.uid == identity.uid)
        return listOf("settings", "triggers", "checkouts", "private_entries").flatMap { category ->
            historyFolder(identity.uid, category).listAll().await().items.map { item ->
                val bytes = item.getBytes(MAX_EVENT_BYTES.toLong()).await()
                // Turn an unexpected history records failure into a readable message.
                try {
                    val current = String(bytes, Charsets.UTF_8)
                    val readable = readableHistory(category, current)
                    runCatching { rewriteIfNeeded(item, current, readable, MAX_EVENT_BYTES) }
                    HistoryRecord(category, item.name.removeSuffix(".json"), readable)
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }

    // Replaces the Firebase password after the current password is confirmed.
    override suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    ) {
        require(
            InputPolicy.validAccountPassword(
                newPassword,
                identity.username
            )
        ) { "Invalid password" }
        val user = auth.currentUser ?: error("Sign in again before changing your password")
        check(user.uid == identity.uid) { "Sign in again before changing your password" }
        val currentEmail = user.email ?: error("This account has no sign-in email")
        user.reauthenticate(EmailAuthProvider.getCredential(currentEmail, currentPassword)).await()
        user.updatePassword(newPassword).await()
    }

    // Account: Authenticates the accountd uid.
    override fun authenticatedUid(): String? = auth.currentUser?.uid
    // Checks the username and password, then returns the matching account.
    override fun authenticatedUsername(): String? =
        auth.currentUser?.displayName?.takeIf(InputPolicy::validExistingUsername)
            ?: auth.currentUser?.email?.substringBefore('@')?.let(InputPolicy::username)

    // Account: Signs out the current account.
    override fun signOut() = auth.signOut()

    // Converts a stored activity JSON record into short text suitable for the history screen.
    private fun readableHistory(category: String, raw: String): String {
        val value = JSONObject(raw)
        return when (category) {
            "settings" -> SettingsEvent.fromJson(value).toJson()
            "triggers" -> TriggerEvent.fromJson(value).toJson()
            "checkouts" -> CheckoutEvent.fromJson(value).toJson()
            "private_entries" -> PrivateEntry.fromJson(value).toJson()
            else -> value
        }.toString(2)
    }

    // Converts account profile details into the JSON saved in protected storage.
    private fun jsonMetadata() = StorageMetadata.Builder().setContentType("application/json")
        .setCustomMetadata("schemaVersion", "2").setCustomMetadata("format", "pretty-json").build()

    // Uploads profile JSON only when its content changed.
    private suspend fun rewriteIfNeeded(
        reference: StorageReference,
        current: String,
        readable: String,
        limit: Int
    ) {
        if (current == readable) return
        val bytes = readable.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit)
        try {
            reference.putBytes(bytes, jsonMetadata()).await()
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val MAX_PROFILE_BYTES = 64L * 1024L
        const val MAX_EVENT_BYTES = 64 * 1024
    }
}
