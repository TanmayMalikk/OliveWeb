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

    // Shared safe app session account entry: Loads the saved value.
    fun load() = runtime.accountSession.load()
    // Finds role for shared safe app session account entry.
    fun selectRole(role: AccountRole) = runtime.accountSession.selectRole(role)
    // Shared safe app session account entry: Finds new.
    fun chooseNew() = runtime.accountSession.chooseNew()
    // Finds returning for shared safe app session account entry.
    fun chooseReturning() = runtime.accountSession.chooseReturning()
    // Shared safe app session account entry: Starts account setup.
    fun openAccountSetup() = runtime.accountSession.openAccountSetup()
    // Removes credential error for shared safe app session account entry.
    fun clearCredentialError() = runtime.accountSession.clearCredentialError()
    // Shared safe app session account entry: Authenticates the account.
    fun authenticate(username: String, password: String, create: Boolean) =
        runtime.accountSignIn.authenticate(username, password, create)

    // Shared safe app session account entry: Saves setup profile.
    fun saveSetupProfile(value: UserProfile) = runtime.accountSession.saveSetupProfile(value)
    // Builds device pin for shared safe app session account entry.
    fun createDevicePin(pin: String, confirmation: String) =
        runtime.accountSession.createDevicePin(pin, confirmation)

    // Saves provisioned trigger for shared safe app session account entry.
    fun saveProvisionedTrigger(trigger: String) = runtime.accountSession.saveProvisionedTrigger(trigger)

    // Starts error for shared safe app session account entry.
    fun showError(text: String) {
        runtime.state.message = text
    }

    // Refreshes device security for shared safe app session account entry.
    fun refreshDeviceSecurity() {
        runtime.deviceSecure = runtime.phoneSecure()
    }

    // Shared safe app session account entry: Updates safe entry method.
    fun setSafeEntryMethod(method: SafeEntryMethod, currentPin: String) =
        runtime.entrySettings.select(method, currentPin)

    // Shared safe app session account entry: Saves safe entry pin.
    fun saveSafeEntryPin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) =
        runtime.entrySettings.savePin(currentPin, pin, confirmation, usePin)

    // Shared safe app session account entry: Authenticates the account device lock.
    fun authenticateDeviceLock(enroll: Boolean = false) = runtime.deviceAccess.authenticate(enroll)
    // Updates device lock trigger for shared safe app session account entry.
    fun changeDeviceLockTrigger(trigger: String) = runtime.deviceAccess.authenticate(false, trigger)

    // Shared safe app session account entry: Detects a valid private-entry search.
    fun interceptSearch(value: String): Boolean = runtime.access.interceptSearch(value)
    // Starts discreet entry for shared safe app session account entry.
    fun beginDiscreetEntry() = runtime.access.beginDiscreetEntry()
    // Shared safe app session account entry: returns the submit pin.
    fun submitPin(pin: String, challenge: Int = runtime.state.pinChallengeVersion) =
        runtime.access.submitPin(pin, challenge)

}
