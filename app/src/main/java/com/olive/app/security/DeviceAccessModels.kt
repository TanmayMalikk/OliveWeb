// All signed-in roles: defines PIN and device-lock settings.
package com.olive.app.security

import com.olive.app.data.AccountRole
import com.olive.app.data.UserProfile

data class DeviceEnrollment(
    val uid: String, val triggerHash: String, val role: AccountRole = AccountRole.USER,
    val entryMethod: SafeEntryMethod = SafeEntryMethod.APP_PIN, val hasAppPin: Boolean = true
) {
    // Calculator digits may request verification only when this account has an app PIN to verify.
    fun allowsCalculatorPinAttempt(pin: String) =
        role == AccountRole.USER && allowsAppPin() && InputPolicy.validPin(pin)
}

data class DeviceSession(val enrollment: DeviceEnrollment, val profile: UserProfile)
sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val failures: Int) : PinResult
}
