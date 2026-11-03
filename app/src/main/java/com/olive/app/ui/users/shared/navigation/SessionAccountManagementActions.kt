// Signed-in accounts and admins: connects profile, cover settings, and account management screens.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.AdminAccountDraft
import com.olive.app.data.AdminAccountSummary
import com.olive.app.data.CoverType
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.SafeContact
import com.olive.app.ui.session.SessionRuntime

internal interface SessionAccountManagementActions {
    val runtime: SessionRuntime

    // Shared safe app session account management actions: Saves organization profile.
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    // Shared safe app session account management actions: Updates organization profile.
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    // Shared safe app session account management actions: Refreshes admin accounts.
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    // Builds managed account for shared safe app session account management actions.
    fun createManagedAccount(value: AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    // Shared safe app session account management actions: Removes managed account.
    fun deleteManagedAccount(value: AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)

    // Shared safe app session account management actions: Saves settings.
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress,
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

    // Saves cover settings for shared safe app session account management actions.
    fun saveCoverSettings(
        fullName: String,
        dob: String = runtime.state.coverDob,
        address: DeliveryAddress = runtime.state.deliveryAddress,
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    // Shared safe app session account management actions: Updates cover.
    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    // Updates trigger for shared safe app session account management actions.
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    // Shared safe app session account management actions: Saves contacts.
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    // Syncs the latest data for shared safe app session account management actions.
    fun sync() = runtime.settings.sync()
    // Shared safe app session account management actions: Syncs local notification handler. It waits for the set time and cancels the timer when the feature closes.
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    // Updates password for shared safe app session account management actions.
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)
}
