package com.olive.app.ui

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.olive.app.ui.covers.DiscreetCoverEntry
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoverEmergencyGestureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPressVibratesOnceWithoutOpeningPrivateEntryAndDoubleTapStillOpensIt() {
        var emergencies = 0
        var opens = 0
        var vibrations = 0
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                assertEquals(HapticFeedbackType.LongPress, hapticFeedbackType)
                vibrations++
            }
        }
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    DiscreetCoverEntry(true, { opens++ }, onEmergency = { emergencies++ },
                        onEmergencyFeedback = { feedback.performHapticFeedback(HapticFeedbackType.LongPress) })
                }
            }
        }
        val icon = compose.onNodeWithTag("discreet_cover_olive")
        icon.performTouchInput { down(center); advanceEventTime(1_900); up() }
        compose.runOnIdle { assertEquals(0, emergencies); assertEquals(0, vibrations); assertEquals(0, opens) }
        icon.performTouchInput { advanceEventTime(600); longClick(durationMillis = 2_100) }
        compose.runOnIdle {
            assertEquals(1, emergencies)
            assertEquals(1, vibrations)
            assertEquals(0, opens)
        }
        icon.performTouchInput { advanceEventTime(600); doubleClick() }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, emergencies) }
    }
    @Test
    fun deniedLocationDoesNotCrashAfterTheEmergencyTrackingStart() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val denied = object : ContextWrapper(app) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION -> PackageManager.PERMISSION_DENIED
                else -> super.checkPermission(permission, pid, uid)
            }

            override fun startForegroundService(service: Intent): ComponentName? =
                throw AssertionError("Denied location must not start the foreground service")
        }
        assertFalse(com.olive.app.location.AdminLocationService.canStart(denied))
        com.olive.app.location.AdminLocationService.start(denied, "synthetic-crash-regression", "Synthetic user")
    }
}
