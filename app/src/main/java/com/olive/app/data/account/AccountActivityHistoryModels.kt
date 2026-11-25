// All account roles: defines private account activity records.
package com.olive.app.data.account

import android.util.Log
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import com.olive.app.security.*
import java.time.Instant
import java.util.UUID
import kotlin.collections.get
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.json.JSONArray
import org.json.JSONObject

data class HistoryRecord(val category: String, val id: String, val json: String)

/**
 * Keep the machine timestamp for sorting and add an ISO value humans can read in Firebase Storage.
 *
 * @receiver The JSON object containing the stored fields.
 * @param name Field, key, or display name.
 * @param value Input value to store, parse, or format.
 * @return The same JSON object with the epoch and ISO timestamp fields set.
 */
private fun JSONObject.putReadableTime(name: String, value: Long) =
    put(name, value).put("${name}Iso", Instant.ofEpochMilli(value).toString())

object PrivateEntryKindSerializer : KSerializer<PrivateEntryKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PrivateEntryKind", PrimitiveKind.STRING)

    /**
     * Serializes the entry kind by name.
     *
     * @param encoder The serialization encoder that receives the value.
     * @param value The note or incident category to encode.
     */
    override fun serialize(encoder: Encoder, value: PrivateEntryKind) =
        encoder.encodeString(value.name)

    /**
     * Deserializes the entry kind, falling back to [PrivateEntryKind.Note].
     *
     * @param decoder The serialization decoder that supplies the stored value.
     * @return The decoded entry type, defaulting to Note when the stored name is unknown.
     */
    override fun deserialize(decoder: Decoder): PrivateEntryKind =
        runCatching { PrivateEntryKind.valueOf(decoder.decodeString()) }.getOrDefault(PrivateEntryKind.Note)
}

@Serializable(with = PrivateEntryKindSerializer::class)
enum class PrivateEntryKind { Note, Incident }

// TESTING: 10 seconds; change to 30.days.inWholeMilliseconds for 30 days.
val PRIVATE_ENTRY_TRASH_RETENTION_MS = 10.seconds.inWholeMilliseconds

@Serializable
data class PrivateEntryEdit(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val changes: List<FieldChange> = emptyList(),
) {
    /**
     * Account: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject().putReadableTime("timestamp", timestamp)

    companion object {
        /**
         * Rebuilds this activity record from its saved JSON fields.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): PrivateEntryEdit {
            value.getLong("timestamp")
            return value.decodeJsonObject()
        }
    }
}

// Notes and incident records get their own timestamped account objects and never enter cover data.
@Serializable
data class PrivateEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val updatedAt: Long = timestamp,
    val kind: PrivateEntryKind = PrivateEntryKind.Note,
    val title: String = "",
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
    /**
     * Account: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject =
        copy(attachmentIds = attachmentIds.distinct()).encodeToJsonObject()
            .putReadableTime("timestamp", timestamp)
            .putReadableTime("updatedAt", updatedAt)
            .also { json ->
                if (deletedAt != null) json.putReadableTime("deletedAt", deletedAt)
                if (purgedAt != null) json.putReadableTime("purgedAt", purgedAt)
            }

    companion object {
        /**
         * Rebuilds this activity record from its saved JSON fields.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): PrivateEntry {
            value.getString("id")
            val timestamp = value.getLong("timestamp")
            value.getString("body")
            val decoded = value.decodeJsonObject<PrivateEntry>()
            return decoded.copy(
                updatedAt = if (value.has("updatedAt") && !value.isNull("updatedAt")) decoded.updatedAt else timestamp,
                attachmentIds = decoded.attachmentIds.filter(String::isNotBlank).distinct(),
                incidentLatitude = decoded.incidentLatitude?.takeIf(Double::isFinite),
                incidentLongitude = decoded.incidentLongitude?.takeIf(Double::isFinite),
                edits = decoded.edits.mapNotNull { edit ->
                    edit.copy(changes = edit.changes.filterNot { it.field == "Pinned" })
                        .takeIf { it.changes.isNotEmpty() }
                },
                deletedAt = decoded.deletedAt?.takeIf { it > 0L },
                purgedAt = decoded.purgedAt?.takeIf { it > 0L },
            )
        }
    }
}

/**
 * Calculates elapsed time without letting Long boundary values wrap around.
 *
 * @param timestamp Epoch timestamp in milliseconds.
 * @param now Current epoch timestamp in milliseconds.
 * @return The computed elapsed since numeric value.
 */
private fun elapsedSince(timestamp: Long, now: Long): Long {
    if (now <= timestamp) return 0L
    return runCatching { Math.subtractExact(now, timestamp) }.getOrDefault(Long.MAX_VALUE)
}

/**
 * Returns true after a trashed entry reaches the deletion limit.
 *
 * @receiver The trashed private entry whose retention period is checked.
 * @param now Current epoch timestamp in milliseconds.
 * @return The resulting trash expired.
 */
fun PrivateEntry.trashExpired(now: Long = System.currentTimeMillis()) =
    deletedAt?.let { elapsedSince(it, now) >= PRIVATE_ENTRY_TRASH_RETENTION_MS } == true

/**
 * Calculates how many full days remain before a trashed entry is deleted.
 *
 * @receiver The trashed private entry whose retention period is checked.
 * @param now Current epoch timestamp in milliseconds.
 * @return The computed trash days remaining numeric value.
 */
fun PrivateEntry.trashDaysRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    val remaining = (PRIVATE_ENTRY_TRASH_RETENTION_MS - elapsedSince(deleted, now)).coerceAtLeast(0L)
    return ((remaining + 86_399_999L) / 86_400_000L).toInt()
}

