// Contains the safe entry method code for this part of Olive.
package com.olive.app.security

// Groups the data and actions for safe entry method.
enum class SafeEntryMethod {
    APP_PIN, DEVICE_LOCK;

    companion object {
        // Older enrollments always used a separate app PIN.
        fun fromStored(value: String): SafeEntryMethod =
            entries.firstOrNull { it.name == value } ?: APP_PIN

        fun recommended(abuserKnowsDevicePin: Boolean) =
            if (abuserKnowsDevicePin) APP_PIN else DEVICE_LOCK
    }
}

// A configured backup is never an alternative to an available, selected device lock.
internal fun DeviceEnrollment.allowsAppPin(phoneSecure: Boolean): Boolean =
    hasAppPin && (entryMethod == SafeEntryMethod.APP_PIN || !phoneSecure)
