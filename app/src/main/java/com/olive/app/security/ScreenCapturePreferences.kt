package com.olive.app.security

import android.content.Context
import android.content.SharedPreferences
import com.olive.app.R

// Device-local choices are isolated by account; never inherit another account's setting.
internal object ScreenCapturePreferences {
    fun store(context: Context): SharedPreferences =
        context.getSharedPreferences("olive_capture_privacy", Context.MODE_PRIVATE)

    fun enabled(context: Context, uid: String?): Boolean =
        if (uid.isNullOrBlank()) context.resources.getBoolean(R.bool.screen_capture_protection_enabled)
        else store(context).getBoolean("blocked_$uid", context.resources.getBoolean(R.bool.screen_capture_protection_enabled))

    fun setEnabled(context: Context, uid: String, enabled: Boolean) {
        require(uid.isNotBlank())
        store(context).edit().putBoolean("blocked_$uid", enabled).apply()
    }
}
