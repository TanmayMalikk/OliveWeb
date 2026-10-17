package com.olive.app.security

enum class SafeEntryMethod {
    APP_PIN, DEVICE_LOCK;

    companion object {
        // Older enrollments always used a separate app PIN.
        fun fromStored(value: String): SafeEntryMethod = entries.firstOrNull { it.name == value } ?: APP_PIN
        fun recommended(abuserKnowsDevicePin: Boolean) = if (abuserKnowsDevicePin) APP_PIN else DEVICE_LOCK
    }
}
