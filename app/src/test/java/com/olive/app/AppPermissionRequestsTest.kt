package com.olive.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPermissionRequestsTest {
    @Test
    fun previouslyDeniedForegroundAndBackgroundRequestsReturnTheActualSettingsChoice() = runBlocking<Unit> {
        Robolectric.buildActivity(ComponentActivity::class.java).use { scenario ->
            val activity = scenario.create().get()
            var changes = 0
            val permissions = AppPermissions(activity, { false }) { changes++ }
            scenario.start().resume()
            activity.getSharedPreferences("olive_permissions", Context.MODE_PRIVATE).edit()
                .putBoolean("location_asked", true).putBoolean("background_location_asked", true).commit()
            val window = shadowOf(activity)
            val looper = shadowOf(Looper.getMainLooper())
            fun returnFromSettings() {
                val request = window.nextStartedActivityForResult
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.intent.action)
                assertEquals("package:${activity.packageName}", request.intent.dataString)
                activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, Intent())
                looper.idle()
            }

            val foreground = async(start = CoroutineStart.UNDISPATCHED) { permissions.requestLocation() }
            assertTrue(permissions.awaitingResult)
            returnFromSettings()
            assertFalse(foreground.await())
            assertFalse(permissions.awaitingResult)

            shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            assertTrue(permissions.hasLocation())
            assertFalse(permissions.hasBackgroundLocation())
            val background = async(start = CoroutineStart.UNDISPATCHED) { permissions.requestBackgroundLocation() }
            assertTrue(permissions.awaitingResult)
            shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            returnFromSettings()
            assertTrue(background.await())
            assertFalse(permissions.awaitingResult)
            assertEquals(2, changes)
        }
    }
}
