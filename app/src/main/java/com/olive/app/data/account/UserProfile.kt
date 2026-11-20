// Users and safe contacts: defines their profile details.
package com.olive.app.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.network.asStringKeyedMap
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import com.olive.app.data.network.optionalInt
import com.olive.app.data.network.optionalString
import com.olive.app.security.*
import com.olive.app.security.InputPolicy
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.json.JSONArray
import org.json.JSONObject

private open class BoundedCoordinateSerializer(
    name: String,
    private val min: Double,
    private val max: Double,
) : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder
        val parsed = if (jsonDecoder != null) {
            val element = jsonDecoder.decodeJsonElement()
            if (element is JsonNull) null
            else (element as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        } else {
            runCatching { decoder.decodeDouble() }.getOrNull()
        }
        return parsed?.takeIf { it.isFinite() && it in min..max }
    }
}

private object LatitudeSerializer : BoundedCoordinateSerializer("Latitude", -90.0, 90.0)
private object LongitudeSerializer : BoundedCoordinateSerializer("Longitude", -180.0, 180.0)

object AccountRoleSerializer : KSerializer<AccountRole> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AccountRole", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AccountRole) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): AccountRole = AccountRole.fromStored(decoder.decodeString())
}

object CoverTypeSerializer : KSerializer<CoverType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CoverType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CoverType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): CoverType = CoverType.fromStored(decoder.decodeString())
}

private object SafeContactListSerializer : KSerializer<List<SafeContact>> {
    override val descriptor: SerialDescriptor = ListSerializer(SafeContact.serializer()).descriptor
    override fun serialize(encoder: Encoder, value: List<SafeContact>) =
        encoder.encodeSerializableValue(ListSerializer(SafeContact.serializer()), value)

    override fun deserialize(decoder: Decoder): List<SafeContact> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(SafeContact.serializer()))
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            if (element !is JsonObject) null
            else runCatching { jsonDecoder.json.decodeFromJsonElement(SafeContact.serializer(), element) }.getOrNull()
        }
    }
}

// A trusted person can optionally be linked to one explicitly accepted chat friend.
@Serializable
data class SafeContact(
    val id: String = "",
    val name: String = "",
    val details: String = "",
    val safeToStay: Boolean = false,
    val linkedUid: String = "",
    val relationship: String = ""
)

// The account role decides which onboarding flow and app shell this person receives.
@Serializable(with = AccountRoleSerializer::class)
enum class AccountRole(val title: String, val description: String) {
    USER("User", "The person using the private cover and safe app"),
    SAFE_CONTACT("Safe Contact", "A trusted person helping someone"),
    STAFF("Staff", "An organization staff member"),
    ADMIN("Admin", "An organization leader who oversees staff");

    companion object {
        /**
         * Rebuilds an account role from its stored text value.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting from stored.
         */
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: USER
    }
}

// Pick the visible cover here; add another option before wiring its screen and launcher icon.
@Serializable(with = CoverTypeSerializer::class)
enum class CoverType(val title: String) {
    NEWS("Daily Digest"), FITNESS("Fitness Tracker"), GAME("Game");

    companion object {
        /**
         * Rebuilds an account profile from its stored JSON fields.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting from stored.
         */
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: FITNESS
    }
}

