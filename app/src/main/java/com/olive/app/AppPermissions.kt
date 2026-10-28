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

internal enum class OnboardingPermission { Notifications, Location, Camera, Microphone, Done }

internal fun nextOnboardingPermission(
    notificationsNeeded: Boolean, notificationsAsked: Boolean,
    locationGranted: Boolean, locationAsked: Boolean, cameraGranted: Boolean, cameraAsked: Boolean,
    microphoneGranted: Boolean, microphoneAsked: Boolean
) = when {
    notificationsNeeded && !notificationsAsked -> OnboardingPermission.Notifications
    !locationGranted && !locationAsked -> OnboardingPermission.Location
    !cameraGranted && !cameraAsked -> OnboardingPermission.Camera
    !microphoneGranted && !microphoneAsked -> OnboardingPermission.Microphone
    else -> OnboardingPermission.Done
}

// One owner serializes every post-onboarding prompt and explicit permission retries.
internal class AppPermissions(
    private val activity: ComponentActivity,
    private val notificationsEnabled: () -> Boolean,
    private val locationEnabled: () -> Boolean,
    private val onChanged: () -> Unit
) {
    private val preferences by lazy {
        activity.getSharedPreferences(
            "olive_permissions",
            Context.MODE_PRIVATE
        )
    }
    private var notificationPrompt = false
    private var locationPrompt = false
    private var cameraPrompt = false
    private var microphonePrompt = false
    private var locationResult: CompletableDeferred<Boolean>? = null
    val awaitingResult get() = notificationPrompt || locationPrompt || cameraPrompt || microphonePrompt
    private val notifications =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPrompt = false
            onChanged()
            afterOnboarding()
        }
    private val location =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationPrompt = false
            if (hasLocation() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                launchBackgroundLocation()
            } else {
                val pending = locationResult
                locationResult = null
                pending?.complete(hasLocation())
                onChanged()
                afterOnboarding()
            }
        }
    private val backgroundLocation =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            locationPrompt = false
            val pending = locationResult
            locationResult = null
            pending?.complete(hasLocation())
            onChanged()
            afterOnboarding()
        }
    private val camera =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraPrompt = false
            onChanged()
            afterOnboarding()
        }
    private val microphone =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            microphonePrompt = false
            onChanged()
            afterOnboarding()
        }

    fun hasLocation() = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPreciseLocation() =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else true

    fun afterOnboarding() {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                if (awaitingResult) return@withResumed
                val needsNotifications = notificationsEnabled() && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                when (nextOnboardingPermission(
                    needsNotifications,
                    preferences.getBoolean("notifications_asked", false),
                    (hasLocation() && hasBackgroundLocation()) || !locationEnabled(),
                    preferences.getBoolean("location_asked", false),
                    hasPermission(Manifest.permission.CAMERA),
                    preferences.getBoolean("camera_asked", false),
                    hasPermission(Manifest.permission.RECORD_AUDIO),
                    preferences.getBoolean("microphone_asked", false)
                )) {
                    OnboardingPermission.Notifications -> if (Build.VERSION.SDK_INT >= 33) {
                        preferences.edit { putBoolean("notifications_asked", true) }
                        notificationPrompt = true
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    OnboardingPermission.Location -> launchLocation()
                    OnboardingPermission.Camera -> launchCamera()
                    OnboardingPermission.Microphone -> launchMicrophone()
                    OnboardingPermission.Done -> Unit
                }
            }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun launchCamera() {
        preferences.edit { putBoolean("camera_asked", true) }
        cameraPrompt = true
        try {
            camera.launch(Manifest.permission.CAMERA)
        } catch (_: IllegalStateException) {
            cameraPrompt = false
        }
    }

    private fun launchMicrophone() {
        preferences.edit { putBoolean("microphone_asked", true) }
        microphonePrompt = true
        try {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        } catch (_: IllegalStateException) {
            microphonePrompt = false
        }
    }

    suspend fun requestLocation(): Boolean {
        if (hasPreciseLocation() && hasBackgroundLocation()) return true
        locationResult?.let { return it.await() }
        val pending = CompletableDeferred<Boolean>().also { locationResult = it }
        try {
            activity.lifecycle.withResumed { launchLocation() }
            return pending.await()
        } finally {
            if (!locationPrompt && locationResult === pending) locationResult = null
        }
    }

    private fun launchLocation() {
        preferences.edit { putBoolean("location_asked", true) }
        locationPrompt = true
        if (locationResult == null) locationResult = CompletableDeferred()
        try {
            if (!hasPreciseLocation()) {
                location.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                launchBackgroundLocation()
            } else {
                locationPrompt = false
                locationResult?.complete(true)
                locationResult = null
            }
        } catch (_: IllegalStateException) {
            locationPrompt = false
            locationResult?.complete(false)
            locationResult = null
        }
    }

    private fun launchBackgroundLocation() {
        locationPrompt = true
        if (locationResult == null) locationResult = CompletableDeferred()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                locationPrompt = false
                locationResult?.complete(true)
                locationResult = null
            }
        } catch (_: IllegalStateException) {
            locationPrompt = false
            locationResult?.complete(false)
            locationResult = null
        }
    }
}
