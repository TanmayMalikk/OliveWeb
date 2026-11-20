package com.olive.app.security

import android.content.Context
import android.content.SharedPreferences
import com.olive.app.R

// Device-local choices are isolated by account; never inherit another account's setting.
internal object ScreenCapturePreferences {
    /**
     * Stores the screen capture preferences.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting [SharedPreferences] instance.
     */
    fun store(context: Context): SharedPreferences =
        context.getSharedPreferences("olive_capture_privacy", Context.MODE_PRIVATE)

    /**
     * Computes enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account, or null when not provided.
     * @return `true` if enabled, or `false` otherwise.
     */
    fun enabled(context: Context, uid: String?): Boolean =
        if (uid.isNullOrBlank()) context.resources.getBoolean(R.bool.screen_capture_protection_enabled)
        else store(context).getBoolean("blocked_$uid", context.resources.getBoolean(R.bool.screen_capture_protection_enabled))

    /**
     * Sets enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setEnabled(context: Context, uid: String, enabled: Boolean) {
        require(uid.isNotBlank())
        store(context).edit().putBoolean("blocked_$uid", enabled).apply()
    }
}
