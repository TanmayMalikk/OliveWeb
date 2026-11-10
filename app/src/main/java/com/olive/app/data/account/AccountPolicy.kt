// All account roles: checks usernames, passwords, account roles, network state, and cover state persistence.
package com.olive.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.olive.app.security.EncryptedVault
import com.olive.app.security.InputPolicy
import com.olive.app.security.child
import org.json.JSONArray

enum class AccountSetupStep { PersonalProfile, PrivateTrigger, DevicePin, OrganizationProfile, Ready }

// Account rules stay pure here so sign-in aliases and first-login routing can be unit tested without Firebase.
object AccountPolicy {
    // Converts a username into its internal sign-in email.
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

    // Recreates the hidden email format used by older personal accounts.
    fun legacyUserEmail(username: String) = "${InputPolicy.username(username)}@users.olive.invalid"

    // Finds missing personal details that must be completed before using the safe app.
    fun personalProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
            value.language.isBlank() || !value.address.locationValid

    // Finds missing staff or admin details that must be completed before opening their portal.
    fun organizationProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
            value.email.isBlank() || value.email.endsWith(".olive.invalid", true)

    // Returns the setup rules for the selected account role.
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

// Shared connectivity check for features that require a verified internet path.
internal fun hasValidatedInternetConnection(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

// Persists account-isolated state for the cover applications.
class CoverStateStore(private val vault: EncryptedVault) {
    // Restores the saved news feed for the given account.
    fun restore(uid: String): List<NewsArticle> = news(uid)

    // Reads cached news articles from the encrypted local vault.
    fun news(uid: String): List<NewsArticle> {
        val item = vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return emptyList()
        val rows = item.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            runCatching { NewsArticle.fromJson(rows.getJSONObject(index)) }.getOrNull()
        }
    }

    // Saves cached news articles into the encrypted local vault.
    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put(
                "news",
                JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } },
            )
        }
    }
}

// Represents one message in an encrypted conversation.
data class ChatMessage(
    val id: String,
    val senderUid: String,
    val senderUsername: String,
    val body: String,
    val sentAt: Long,
    val unsent: Boolean = false,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
    val deliveryFailureCode: String? = null,
)

// Gives each screen one handle for stopping a live chat listener safely.
fun interface ChatSubscription {
    fun close()
}
