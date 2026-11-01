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

    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.saveOrganizationProfile(fullName, dob, email, phone)

    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) =
        runtime.organization.updateOrganizationProfile(fullName, dob, email, phone)

    fun refreshAdminAccounts() = runtime.organization.refreshAdminAccounts()
    fun createManagedAccount(value: AdminAccountDraft) = runtime.organization.createManagedAccount(value)
    fun deleteManagedAccount(value: AdminAccountSummary) = runtime.organization.deleteManagedAccount(value)

    fun saveSettings(
        fullName: String,
        affected: String,
        abuserName: String,
        dob: String,
        situation: String,
        address: DeliveryAddress,
    ) = runtime.settings.saveSettings(fullName, affected, abuserName, dob, situation, address)

    fun saveCoverSettings(
        fullName: String,
        dob: String = runtime.state.coverDob,
        address: DeliveryAddress = runtime.state.deliveryAddress,
    ) = runtime.settings.saveCoverSettings(fullName, dob, address)

    fun changeCover(type: CoverType) = runtime.settings.changeCover(type)
    fun changeTrigger(trigger: String, pin: String) = runtime.settings.changeTrigger(trigger, pin)
    fun saveContacts(value: List<SafeContact>) = runtime.settings.saveContacts(value)
    fun sync() = runtime.settings.sync()
    fun syncLocalNotificationHandler() = runtime.messaging.syncLocalNotificationHandler()
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) =
        runtime.settings.changePassword(currentPassword, newPassword, confirmation)
}
