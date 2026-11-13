// Users and safe contacts: defines their profile details.
package com.olive.app.data.account

import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName


// A trusted person can optionally be linked to one explicitly accepted chat friend.
data class SafeContact(
    val id: String,
    val name: String,
    val details: String,
    val safeToStay: Boolean = false,
    val linkedUid: String = ""
)

// The account role decides which onboarding flow and app shell this person receives.
enum class AccountRole(val title: String, val description: String) {
    USER("User", "The person using the private cover and safe app"),
    SAFE_CONTACT("Safe Contact", "A trusted person helping someone"),
    STAFF("Staff", "An organization staff member"),
    ADMIN("Admin", "An organization leader who oversees staff");

    companion object {
        // Rebuilds an account role from its stored text value.
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: USER
    }
}

// Pick the visible cover here; add another option before wiring its screen and launcher icon.
enum class CoverType(val title: String) {
    NEWS("Daily Digest"), FITNESS("Fitness Tracker"), GAME("Game");

    companion object {
        // Rebuilds an account profile from its stored JSON fields.
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: FITNESS
    }
}

// This is the complete account profile; cover screens only get the small, normal-looking subset they need.
data class UserProfile(
    val username: String,
    val fullName: String,
    val affectedNames: String,
    val abuserName: String,
    val dob: String,
    val language: String,
    val situation: String,
    val region: String,
    val triggerHash: String,
    val contacts: List<SafeContact> = emptyList(),
    val address: DeliveryAddress = DeliveryAddress(),
    val coverType: CoverType = CoverType.FITNESS,
    val role: AccountRole = AccountRole.USER,
    val email: String = "",
    val phone: String = "",
) {
    // Converts this account record into fields that Firebase can store.
    fun toMap(): Map<String, Any> = mapOf(
        "address" to address.toMap(),
        "username" to username,
        "fullName" to fullName,
        "affectedNames" to affectedNames,
        "abuserName" to abuserName,
        "dob" to dob,
        "language" to language,
        "situation" to situation,
        "region" to region,
        "triggerHash" to triggerHash,
        "contacts" to contacts.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "details" to it.details,
                "safeToStay" to it.safeToStay,
                "linkedUid" to it.linkedUid
            )
        },
        "coverType" to coverType.name,
        "role" to role.name,
        "email" to email,
        "phone" to phone,
    )

    // Converts this account record into protected JSON data.
    fun toJson() = JSONObject(toMap())

    companion object {
        // Rebuilds this account record from protected JSON data.
        fun fromJson(j: JSONObject): UserProfile {
            val contacts = j.optJSONArray("contacts") ?: JSONArray()
            return UserProfile(
                j.optString("username"),
                j.optString("fullName"),
                j.optString("affectedNames"),
                j.optString("abuserName"),
                j.optString("dob"),
                j.optString("language"),
                j.optString("situation"),
                j.optString("region"),
                j.optString("triggerHash"),
                (0 until contacts.length()).mapNotNull {
                    val c = contacts.optJSONObject(it) ?: return@mapNotNull null
                    SafeContact(
                        c.optString("id"), c.optString("name"), c.optString("details"),
                        c.optBoolean("safeToStay"), c.optString("linkedUid")
                    )
                },
                DeliveryAddress.fromJson(j.optJSONObject("address")),
                CoverType.fromStored(j.optString("coverType")),
                AccountRole.fromStored(j.optString("role")),
                j.optString("email"),
                j.optString("phone")
            )
        }
    }
}

// Firebase uses the uid behind the scenes while the human sticks with their username.
data class AccountIdentity(val uid: String, val username: String)

// Failed attempts remember when something happened, never which PIN somebody tried.
data class FailedAttempt(val id: String, val timestamp: Long)

// General location details used by profiles, safety resources, maps, and emergency reports.
data class DeliveryAddress(
    val line1: String = "", val line2: String = "", val city: String = "",
    val province: String = "", val postalCode: String = "", val country: String = "Canada",
    val latitude: Double? = null, val longitude: Double? = null,
) {
    val locationValid
        get() = city.isNotBlank() && listOf(
            line1,
            line2,
            city,
            province,
            postalCode,
            country
        ).all { it.length <= 160 }
    val valid
        get() = line1.isNotBlank() && city.isNotBlank() && province.isNotBlank() && postalCode.isNotBlank() && country.isNotBlank()
                && listOf(
            line1,
            line2,
            city,
            province,
            postalCode,
            country
        ).all { it.length <= 160 }
    val label get() = if (valid) "$line1, $city" else ""
    val generalLocation
        get() = listOf(city, province, country).filter { it.isNotBlank() }.joinToString(", ")

    // Converts this account record into fields that Firebase can store.
    fun toMap(): Map<String, Any> = buildMap {
        put("line1", line1)
        put("line2", line2)
        put("city", city)
        put("province", province)
        put(
            "postalCode",
            postalCode
        )
        put("country", country)
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
    }

    companion object {
        // Rebuilds this account record from protected JSON data.
        fun fromJson(j: JSONObject?) =
            if (j == null) DeliveryAddress() else DeliveryAddress(
                j.optString("line1"),
                j.optString("line2"),
                j.optString("city"),
                j.optString("province"),
                j.optString("postalCode"),
                j.optString("country", "Canada"),
                j.optDouble("latitude").takeIf { it.isFinite() && it in -90.0..90.0 },
                j.optDouble("longitude").takeIf { it.isFinite() && it in -180.0..180.0 })
    }
}

