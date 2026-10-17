package com.olive.app.security

import com.olive.app.data.AccountRole
import com.olive.app.data.UserProfile

data class DeviceEnrollment(val uid: String, val triggerHash: String, val role: AccountRole = AccountRole.USER,
    val entryMethod: SafeEntryMethod = SafeEntryMethod.APP_PIN)
data class DeviceSession(val enrollment:DeviceEnrollment,val profile:UserProfile)
sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val failures: Int) : PinResult
}
