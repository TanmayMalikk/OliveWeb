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

// A saved app PIN is an optional second route even when device lock stays preferred.
internal fun DeviceEnrollment.allowsAppPin(): Boolean = hasAppPin
