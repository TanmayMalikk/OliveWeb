// All app roles: keeps account-entry state and sign-in actions in one place.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.AccountRole
import com.olive.app.data.UserProfile
import com.olive.app.security.SafeEntryMethod
import com.olive.app.security.allowsAppPin
import com.olive.app.ui.session.SessionRuntime

internal class SessionAccountEntry(private val runtime: SessionRuntime) {
    val role get() = runtime.state.accountRole
    val busy get() = runtime.state.busy
    val message get() = runtime.state.message
    val credentialFieldError get() = runtime.state.credentialFieldError
    val creatingAccount get() = runtime.state.creatingAccount
    val setupUsername get() = runtime.setupUsername
    val setupProfile get() = runtime.setupProfile
    val configured get() = runtime.configured
    val preferredEntryMethod get() = runtime.enrollment?.entryMethod ?: SafeEntryMethod.APP_PIN
    val hasAppPin get() = runtime.enrollment?.hasAppPin == true
    val phoneHasLock get() = runtime.deviceSecure
    val usesDeviceLock get() = preferredEntryMethod == SafeEntryMethod.DEVICE_LOCK && phoneHasLock
    val canUseAppPin get() = runtime.enrollment?.allowsAppPin() == true

    // Restores the account and private-entry state saved on this device.
    fun load() = runtime.accountSession.load()
    // Selects whether the person is a user, trusted contact, staff member, or admin.
    fun selectRole(role: AccountRole) = runtime.accountSession.selectRole(role)
    // Opens the new-account path on the account entry screen.
    fun chooseNew() = runtime.accountSession.chooseNew()
    // Opens the returning-account sign-in path.
    fun chooseReturning() = runtime.accountSession.chooseReturning()
    // Starts profile setup after a new account signs in successfully.
    fun openAccountSetup() = runtime.accountSession.openAccountSetup()
    // Clears the username or password error when the user edits the form.
    fun clearCredentialError() = runtime.accountSession.clearCredentialError()
    // Signs in with Firebase, or creates the Firebase account when requested.
    fun authenticate(username: String, password: String, create: Boolean) =
        runtime.accountSignIn.authenticate(username, password, create)

    // Saves the profile collected during account setup.
    fun saveSetupProfile(value: UserProfile) = runtime.accountSession.saveSetupProfile(value)
    // Checks both PIN fields before saving the first private-entry PIN.
    fun createDevicePin(pin: String, confirmation: String) =
        runtime.accountSession.createDevicePin(pin, confirmation)

    // Saves the hidden phrase chosen during first-time setup.
    fun saveProvisionedTrigger(trigger: String) = runtime.accountSession.saveProvisionedTrigger(trigger)

    // Shows a specific account-entry error on the current screen.
    fun showError(text: String) {
        runtime.state.message = text
    }

    // Checks again whether Android currently has a secure device lock.
    fun refreshDeviceSecurity() {
        runtime.deviceSecure = runtime.phoneSecure()
    }

    // Changes whether this user prefers Android lock or the app PIN.
    fun setSafeEntryMethod(method: SafeEntryMethod, currentPin: String) =
        runtime.entrySettings.select(method, currentPin)

    // Saves or replaces the user's backup app PIN.
    fun saveSafeEntryPin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) =
        runtime.entrySettings.savePin(currentPin, pin, confirmation, usePin)

    // Opens Android device authentication for entry or first-time enrolment.
    fun authenticateDeviceLock(enroll: Boolean = false) = runtime.deviceAccess.authenticate(enroll)
    // Confirms device authentication before changing the hidden trigger phrase.
    fun changeDeviceLockTrigger(trigger: String) = runtime.deviceAccess.authenticate(false, trigger)

    // Checks whether cover-app search text matches the saved hidden phrase.
    fun interceptSearch(value: String): Boolean = runtime.access.interceptSearch(value)
    // Opens private authentication after the hidden olive is double-tapped.
    fun beginDiscreetEntry() = runtime.access.beginDiscreetEntry()
    // Checks the entered app PIN against the current private-entry challenge.
    fun submitPin(pin: String, challenge: Int = runtime.state.pinChallengeVersion) =
        runtime.access.submitPin(pin, challenge)

}
