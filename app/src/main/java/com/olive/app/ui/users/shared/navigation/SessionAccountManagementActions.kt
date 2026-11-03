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

    // Saves the staff or admin details entered during account setup.
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    // Updates the signed-in staff or admin profile after setup.
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    // Reloads the account list shown in the admin portal.
    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    // Creates a staff or admin account from the admin form.
    fun createManagedAccount(value: AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    // Deletes a staff or admin account selected in the admin portal.
    fun deleteManagedAccount(value: AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)

    // Saves the user profile and situation details from Safe App settings.
    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress,
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

    // Saves the public profile details used by the selected cover app.
    fun saveCoverSettings(
        fullName: String,
        dob: String = runtime.state.coverDob,
        address: DeliveryAddress = runtime.state.deliveryAddress,
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    // Changes which cover app opens before the private Safe App.
    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    // Changes the hidden phrase that opens private entry from a cover app.
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    // Saves the trusted contacts entered during setup or in settings.
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    // Sends local setting changes to the account and downloads newer changes.
    fun sync() = runtime.settings.sync()
    // Restarts account notification listeners after the signed-in account changes.
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    // Checks the current password before replacing it with the new password.
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)
}
