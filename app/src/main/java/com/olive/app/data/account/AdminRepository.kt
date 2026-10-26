// Contains the admin repository code for this part of Olive.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

// Groups the data and actions for account counts.
data class AccountCounts(
    val users: Int = 0,
    val safeContacts: Int = 0,
    val staff: Int = 0,
    val admins: Int = 0
)

// Groups the data and actions for admin account summary.
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

// Groups the data and actions for admin account draft.
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

    suspend fun accounts(): Pair<AccountCounts, List<AdminAccountSummary>> {
        val root = functions.getHttpsCallable("adminListAccounts").call().await().data.asMap()
        val counts = root["counts"].asMap()
        val rows = root["accounts"] as? List<*> ?: emptyList<Any>()
        return AccountCounts(
            counts.int("USER"),
            counts.int("SAFE_CONTACT"),
            counts.int("STAFF"),
            counts.int("ADMIN")
        ) to
                rows.mapNotNull { raw ->
                    raw.asMap().takeIf { it.isNotEmpty() }?.let { value ->
                        AdminAccountSummary(
                            value.string("uid"),
                            value.string("username"),
                            AccountRole.fromStored(value.string("role")),
                            value.string("fullName"),
                            value.string("email"),
                            value.string("phone"),
                            value["profileComplete"] == true,
                            value["protected"] == true,
                            (value["assignedUsers"] as? Number)?.toInt() ?: 0,
                        )
                    }
                }
    }

    suspend fun create(value: AdminAccountDraft) {
        val data = mapOf(
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
        functions.getHttpsCallable("adminCreateAccount").call(data).await()
    }

    suspend fun delete(uid: String) {
        functions.getHttpsCallable("adminDeleteAccount").call(mapOf("uid" to uid)).await()
    }

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

private fun Any?.asMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

private fun Map<String, Any?>.string(key: String) = this[key]?.toString().orEmpty()
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0
