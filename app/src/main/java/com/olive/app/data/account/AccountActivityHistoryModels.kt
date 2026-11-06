// All account roles: defines private account activity records.
package com.olive.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
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


data class HistoryRecord(val category: String, val id: String, val json: String)

// Keep the machine timestamp for sorting and add an ISO value humans can read in Firebase Storage.
private fun JSONObject.putReadableTime(name: String, value: Long) =
    put(name, value).put("${name}Iso", Instant.ofEpochMilli(value).toString())

enum class PrivateEntryKind { Note, Incident }

// TESTING: 10 seconds; change to 30.days.inWholeMilliseconds for 30 days.
val PRIVATE_ENTRY_TRASH_RETENTION_MS = 10.seconds.inWholeMilliseconds

data class PrivateEntryEdit(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val changes: List<FieldChange>,
) {
    // Account: Converts this record into JSON.
    fun toJson(): JSONObject = JSONObject().put("id", id).putReadableTime("timestamp", timestamp)
        .put("changes", JSONArray().also { rows -> changes.forEach { rows.put(it.toJson()) } })

    companion object {
        // Rebuilds this activity record from its saved JSON fields.
        fun fromJson(value: JSONObject): PrivateEntryEdit {
            val rows = value.optJSONArray("changes") ?: JSONArray()
            return PrivateEntryEdit(
                value.optString("id", UUID.randomUUID().toString()), value.getLong("timestamp"),
                (0 until rows.length()).map { FieldChange.fromJson(rows.getJSONObject(it)) })
        }
    }
}

// Notes and incident records get their own timestamped account objects and never enter cover data.
data class PrivateEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val updatedAt: Long = timestamp,
    val kind: PrivateEntryKind = PrivateEntryKind.Note,
    val title: String,
    val body: String,
    val incidentDate: String = "",
    val incidentTime: String = "",
    val incidentLocation: String = "",
    val incidentLatitude: Double? = null,
    val incidentLongitude: Double? = null,
    val attachmentIds: List<String> = emptyList(),
    val pinned: Boolean = false,
    val edits: List<PrivateEntryEdit> = emptyList(),
    val deletedAt: Long? = null,
    val purgedAt: Long? = null,
) {
    // Account: Converts this record into JSON.
    fun toJson(): JSONObject = JSONObject().put("id", id).putReadableTime("timestamp", timestamp)
        .putReadableTime("updatedAt", updatedAt)
        .put("kind", kind.name).put("title", title).put("body", body).put("pinned", pinned)
        .put("incidentDate", incidentDate).put("incidentTime", incidentTime)
        .put("incidentLocation", incidentLocation)
        .put("attachmentIds", JSONArray().also { rows -> attachmentIds.distinct().forEach(rows::put) })
        // Add each changed profile field to the saved history record.
        .put("edits", JSONArray().also { rows -> edits.forEach { rows.put(it.toJson()) } })
        .also {
            incidentLatitude?.let { latitude -> it.put("incidentLatitude", latitude) }
            incidentLongitude?.let { longitude -> it.put("incidentLongitude", longitude) }
            if (deletedAt != null) it.putReadableTime("deletedAt", deletedAt)
            if (purgedAt != null) it.putReadableTime("purgedAt", purgedAt)
        }

    companion object {
        // Rebuilds this activity record from its saved JSON fields.
        fun fromJson(value: JSONObject): PrivateEntry {
            val edits = value.optJSONArray("edits") ?: JSONArray()
            val attachments = value.optJSONArray("attachmentIds") ?: JSONArray()
            return PrivateEntry(
                id = value.getString("id"),
                timestamp = value.getLong("timestamp"),
                updatedAt = value.optLong("updatedAt", value.getLong("timestamp")),
                kind = value.optString("kind").takeIf(String::isNotBlank)
                    ?.let { runCatching { PrivateEntryKind.valueOf(it) }.getOrNull() }
                    ?: PrivateEntryKind.Note,
                title = value.optString("title"),
                body = value.getString("body"),
                incidentDate = value.optString("incidentDate"),
                incidentTime = value.optString("incidentTime"),
                incidentLocation = value.optString("incidentLocation"),
                incidentLatitude = value.optDouble("incidentLatitude", Double.NaN).takeIf(Double::isFinite),
                incidentLongitude = value.optDouble("incidentLongitude", Double.NaN).takeIf(Double::isFinite),
                attachmentIds = (0 until attachments.length()).mapNotNull { index ->
                    attachments.optString(index).takeIf(String::isNotBlank)
                }.distinct(),
                pinned = value.optBoolean("pinned"),
                edits = (0 until edits.length()).map {
                    PrivateEntryEdit.fromJson(
                        edits.getJSONObject(
                            it
                        )
                    )
                }
                    .mapNotNull { edit ->
                        edit.copy(changes = edit.changes.filterNot { it.field == "Pinned" })
                            .takeIf { it.changes.isNotEmpty() }
                    },
                deletedAt = value.optLong("deletedAt").takeIf { it > 0L },
                purgedAt = value.optLong("purgedAt").takeIf { it > 0L },
            )
        }
    }
}

// Calculates elapsed time without letting Long boundary values wrap around.
private fun elapsedSince(timestamp: Long, now: Long): Long {
    if (now <= timestamp) return 0L
    return runCatching { Math.subtractExact(now, timestamp) }.getOrDefault(Long.MAX_VALUE)
}

// Returns true after a trashed entry reaches the deletion limit.
fun PrivateEntry.trashExpired(now: Long = System.currentTimeMillis()) =
    deletedAt?.let { elapsedSince(it, now) >= PRIVATE_ENTRY_TRASH_RETENTION_MS } == true

