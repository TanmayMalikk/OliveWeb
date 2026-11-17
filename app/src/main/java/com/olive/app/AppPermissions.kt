// All app roles: checks Android permissions needed by each app feature.
package com.olive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun shouldRequestNotifications(enabled: Boolean, asked: Boolean, granted: Boolean) =
    enabled && !asked && !granted

// One owner serializes every post-onboarding prompt and explicit permission retries.
internal class AppPermissions(
    private val activity: ComponentActivity,
    private val notificationsEnabled: () -> Boolean,
    private val onChanged: () -> Unit
) {
    private val preferences by lazy {
        activity.getSharedPreferences(
            "olive_permissions",
            Context.MODE_PRIVATE
        )
    }
    private var notificationPrompt = false
    private val locationRequests = Mutex()
    private var locationResult: CompletableDeferred<Boolean>? = null
    val awaitingResult get() = notificationPrompt || locationResult != null
    private val notifications =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPrompt = false
            onChanged()
        }
    private val location =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            finishLocationRequest(hasLocation())
        }
    private val backgroundLocation =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            finishLocationRequest(hasBackgroundLocation())
        }
    // Checks whether Android granted approximate or precise location access.
    fun hasLocation() = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    // Checks background location on Android 10 and newer; older versions do not need a separate grant.
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Notifications support tickets, calls, and updates after account setup.
    fun afterOnboarding() {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                if (awaitingResult || Build.VERSION.SDK_INT < 33 || !shouldRequestNotifications(
                        enabled = notificationsEnabled(),
                        asked = preferences.getBoolean("notifications_asked", false),
                        granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED,
                    )
                ) return@withResumed
                preferences.edit { putBoolean("notifications_asked", true) }
                notificationPrompt = true
                try {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: IllegalStateException) {
                    notificationPrompt = false
                    preferences.edit { remove("notifications_asked") }
                }
            }
        }
    }

    suspend fun requestLocation(): Boolean = locationRequests.withLock {
        locationResult?.await()
        hasLocation() || launchLocationRequest {
            location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    suspend fun requestBackgroundLocation(): Boolean {
        if (!requestLocation()) return false
        return locationRequests.withLock {
            locationResult?.await()
            hasBackgroundLocation() || launchLocationRequest {
                backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private suspend fun launchLocationRequest(launch: () -> Unit): Boolean {
        val pending = CompletableDeferred<Boolean>()
        activity.lifecycle.withResumed {
            locationResult = pending
            try {
                launch()
            } catch (_: IllegalStateException) {
                finishLocationRequest(false)
            }
        }
        val granted = pending.await()
        return activity.lifecycle.withResumed { granted }
    }

    private fun finishLocationRequest(granted: Boolean) {
        val pending = locationResult
        locationResult = null
        onChanged()
        pending?.complete(granted)
    }
}