// This is the complete account profile; cover screens only get the small, normal-looking subset they need.
@Serializable
data class UserProfile(
    val username: String = "",
    val fullName: String = "",
    val affectedNames: String = "",
    val abuserName: String = "",
    val dob: String = "",
    val language: String = "",
    val situation: String = "",
    val region: String = "",
    val triggerHash: String = "",
    @Serializable(with = SafeContactListSerializer::class)
    val contacts: List<SafeContact> = emptyList(),
    val address: DeliveryAddress = DeliveryAddress(),
    val coverType: CoverType = CoverType.FITNESS,
    val role: AccountRole = AccountRole.USER,
    val email: String = "",
    val phone: String = "",
) {
    /**
     * Converts this account record into fields that Firebase can store.
     *
     * @return Map containing the resulting to map entries.
     */
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
                "linkedUid" to it.linkedUid,
                "relationship" to it.relationship
            )
        },
        "coverType" to coverType.name,
        "role" to role.name,
        "email" to email,
        "phone" to phone,
    )

    /**
     * Converts this account record into protected JSON data.
     *
     * @return JSON representation of this object.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Rebuilds this account record from protected JSON data.
         *
         * @param j J supplied to [fromJson].
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(j: JSONObject): UserProfile = j.decodeJsonObject()
    }
}

// Firebase uses the uid behind the scenes while the human sticks with their username.
data class AccountIdentity(val uid: String, val username: String)

// Failed attempts remember when something happened, never which PIN somebody tried.
data class FailedAttempt(val id: String, val timestamp: Long)

// General location details used by profiles, safety resources, maps, and emergency reports.
@Serializable
data class DeliveryAddress(
    val line1: String = "", val line2: String = "", val city: String = "",
    val province: String = "", val postalCode: String = "", val country: String = "Canada",
    @Serializable(with = LatitudeSerializer::class)
    val latitude: Double? = null,
    @Serializable(with = LongitudeSerializer::class)
    val longitude: Double? = null,
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

    /**
     * Converts this account record into fields that Firebase can store.
     *
     * @return Map containing the resulting to map entries.
     */
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
        /**
         * Rebuilds this account record from protected JSON data.
         *
         * @param j Optional j supplied to [fromJson].
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(j: JSONObject?): DeliveryAddress =
            if (j == null) DeliveryAddress() else j.decodeJsonObject()
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
class AdminRepository(private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1")) {


    /**
     * Fetches and parses one view from the adminListAccounts callable function.
     *
     * @param view Backend projection view (`SUMMARY`, `ACCOUNTS`, or `STAFF`).
     * @return Parsed account counts and matching account summaries.
     */
    private suspend fun fetchAdminAccounts(view: String): Pair<AccountCounts, List<AdminAccountSummary>> =
        parseAdminAccounts(
            functions.getHttpsCallable("adminListAccounts")
                .call(mapOf("view" to view)).await().data
        )

    /**
     * Loads only dashboard totals so sign-in does not download every organization account.
     *
     * @return The resulting [AccountCounts] instance.
     */
    suspend fun summary(): AccountCounts = fetchAdminAccounts("SUMMARY").first

    /**
     * Loads account rows only after the administrator opens the account directory.
     *
     * @return List of resulting accounts items.
     */
    suspend fun accounts(): List<AdminAccountSummary> = fetchAdminAccounts("ACCOUNTS").second

    /**
     * Loads staff rows and calculated workloads only on the Staff management screen.
     *
     * @return List of resulting staff accounts items.
     */
    suspend fun staffAccounts(): List<AdminAccountSummary> = fetchAdminAccounts("STAFF").second

    /**
     * Calls the protected backend to create a staff or admin account.
     *
     * @param value Input value to store, parse, or format.
     */
    suspend fun create(value: AdminAccountDraft) {
        functions.getHttpsCallable("adminCreateAccount").call(adminAccountPayload(value)).await()
    }

    /**
     * Calls the protected backend to delete an allowed staff or admin account.
     *
     * @param uid Firebase UID of the target user account.
     */
    suspend fun delete(uid: String) {
        functions.getHttpsCallable("adminDeleteAccount").call(mapOf("uid" to uid)).await()
    }

    /**
     * Saves the organization profile so account storage can load it again.
     *
     * @param fullName Full name supplied to [completeOrganizationProfile].
     * @param dob Date of birth supplied to [completeOrganizationProfile].
     * @param email Email supplied to [completeOrganizationProfile].
     * @param phone Phone supplied to [completeOrganizationProfile].
     * @return The resulting [UserProfile] for complete organization profile.
     */
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
        ).await().data.asStringKeyedMap()["profile"].asStringKeyedMap()
        FirebaseAuth.getInstance().currentUser?.reload()?.await()
        return UserProfile.fromJson(JSONObject(result))
    }
}

/**
 * Converts untrusted callable output into safe values before the admin screen reads it.
 *
 * @param raw Optional raw supplied to [parseAdminAccounts].
 * @return The resulting [Pair] for parse admin accounts.
 */
internal fun parseAdminAccounts(raw: Any?): Pair<AccountCounts, List<AdminAccountSummary>> {
    val root = raw.asStringKeyedMap()
    val counts = root["counts"].asStringKeyedMap()
    val tickets = root["tickets"].asStringKeyedMap()
    val rows = root["accounts"] as? List<*> ?: emptyList<Any>()
    return AccountCounts(
        counts.optionalInt("USER"),
        counts.optionalInt("SAFE_CONTACT"),
        counts.optionalInt("STAFF"),
        counts.optionalInt("ADMIN"),
        tickets.optionalInt("open"),
        tickets.optionalInt("closed"),
    ) to rows.mapNotNull { row ->
        row.asStringKeyedMap().takeIf { it.isNotEmpty() }?.let { value ->
            AdminAccountSummary(
                value.optionalString("uid"),
                value.optionalString("username"),
                AccountRole.fromStored(value.optionalString("role")),
                value.optionalString("fullName"),
                value.optionalString("email"),
                value.optionalString("phone"),
                value["profileComplete"] == true,
                value["protected"] == true,
                value.optionalInt("assignedUsers").coerceAtLeast(0),
                value.optionalInt("safetyPlans").coerceAtLeast(0),
                value.optionalInt("completedSafetyPlans").coerceAtLeast(0),
                value.optionalInt("supportChats").coerceAtLeast(0),
                value.optionalInt("closedSupportChats").coerceAtLeast(0),
            )
        }
    }
}

/**
 * Builds the exact server request for an account created from the admin screen.
 *
 * @param value Input value to store, parse, or format.
 * @return Map containing the resulting admin account payload entries.
 */
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

// All account roles: loads and saves offline account profiles.

// Handy for emulator testing: same account flow, but nothing leaves this installation.
class LocalAccountRepository(private val vault: EncryptedVault) : AccountRepository {
    /**
     * Uses one protected-storage key for the local account profile.
     *
     * @param username Account username.
     * @param role Account role determining workspace permissions.
     * @return The resulting key.
     */
    private fun key(username: String, role: AccountRole) =
        "${role.name}:${InputPolicy.username(username)}"