// Calculates how many full days remain before a trashed entry is deleted.
fun PrivateEntry.trashDaysRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    val remaining = (PRIVATE_ENTRY_TRASH_RETENTION_MS - elapsedSince(deleted, now)).coerceAtLeast(0L)
    return ((remaining + 86_399_999L) / 86_400_000L).toInt()
}

// Calculates the remaining trash time for the live countdown.
fun PrivateEntry.trashSecondsRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    return ((PRIVATE_ENTRY_TRASH_RETENTION_MS - elapsedSince(deleted, now)).coerceAtLeast(0L) + 999L).div(
        1_000L
    ).toInt()
}

data class FieldChange(val field: String, val before: String, val after: String) {
    // Account: Converts this record into JSON.
    fun toJson(): JSONObject =
        JSONObject().put("field", field).put("before", before).put("after", after)

    companion object {
        // Rebuilds this account record from protected JSON data.
        fun fromJson(value: JSONObject) = FieldChange(
            value.getString("field"),
            value.optString("before"),
            value.optString("after")
        )
    }
}

// Private account activity lives in the encrypted device vault; secrets are described, never copied in.
data class SettingsEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val area: String,
    val action: String,
    val changes: List<FieldChange> = emptyList(),
) {
    // Converts this account record into protected JSON data.
    fun toJson(): JSONObject =
        JSONObject().put("id", id).putReadableTime("timestamp", timestamp).put("area", area)
            .put("action", action)
            .put("changes", JSONArray().also { rows -> changes.forEach { rows.put(it.toJson()) } })

    companion object {
        // Rebuilds this account record from protected JSON data.
        fun fromJson(value: JSONObject): SettingsEvent {
            val rows = value.optJSONArray("changes") ?: JSONArray()
            return SettingsEvent(
                value.getString("id"),
                value.getLong("timestamp"),
                value.getString("area"),
                value.getString("action"),
                (0 until rows.length()).map { FieldChange.fromJson(rows.getJSONObject(it)) })
        }
    }
}

data class TriggerEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val pinAttempts: Int = 0,
    val lastResult: String = "No PIN attempt",
) {
    // Converts this account record into protected JSON data.
    fun toJson(): JSONObject =
        JSONObject().put("id", id).putReadableTime("timestamp", timestamp).put("source", source)
            .put("pinAttempts", pinAttempts).put("lastResult", lastResult)

    companion object {
        // Rebuilds this activity record from its saved JSON fields.
        fun fromJson(value: JSONObject) = TriggerEvent(
            value.getString("id"),
            value.getLong("timestamp"),
            value.getString("source"),
            value.optInt("pinAttempts"),
            value.optString("lastResult", "No PIN attempt")
        )
    }
}

// Changes are recorded field by field so the history can show old and new values clearly.
fun profileChanges(before: UserProfile, after: UserProfile): List<FieldChange> = buildList {
    // Records a setting only when the old and new values differ.
    fun changed(field: String, old: String, new: String) {
        if (old != new) add(FieldChange(field, old, new))
    }
    changed("Full name", before.fullName, after.fullName)
    changed("People experiencing abuse", before.affectedNames, after.affectedNames)
    changed("Abuser's name", before.abuserName, after.abuserName)
    changed("Date of birth", before.dob, after.dob)
    changed("Language", before.language, after.language)
    changed("Situation", before.situation, after.situation)
    changed("City", before.address.city, after.address.city)
    changed("Street address", before.address.line1, after.address.line1)
    changed("Apartment / unit", before.address.line2, after.address.line2)
    changed("Province / state", before.address.province, after.address.province)
    changed("Postal / ZIP", before.address.postalCode, after.address.postalCode)
    changed("Country", before.address.country, after.address.country)
    changed("Cover app", before.coverType.title, after.coverType.title)
    if (before.triggerHash != after.triggerHash) add(
        FieldChange(
            "Trigger word",
            "Hidden",
            "Changed"
        )
    )
    if (before.contacts != after.contacts) {
        // Formats the summary shown by account storage.
        fun summary(contacts: List<SafeContact>) = contacts.joinToString("; ") {
            "${it.name}: ${it.details}${if (it.safeToStay) " (Safe to stay)" else ""}${if (it.linkedUid.isNotBlank()) " (Linked chat account)" else ""}"
        }
        add(FieldChange("Safe contacts", summary(before.contacts), summary(after.contacts)))
    }
}

// All account roles: loads and saves Firebase account profiles.

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
        require(record.category in setOf("settings", "triggers", "private_entries"))
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
        return listOf("settings", "triggers", "private_entries").flatMap { category ->
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

// All account roles: loads and saves account profiles.

// The UI talks to this one doorway whether accounts are local test accounts or real Firebase accounts.
interface AccountRepository {
    // Creates a new account with the selected role.
    suspend fun create(username: String, password: String, role: AccountRole): AccountIdentity
    // Signs in with the supplied username and password.
    suspend fun authenticate(username: String, password: String, role: AccountRole): AccountIdentity
    // Loads the signed-in account profile from this repository.
    suspend fun profile(identity: AccountIdentity): UserProfile?
    // Saves the account profile in the selected account repository.
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    // Adds one account activity record to storage.
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    // Loads the private activity history saved for this account.
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    // Re-encrypts this account after its password changes.
    suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    )

    // Confirms that the supplied password belongs to this account ID.
    fun authenticatedUid(): String?
    // Returns the normalized username of the signed-in account.
    fun authenticatedUsername(): String?
    // Signs out the current account and clears its repository session.
    fun signOut()
}
