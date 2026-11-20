// All app roles: checks Android permissions needed by each app feature.
package com.olive.app

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
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

/**
 * Determines whether the app should request notifications.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param asked Whether to apply asked.
 * @param granted Whether to apply granted.
 * @return `true` if should request notifications, or `false` otherwise.
 */
internal fun shouldRequestNotifications(enabled: Boolean, asked: Boolean, granted: Boolean) =
    enabled && !asked && !granted

/**
 * Determines whether the app should open location settings.
 *
 * @param asked Whether to apply asked.
 * @param fineRationale Whether to apply fine rationale.
 * @param coarseRationale Whether to apply coarse rationale.
 * @return `true` if should open location settings, or `false` otherwise.
 */
internal fun shouldOpenLocationSettings(asked: Boolean, fineRationale: Boolean, coarseRationale: Boolean) =
    asked && !fineRationale && !coarseRationale

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
    private val locationSettings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishLocationRequest(hasLocation())
    }
    private val backgroundLocationSettings =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishLocationRequest(hasBackgroundLocation())
        }

    /**
     * Returns the app location settings.
     *
     * @return The resulting app location settings.
     */
    private fun appLocationSettings() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())

    /**
     * Checks whether Android granted approximate or precise location access.
     *
     * @return `true` if has location, or `false` otherwise.
     */
    fun hasLocation() = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks background location on Android 10 and newer; older versions do not need a separate grant.
     *
     * @return `true` if has background location, or `false` otherwise.
     */
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Notifications support tickets, calls, and updates after account setup.
     */
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

    /**
     * Requests location.
     *
     * @return `true` if request location, or `false` otherwise.
     */
    suspend fun requestLocation(): Boolean = locationRequests.withLock {
        locationResult?.await()
        hasLocation() || launchLocationRequest {
            val deniedPermanently = shouldOpenLocationSettings(
                preferences.getBoolean("location_asked", false),
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION),
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            if (deniedPermanently) locationSettings.launch(appLocationSettings())
            else {
                preferences.edit { putBoolean("location_asked", true) }
                location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    /**
     * Requests background location.
     *
     * @return `true` if request background location, or `false` otherwise.
     */
    suspend fun requestBackgroundLocation(): Boolean {
        if (!requestLocation()) return false
        return locationRequests.withLock {
            locationResult?.await()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocation()) return@withLock true
            launchLocationRequest {
                val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                val canRequest = !preferences.getBoolean("background_location_asked", false) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                if (canRequest) {
                    // Android owns the link to the location permission page.
                    backgroundLocation.launch(permission)
                    preferences.edit { putBoolean("background_location_asked", true) }
                } else {
                    backgroundLocationSettings.launch(appLocationSettings())
                }
            }
        }
    }

    /**
     * Launches location request.
     *
     * @param launch Action or callback executed for launch.
     * @return `true` if launch location request, or `false` otherwise.
     */
    private suspend fun launchLocationRequest(launch: () -> Unit): Boolean {
        val pending = CompletableDeferred<Boolean>()
        activity.lifecycle.withResumed {
            locationResult = pending
            try {
                launch()
            } catch (_: android.content.ActivityNotFoundException) {
                finishLocationRequest(false)
            } catch (_: IllegalStateException) {
                finishLocationRequest(false)
            }
        }
        val granted = pending.await()
        return activity.lifecycle.withResumed { granted }
    }

    /**
     * Finishes location request.
     *
     * @param granted Whether to apply granted.
     */
    private fun finishLocationRequest(granted: Boolean) {
        val pending = locationResult
        locationResult = null
        onChanged()
        pending?.complete(granted)
    }
}
