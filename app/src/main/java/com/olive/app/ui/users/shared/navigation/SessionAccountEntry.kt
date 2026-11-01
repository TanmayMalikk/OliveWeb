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

    fun load() = runtime.accountSession.load()
    fun selectRole(role: AccountRole) = runtime.accountSession.selectRole(role)
    fun chooseNew() = runtime.accountSession.chooseNew()
    fun chooseReturning() = runtime.accountSession.chooseReturning()
    fun openAccountSetup() = runtime.accountSession.openAccountSetup()
    fun clearCredentialError() = runtime.accountSession.clearCredentialError()
    fun authenticate(username: String, password: String, create: Boolean) =
        runtime.accountSignIn.authenticate(username, password, create)

    fun saveSetupProfile(value: UserProfile) = runtime.accountSession.saveSetupProfile(value)
    fun createDevicePin(pin: String, confirmation: String) =
        runtime.accountSession.createDevicePin(pin, confirmation)

    fun saveProvisionedTrigger(trigger: String) = runtime.accountSession.saveProvisionedTrigger(trigger)

    fun showError(text: String) {
        runtime.state.message = text
    }

    fun refreshDeviceSecurity() {
        runtime.deviceSecure = runtime.phoneSecure()
    }

    fun setSafeEntryMethod(method: SafeEntryMethod, currentPin: String) =
        runtime.entrySettings.select(method, currentPin)

    fun saveSafeEntryPin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) =
        runtime.entrySettings.savePin(currentPin, pin, confirmation, usePin)

    fun authenticateDeviceLock(enroll: Boolean = false) = runtime.deviceAccess.authenticate(enroll)
    fun changeDeviceLockTrigger(trigger: String) = runtime.deviceAccess.authenticate(false, trigger)

    fun interceptSearch(value: String): Boolean = runtime.access.interceptSearch(value)
    fun beginDiscreetEntry() = runtime.access.beginDiscreetEntry()
    fun submitPin(pin: String, challenge: Int = runtime.state.pinChallengeVersion) =
        runtime.access.submitPin(pin, challenge)

    fun submitCalculatorPin(pin: String) = runtime.access.submitCalculatorPin(pin)
}