// Admins: loads staff and admin accounts for the management screen.

data class AccountCounts(
    val users: Int = 0,
    val safeContacts: Int = 0,
    val staff: Int = 0,
    val admins: Int = 0,
    val openTickets: Int = 0,
    val closedTickets: Int = 0,
)

data class AdminAccountSummary(
    val uid: String,
    val username: String,
    val role: AccountRole,
    val fullName: String,
    val email: String,
    val phone: String,
    val profileComplete: Boolean,
    val protected: Boolean,
    val assignedUsers: Int = 0,
    val safetyPlans: Int = 0,
    val completedSafetyPlans: Int = 0,
    val supportChats: Int = 0,
    val closedSupportChats: Int = 0,
)

data class AdminAccountDraft(
    val username: String,
    val password: String,
    val role: AccountRole,
    val fullName: String = "",
    val dob: String = "",
    val email: String = "",
    val phone: String = "",
    val language: String = "English",
    val city: String = "",
    val affectedNames: String = "",
    val abuserName: String = "",
    val situation: String = "",
)

// Admin actions go through callable Functions so account deletion and role checks never trust the phone.
class AdminRepository {
    private val functions = FirebaseFunctions.getInstance("northamerica-northeast1")

    // Loads only dashboard totals so sign-in does not download every organization account.
    suspend fun summary(): AccountCounts {
        return parseAdminAccounts(
            functions.getHttpsCallable("adminListAccounts")
                .call(mapOf("view" to "SUMMARY")).await().data
        ).first
    }

    // Loads account rows only after the administrator opens the account directory.
    suspend fun accounts(): List<AdminAccountSummary> {
        return parseAdminAccounts(
            functions.getHttpsCallable("adminListAccounts")
                .call(mapOf("view" to "ACCOUNTS")).await().data
        ).second
    }

    // Loads staff rows and calculated workloads only on the Staff management screen.
    suspend fun staffAccounts(): List<AdminAccountSummary> {
        return parseAdminAccounts(
            functions.getHttpsCallable("adminListAccounts")
                .call(mapOf("view" to "STAFF")).await().data
        ).second
    }

    // Calls the protected backend to create a staff or admin account.
    suspend fun create(value: AdminAccountDraft) {
        functions.getHttpsCallable("adminCreateAccount").call(adminAccountPayload(value)).await()
    }

    // Calls the protected backend to delete an allowed staff or admin account.
    suspend fun delete(uid: String) {
        functions.getHttpsCallable("adminDeleteAccount").call(mapOf("uid" to uid)).await()
    }

    // Saves the organization profile so account storage can load it again.
    suspend fun completeOrganizationProfile(
        fullName: String,
        dob: String,
        email: String,
        phone: String
    ): UserProfile {
        val result = functions.getHttpsCallable("completeOrganizationProfile").call(
            mapOf(
                "fullName" to fullName, "dob" to dob, "email" to email, "phone" to phone,
            )
        ).await().data.asMap()["profile"].asMap()
        FirebaseAuth.getInstance().currentUser?.reload()?.await()
        return UserProfile.fromJson(JSONObject(result))
    }
}