/**
 * Calculates the remaining trash time for the live countdown.
 *
 * @receiver The trashed private entry whose retention period is checked.
 * @param now Current epoch timestamp in milliseconds.
 * @return The computed trash seconds remaining numeric value.
 */
fun PrivateEntry.trashSecondsRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    return ((PRIVATE_ENTRY_TRASH_RETENTION_MS - elapsedSince(deleted, now)).coerceAtLeast(0L) + 999L).div(
        1_000L
    ).toInt()
}

@Serializable
data class FieldChange(val field: String, val before: String = "", val after: String = "") {
    /**
     * Account: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Rebuilds this account record from protected JSON data.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): FieldChange = value.decodeJsonObject()
    }
}

// Private account activity lives in the encrypted device vault; secrets are described, never copied in.
@Serializable
data class SettingsEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val area: String,
    val action: String,
    val changes: List<FieldChange> = emptyList(),
) {
    /**
     * Converts this account record into protected JSON data.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject().putReadableTime("timestamp", timestamp)

    companion object {
        /**
         * Rebuilds this account record from protected JSON data.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): SettingsEvent {
            value.getString("id")
            value.getLong("timestamp")
            return value.decodeJsonObject()
        }
    }
}

@Serializable
data class TriggerEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val pinAttempts: Int = 0,
    val lastResult: String = "No PIN attempt",
) {
    /**
     * Converts this account record into protected JSON data.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject().putReadableTime("timestamp", timestamp)

    companion object {
        /**
         * Rebuilds this activity record from its saved JSON fields.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): TriggerEvent {
            value.getString("id")
            value.getLong("timestamp")
            return value.decodeJsonObject()
        }
    }
}

/**
 * Changes are recorded field by field so the history can show old and new values clearly.
 *
 * @param before Before supplied to [profileChanges].
 * @param after After supplied to [profileChanges].
 * @return List of resulting profile changes items.
 */
fun profileChanges(before: UserProfile, after: UserProfile): List<FieldChange> = buildList {
    /**
     * Records a setting only when the old and new values differ.
     *
     * @param field Field supplied to [changed].
     * @param old Old supplied to [changed].
     * @param new New supplied to [changed].
     */
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
    changed("Email", before.email, after.email)
    changed(
        "Email notifications",
        if (before.emailCommunicationConsent) "Enabled" else "Disabled",
        if (after.emailCommunicationConsent) "Enabled" else "Disabled",
    )
    changed("Cover app", before.coverType.title, after.coverType.title)
    if (before.triggerHash != after.triggerHash) add(
        FieldChange(
            "Trigger word",
            "Hidden",
            "Changed"
        )
    )
    if (before.safetyIntake != after.safetyIntake) add(
        FieldChange(
            "Safety plan questionnaire",
            "",
            "Updated",
        ),
    )
    if (before.contacts != after.contacts) {
        /**
         * Formats the summary shown by account storage.
         *
         * @param contacts Collection of contact records.
         * @return The resulting summary.
         */
        fun summary(contacts: List<SafeContact>) = contacts.joinToString("; ") {
            "${it.name}: ${it.details}${if (it.safeToStay) " (Safe to stay)" else ""}${if (it.linkedUid.isNotBlank()) " (Linked chat account)" else ""}"
        }
        add(FieldChange("Safe contacts", summary(before.contacts), summary(after.contacts)))
    }
}

// All account roles: loads and saves Firebase account profiles.

// Real accounts use Firebase Auth, while each user's private profile gets its own Storage file.
class FirebaseAccountRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) : AccountRepository {
    private val accountSync = AccountSyncRepository()

