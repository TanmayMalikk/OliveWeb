// Admins: loads staff and admin accounts for the management screen.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

data class AccountCounts(
    val users: Int = 0,
    val safeContacts: Int = 0,
    val staff: Int = 0,
    val admins: Int = 0
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

    // Account: returns the accounts.
    suspend fun accounts(): Pair<AccountCounts, List<AdminAccountSummary>> {
        return parseAdminAccounts(functions.getHttpsCallable("adminListAccounts").call().await().data)
    }

    // Account: Returns the create. It calls the protected Firebase Cloud Function for this feature.
    suspend fun create(value: AdminAccountDraft) {
        functions.getHttpsCallable("adminCreateAccount").call(adminAccountPayload(value)).await()
    }

    // Account: Deletes the selected item. It calls the protected Firebase Cloud Function for this feature.
    suspend fun delete(uid: String) {
        functions.getHttpsCallable("adminDeleteAccount").call(mapOf("uid" to uid)).await()
    }

    // returns the complete organization profile for account.
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
        return UserProfile.fromJson(org.json.JSONObject(result))
    }
}

// Converts untrusted callable output into safe values before the admin screen reads it.
internal fun parseAdminAccounts(raw: Any?): Pair<AccountCounts, List<AdminAccountSummary>> {
    val root = raw.asMap()
    val counts = root["counts"].asMap()
    val rows = root["accounts"] as? List<*> ?: emptyList<Any>()
    return AccountCounts(
        counts.int("USER"),
        counts.int("SAFE_CONTACT"),
        counts.int("STAFF"),
        counts.int("ADMIN")
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

// Converts the value to map for account.
private fun Any?.asMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

// returns the string for account.
private fun Map<String, Any?>.string(key: String) = this[key]?.toString().orEmpty()
// Account: returns the int.
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0
