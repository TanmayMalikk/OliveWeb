// All signed-in roles: defines PIN and device-lock entry choices.
package com.olive.app.security

enum class SafeEntryMethod {
    APP_PIN, DEVICE_LOCK;

    companion object {
        // Older enrollments always used a separate app PIN.
        fun fromStored(value: String): SafeEntryMethod =
            entries.firstOrNull { it.name == value } ?: APP_PIN

        // Safe entry method: returns the recommended.
        fun recommended(abuserKnowsDevicePin: Boolean) =
            if (abuserKnowsDevicePin) APP_PIN else DEVICE_LOCK
    }
}

// A saved app PIN is an optional second route even when device lock stays preferred.
internal fun DeviceEnrollment.allowsAppPin(): Boolean = hasAppPin