// Converts untrusted callable output into safe values before the admin screen reads it.
internal fun parseAdminAccounts(raw: Any?): Pair<AccountCounts, List<AdminAccountSummary>> {
    val root = raw.asMap()
    val counts = root["counts"].asMap()
    val tickets = root["tickets"].asMap()
    val rows = root["accounts"] as? List<*> ?: emptyList<Any>()
    return AccountCounts(
        counts.int("USER"),
        counts.int("SAFE_CONTACT"),
        counts.int("STAFF"),
        counts.int("ADMIN"),
        tickets.int("open"),
        tickets.int("closed"),
    ) to rows.mapNotNull { row ->
        row.asMap().takeIf { it.isNotEmpty() }?.let { value ->
            AdminAccountSummary(
                value.string("uid"),
                value.string("username"),
                AccountRole.fromStored(value.string("role")),
                value.string("fullName"),
                value.string("email"),
                value.string("phone"),
                value["profileComplete"] == true,
                value["protected"] == true,
                (value["assignedUsers"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                (value["safetyPlans"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                (value["completedSafetyPlans"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                (value["supportChats"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                (value["closedSupportChats"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}

// Builds the exact server request for an account created from the admin screen.
internal fun adminAccountPayload(value: AdminAccountDraft): Map<String, Any> = mapOf(
    "username" to value.username,
    "password" to value.password,
    "role" to value.role.name,
    "fullName" to value.fullName,
    "dob" to value.dob,
    "email" to value.email,
    "phone" to value.phone,
    "language" to value.language,
    "region" to value.city,
    "affectedNames" to value.affectedNames,
    "abuserName" to value.abuserName,
    "situation" to value.situation,
    "address" to mapOf(
        "line1" to "",
        "line2" to "",
        "city" to value.city,
        "province" to "",
        "postalCode" to "",
        "country" to "Canada"
    ),
)

// Converts this account record into fields that Firebase can store.
private fun Any?.asMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

// Reads a text field from an admin account response.
private fun Map<String, Any?>.string(key: String) = this[key]?.toString().orEmpty()
// Reads a number field from an admin account response.
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0

// All account roles: loads and saves offline account profiles.

// Handy for emulator testing: same account flow, but nothing leaves this installation.
class LocalAccountRepository(private val vault: EncryptedVault) : AccountRepository {
    // Uses one protected-storage key for the local account profile.
    private fun key(username: String, role: AccountRole) =
        "${role.name}:${InputPolicy.username(username)}"

    // Creates an encrypted local account for emulator or offline testing.
    override suspend fun create(username: String, password: String, role: AccountRole) =
        withContext(Dispatchers.IO) {
            require(InputPolicy.validUsername(username)) { "Invalid username" }
            require(InputPolicy.validAccountPassword(password, username)) { "Invalid password" }
            val identity =
                AccountIdentity(UUID.randomUUID().toString(), InputPolicy.username(username))
            val verifier = SecretVerifier.create(password)
            vault.update { root ->
                val accounts = root.child("accounts")
                val normalized = InputPolicy.username(username)
                val usernameTaken = accounts.has(normalized) || AccountRole.entries.any {
                    accounts.has(key(normalized, it))
                }
                check(!usernameTaken) { "Account unavailable" }
                accounts.put(
                    key(username, role),
                    JSONObject().put("uid", identity.uid).put("passwordVerifier", verifier)
                )
            }
            identity
        }

    // Account: Authenticates the account.
    override suspend fun authenticate(username: String, password: String, role: AccountRole) =
        withContext(Dispatchers.IO) {
            val normalized = InputPolicy.username(username)
            val accounts = vault.read().optJSONObject("accounts")
            val account = accounts?.optJSONObject(key(username, role))
                ?: if (role == AccountRole.USER) accounts?.optJSONObject(normalized) else null
            // Perform the KDF for unknown usernames too, avoiding a cheap username timing oracle.
            val verifier = account?.getString("passwordVerifier")
                ?: SecretVerifier.create("unavailable-account")
            check(
                SecretVerifier.matches(
                    password,
                    verifier
                ) && account != null
            ) { "Sign-in failed" }
            AccountIdentity(account.getString("uid"), normalized)
        }

    // Loads the signed-in account profile from this repository.
    override suspend fun profile(identity: AccountIdentity) = withContext(Dispatchers.IO) {
        vault.read().optJSONObject("profiles")?.optJSONObject(identity.uid)
            ?.let(UserProfile::fromJson)
    }

    // Saves the account profile in the selected account repository.
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) =
        withContext(Dispatchers.IO) {
            vault.update { it.child("profiles").put(identity.uid, profile.toJson()) }
        }

    // Adds one account activity record to storage.
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) = Unit
    // Loads private activity-history records for this account.
    override suspend fun historyRecords(identity: AccountIdentity) = emptyList<HistoryRecord>()
    // Re-encrypts this account after its password changes.
    override suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    ) = withContext(Dispatchers.IO) {
        require(
            InputPolicy.validAccountPassword(
                newPassword,
                identity.username
            )
        ) { "Invalid password" }
        vault.update { root ->
            val records = root.getJSONObject("accounts")
            val account = AccountRole.entries.asSequence()
                .mapNotNull { records.optJSONObject(key(identity.username, it)) }
                .firstOrNull { it.optString("uid") == identity.uid } ?: records.getJSONObject(
                identity.username
            )
            check(
                account.getString("uid") == identity.uid && SecretVerifier.matches(
                    currentPassword,
                    account.getString("passwordVerifier")
                )
            ) { "Current password is incorrect" }
            account.put("passwordVerifier", SecretVerifier.create(newPassword))
        }
    }

    // Account: Authenticates the accountd uid.
    override fun authenticatedUid(): String? = null
    // Checks the username and password, then returns the matching account.
    override fun authenticatedUsername(): String? = null
    // Account: Signs out the current account.
    override fun signOut() = Unit
}

// All account roles: defines Firebase account and profile records.

// Firestore account data. Property annotations preserve the existing cloud field names.
data class User(
    // Firestore fills this value from the document ID.
    @DocumentId
    val uid: String = "",
    val username: String = "",
    val fullName: String = "",
    val affectedNames: String = "",
    val dob: String = "",
    val language: String = "",
    val situation: String = "",
    val region: String = "",
    val triggerHash: String = "",
    val contacts: List<Contact> = emptyList(),
    val address: Address? = null,

    @PropertyName("cover_app")
    val coverApp: String = "",
)

data class Contact(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val details: String = ""
)

data class Address(
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val province: String = "",
    val postalCode: String = "",
    val country: String = ""
)

