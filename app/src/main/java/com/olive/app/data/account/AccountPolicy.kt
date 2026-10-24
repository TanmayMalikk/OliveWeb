// Contains the account policy code for this part of Olive.
package com.olive.app.data

import com.olive.app.security.InputPolicy

// Groups the data and actions for account setup step.
enum class AccountSetupStep { PersonalProfile, PrivateTrigger, DevicePin, OrganizationProfile, Ready }

// Account rules stay pure here so sign-in aliases and first-login routing can be unit tested without Firebase.
object AccountPolicy {
    fun signInEmail(identifier: String, role: AccountRole): String {
        val value = identifier.trim()
        if (role in setOf(
                AccountRole.STAFF,
                AccountRole.ADMIN
            ) && '@' in value
        ) return value.lowercase()
        val domain = when (role) {
            AccountRole.USER -> "users.olive.invalid"
            AccountRole.SAFE_CONTACT -> "safe.olive.invalid"
            AccountRole.STAFF -> "staff.olive.invalid"
            AccountRole.ADMIN -> "admin.olive.invalid"
        }
        return "${InputPolicy.username(value)}@$domain"
    }

    fun legacyUserEmail(username: String) = "${InputPolicy.username(username)}@users.olive.invalid"

    fun personalProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
                value.language.isBlank() || !value.address.locationValid

    fun organizationProfileNeedsSetup(value: UserProfile) =
        value.fullName.isBlank() || !InputPolicy.validDob(value.dob) ||
                value.email.isBlank() || value.email.endsWith(".olive.invalid", true)

    fun nextSetup(profile: UserProfile?, role: AccountRole): AccountSetupStep {
        if (profile == null) return if (role in setOf(
                AccountRole.STAFF,
                AccountRole.ADMIN
            )
        ) AccountSetupStep.OrganizationProfile else AccountSetupStep.PersonalProfile
        return when (role) {
            AccountRole.USER -> when {
                personalProfileNeedsSetup(profile) -> AccountSetupStep.PersonalProfile
                profile.triggerHash.isBlank() -> AccountSetupStep.PrivateTrigger
                else -> AccountSetupStep.DevicePin
            }

            AccountRole.SAFE_CONTACT -> if (personalProfileNeedsSetup(profile)) AccountSetupStep.PersonalProfile else AccountSetupStep.Ready
            AccountRole.STAFF, AccountRole.ADMIN -> if (organizationProfileNeedsSetup(profile)) AccountSetupStep.OrganizationProfile else AccountSetupStep.Ready
        }
    }
}
