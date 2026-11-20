package com.olive.app.ui

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.olive.app.R
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.dashboard.EmergencyReadinessCard
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class EmergencyReadinessUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingLocationPromptsInOrderAndSnoozeIsStoredForTheAccount() {
        val account = "synthetic-readiness-${UUID.randomUUID()}"
        var foreground = false
        var background = false
        val activity = compose.activity
        val permissions = object : ContextWrapper(activity) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                val granted = when (permission) {
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> foreground
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> background
                    else -> return super.checkPermission(permission, pid, uid)
                }
                return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(
                    LocalContext provides permissions,
                    LocalLocationPermissionRequest provides { foreground = true; true },
                    LocalBackgroundLocationPermissionRequest provides { background = true; true },
                ) {
                    EmergencyReadinessCard(account, localMode = false)
                }
            }
        }
        val allowForeground = compose.onNodeWithText(activity.getString(R.string.emergency_readiness_allow_location))
        val allowBackground = compose.onNodeWithText(activity.getString(R.string.emergency_readiness_allow_background))
        allowBackground.assertDoesNotExist()
        allowForeground.assertIsEnabled().performClick()
        allowForeground.assertDoesNotExist()
        allowBackground.assertIsEnabled()
        compose.onNodeWithText(activity.getString(R.string.emergency_readiness_remind_later)).performClick()
        allowBackground.assertDoesNotExist()
        val prefs = activity.getSharedPreferences("olive_emergency_readiness", 0)
        assertTrue(prefs.getLong("snoozed_until:$account", 0) > System.currentTimeMillis())
    }
}