    /**
     * Creates an encrypted local account for emulator or offline testing.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting create.
     */
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

    /**
     * Account: Authenticates the account.
     *
     * @param username Account username.
     * @param password Account password string.
     * @param role Account role determining workspace permissions.
     * @return The resulting authenticate.
     */
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

    /**
     * Loads the signed-in account profile from this repository.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return The resulting profile.
     */
    override suspend fun profile(identity: AccountIdentity) = withContext(Dispatchers.IO) {
        vault.read().optJSONObject("profiles")?.optJSONObject(identity.uid)
            ?.let(UserProfile::fromJson)
    }

    /**
     * Saves the account profile in the selected account repository.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     */
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) =
        withContext(Dispatchers.IO) {
            vault.update { it.child("profiles").put(identity.uid, profile.toJson()) }
        }

    /**
     * Adds one account activity record to storage.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param record Record supplied to [saveHistoryRecord].
     */
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) = Unit
    /**
     * Loads private activity-history records for this account.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @return The resulting history records.
     */
    override suspend fun historyRecords(identity: AccountIdentity) = emptyList<HistoryRecord>()
    /**
     * Re-encrypts this account after its password changes.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param currentPassword Current password supplied to [changePassword].
     * @param newPassword New password supplied to [changePassword].
     */
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

    /**
     * Account: Authenticates the accountd uid.
     *
     * @return The resulting authenticated user ID string, or `null` when unavailable.
     */
    override fun authenticatedUid(): String? = null
    /**
     * Checks the username and password, then returns the matching account.
     *
     * @return The resulting authenticated username string, or `null` when unavailable.
     */
    override fun authenticatedUsername(): String? = null
    /**
     * Account: Signs out the current account.
     */
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

enum class AccountSetupStep { PersonalProfile, PrivateTrigger, DevicePin, OrganizationProfile, Ready }

// Account rules stay pure here so sign-in aliases and first-login routing can be unit tested without Firebase.
object AccountPolicy {
    /**
     * Converts a username into its internal sign-in email.
     *
     * @param identifier Identifier supplied to [signInEmail].
     * @param role Account role determining workspace permissions.
     * @return The resulting sign in email string.
     */
    fun signInEmail(identifier: String, role: AccountRole): String {
        val value = identifier.trim()
        if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN) && '@' in value) {
            return value.lowercase()
        }
        val domain = when (role) {
            AccountRole.USER -> "users.olive.invalid"
            AccountRole.SAFE_CONTACT -> "safe.olive.invalid"
            AccountRole.STAFF, AccountRole.ADMIN -> "yourolivebranch.ca"
        }
        return "${InputPolicy.username(value)}@$domain"
    }

    /**
     * Recreates the hidden email format used by older personal accounts.
     *
     * @param username Account username.
     * @return The resulting legacy user email.
     */
    fun legacyUserEmail(username: String) = "${InputPolicy.username(username)}@users.olive.invalid"

    /**
     * Finds missing personal details that must be completed before using the safe app.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting personal profile needs setup.
     */
    fun personalProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
            value.language.isBlank() || !value.address.locationValid

    /**
     * Finds missing staff or admin details that must be completed before opening their portal.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting organization profile needs setup.
     */
    fun organizationProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
            value.email.isBlank() || value.email.endsWith(".olive.invalid", true)

    /**
     * Returns the setup rules for the selected account role.
     *
     * @param profile Account profile details, or null when not provided.
     * @param role Account role determining workspace permissions.
     * @return The resulting [AccountSetupStep] for next setup.
     */
    fun nextSetup(profile: UserProfile?, role: AccountRole): AccountSetupStep {
        if (profile == null) {
            return if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
                AccountSetupStep.OrganizationProfile
            } else AccountSetupStep.PersonalProfile
        }
        return when (role) {
            AccountRole.USER -> when {
                personalProfileNeedsSetup(profile) -> AccountSetupStep.PersonalProfile
                profile.triggerHash.isBlank() -> AccountSetupStep.PrivateTrigger
                else -> AccountSetupStep.DevicePin
            }
            AccountRole.SAFE_CONTACT ->
                if (personalProfileNeedsSetup(profile)) AccountSetupStep.PersonalProfile else AccountSetupStep.Ready
            AccountRole.STAFF, AccountRole.ADMIN ->
                if (organizationProfileNeedsSetup(profile)) AccountSetupStep.OrganizationProfile else AccountSetupStep.Ready
        }
    }
}

/**
 * Incident dates describe past events; malformed or future dates must be corrected explicitly.
 *
 * @param value Input value to store, parse, or format.
 * @param today Today supplied to [parseIncidentDate].
 * @return The resulting [LocalDate], or `null` if unavailable.
 */
internal fun parseIncidentDate(value: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val text = value.trim()
    if (!text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
        ?.takeIf { it.year >= 1 && !it.isAfter(today) }
}
