// Contains the device access models code for this part of Olive.
package com.olive.app.security

import com.olive.app.data.AccountRole
import com.olive.app.data.UserProfile

// Groups the data and actions for device enrollment.
data class DeviceEnrollment(
    val uid: String, val triggerHash: String, val role: AccountRole = AccountRole.USER,
    val entryMethod: SafeEntryMethod = SafeEntryMethod.APP_PIN, val hasAppPin: Boolean = true
)

// Groups the data and actions for device session.
data class DeviceSession(val enrollment: DeviceEnrollment, val profile: UserProfile)
sealed interface PinResult {
    data object Accepted : PinResult
    // Groups the data and actions for rejected.
    data class Rejected(val failures: Int) : PinResult
}