    /**
     * Change this path together with firebase/storage.rules and its tests.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting profile object.
     */
    private fun profileObject(uid: String) = storage.reference.child("accounts/$uid/profile.json")
    /**
     * Returns the Firebase Storage folder used by this account's history.
     *
     * @param uid Firebase UID of the target user account.
     * @param category Category supplied to [historyFolder].
     * @return The resulting history folder.
     */
    private fun historyFolder(uid: String, category: String) =
        storage.reference.child("accounts/$uid/history/$category")

    /**
     * Change this alias only with an account migration or existing usernames will stop signing in.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting [AccountIdentity] instance.
     */
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
        val createdUid = result["uid"]?.toString()?.takeIf(String::isNotBlank) ?: error("Missing account")
        val user = auth.signInWithEmailAndPassword(
            AccountPolicy.signInEmail(normalized, role),
            password
        ).await().user ?: error("Missing account")
        if (user.uid != createdUid) {
            auth.signOut()
            error("The newly created account could not be signed in.")
        }
        return AccountIdentity(user.uid, normalized)
    }

    /**
     * Account: Authenticates the account.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting [AccountIdentity] instance.
     */
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
        val claimedRole = user.getIdToken(false).await().claims["role"] as? String
        if (claimedRole != null && claimedRole != role.name) {
            auth.signOut()
            error("This account is registered under a different account type.")
        }
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

    /**
     * Loads the signed-in account profile from this repository.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return The resulting [UserProfile], or `null` if unavailable.
     */
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

    /**
     * Uploads the latest account profile to Firebase.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     */
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

    /**
     * One object per event avoids overwriting another device's history.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param record Record supplied to [saveHistoryRecord].
     */
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

    /**
     * Loads private activity-history records for this account.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return List of resulting history records items.
     */
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

    /**
     * Replaces the Firebase password after the current password is confirmed.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param currentPassword Current password supplied to [changePassword].
     * @param newPassword New password supplied to [changePassword].
     */
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

    /**
     * Account: Authenticates the accountd uid.
     *
     * @return The resulting authenticated user ID string, or `null` when unavailable.
     */
    override fun authenticatedUid(): String? = auth.currentUser?.uid
    /**
     * Checks the username and password, then returns the matching account.
     *
     * @return The resulting authenticated username string, or `null` when unavailable.
     */
    override fun authenticatedUsername(): String? =
        auth.currentUser?.displayName?.takeIf(InputPolicy::validExistingUsername)
            ?: auth.currentUser?.email?.substringBefore('@')?.let(InputPolicy::username)

    /**
     * Returns the authenticated role.
     *
     * @return The resulting [AccountRole], or `null` if unavailable.
     */
    override suspend fun authenticatedRole(): AccountRole? {
        val claim = auth.currentUser?.getIdToken(false)?.await()?.claims?.get("role")
        return AccountRole.entries.firstOrNull { it.name == claim }
    }

    /**
     * Account: Signs out the current account.
     */
    override fun signOut() = auth.signOut()

    /**
     * Records this Android installation for cross-device sign-in notifications.
     *
     * @param deviceId The ID of the device participating in the operation.
     * @param registerOnly Whether the request should only register this device.
     */
    override suspend fun recordDeviceSignIn(deviceId: String, registerOnly: Boolean) {
        functions.getHttpsCallable("recordDeviceSignIn")
            .call(mapOf("deviceId" to deviceId, "platform" to "APP", "registerOnly" to registerOnly))
            .await()
    }

    /**
     * Converts a stored activity JSON record into short text suitable for the history screen.
     *
     * @param category Category supplied to [readableHistory].
     * @param raw Raw supplied to [readableHistory].
     * @return The resulting readable history string.
     */
    private fun readableHistory(category: String, raw: String): String {
        val value = JSONObject(raw)
        return when (category) {
            "settings" -> SettingsEvent.fromJson(value).toJson()
            "triggers" -> TriggerEvent.fromJson(value).toJson()
            "private_entries" -> PrivateEntry.fromJson(value).toJson()
            else -> value
        }.toString(2)
    }

    /**
     * Converts account profile details into the JSON saved in protected storage.
     *
     * @return The resulting JSON metadata.
     */
    private fun jsonMetadata() = StorageMetadata.Builder().setContentType("application/json")
        .setCustomMetadata("schemaVersion", "2").setCustomMetadata("format", "pretty-json").build()

    /**
     * Uploads profile JSON only when its content changed.
     *
     * @param reference Reference supplied to [rewriteIfNeeded].
     * @param current Current supplied to [rewriteIfNeeded].
     * @param readable Readable supplied to [rewriteIfNeeded].
     * @param limit Limit supplied to [rewriteIfNeeded].
     */
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
    /**
     * Creates a new account with the selected role.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting [AccountIdentity] instance.
     */
    suspend fun create(username: String, password: String, role: AccountRole): AccountIdentity
    /**
     * Signs in with the supplied username and password.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting [AccountIdentity] instance.
     */
    suspend fun authenticate(username: String, password: String, role: AccountRole): AccountIdentity
    /**
     * Loads the signed-in account profile from this repository.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return The resulting [UserProfile], or `null` if unavailable.
     */
    suspend fun profile(identity: AccountIdentity): UserProfile?
    /**
     * Saves the account profile in the selected account repository.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     */
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    /**
     * Adds one account activity record to storage.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param record Record supplied to [saveHistoryRecord].
     */
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    /**
     * Loads the private activity history saved for this account.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return List of resulting history records items.
     */
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    /**
     * Re-encrypts this account after its password changes.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param currentPassword Current password supplied to [changePassword].
     * @param newPassword New password supplied to [changePassword].
     */
    suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    )

    /**
     * Confirms that the supplied password belongs to this account ID.
     *
     * @return The resulting authenticated user ID string, or `null` when unavailable.
     */
    fun authenticatedUid(): String?
    /**
     * Returns the normalized username of the signed-in account.
     *
     * @return The resulting authenticated username string, or `null` when unavailable.
     */
    fun authenticatedUsername(): String?
    /**
     * Returns the authenticated role.
     *
     * @return The resulting [AccountRole], or `null` if unavailable.
     */
    suspend fun authenticatedRole(): AccountRole? = null
    /**
     * Records one device sign-in for cross-device security alerts.
     *
     * @param deviceId The ID of the device participating in the operation.
     * @param registerOnly Whether the request should only register this device.
     */
    suspend fun recordDeviceSignIn(deviceId: String, registerOnly: Boolean = false) = Unit
    /**
     * Signs out the current account and clears its repository session.
     */
    fun signOut()
}

// Only a revision counter is stored here; profile and history contents stay in their protected Storage files.
internal class AccountSyncRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /**
     * Watches one account marker and lets the session reload the matching Storage files.
     *
     * @param uid Firebase UID of the target user account.
     * @param onChange Callback invoked when the value or observed state changes.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(uid: String, onChange: () -> Unit, onError: (Exception) -> Unit): ChatSubscription {
        val listener = firestore.collection("accountSync").document(uid)
            .addSnapshotListener { _, error ->
                if (error != null) onError(error) else onChange()
            }
        return ChatSubscription { listener.remove() }
    }

    /**
     * Raises the marker after a successful profile or history upload.
     *
     * @param uid Firebase UID of the target user account.
     */
    suspend fun notifyChanged(uid: String) {
        firestore.collection("accountSync").document(uid).set(
            mapOf("revision" to FieldValue.increment(1), "updatedAt" to FieldValue.serverTimestamp()),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }
}
