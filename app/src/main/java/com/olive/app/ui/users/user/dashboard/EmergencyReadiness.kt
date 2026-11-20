package com.olive.app.ui.users.user.dashboard

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.olive.app.R
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private enum class ReadinessIssue(val explanation: Int, val action: Int) {
    Location(R.string.emergency_readiness_location, R.string.emergency_readiness_allow_location),
    DeviceLocation(R.string.emergency_readiness_device_location, R.string.emergency_readiness_enable_location),
    Background(R.string.emergency_readiness_background, R.string.emergency_readiness_allow_background),
    AppNotifications(R.string.emergency_readiness_notifications, R.string.emergency_readiness_notification_settings),
    TrackingNotifications(R.string.emergency_readiness_notifications, R.string.emergency_readiness_notification_settings),
}

private fun Context.emergencyReadinessIssues(localMode: Boolean): List<ReadinessIssue> = buildList {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this@emergencyReadinessIssues, permission) == PackageManager.PERMISSION_GRANTED

    val hasLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    if (!hasLocation) add(ReadinessIssue.Location)
    val locationManager = getSystemService(LocationManager::class.java)
    if (locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager)) {
        add(ReadinessIssue.DeviceLocation)
    }

    if (!localMode) {
        if (hasLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            add(ReadinessIssue.Background)
        }
        val channel = getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(OliveNotificationManager.CHANNEL_LOCATION)
        when {
            !NotificationManagerCompat.from(this@emergencyReadinessIssues).areNotificationsEnabled() ->
                add(ReadinessIssue.AppNotifications)
            channel?.importance == NotificationManager.IMPORTANCE_NONE -> add(ReadinessIssue.TrackingNotifications)
        }
    }
}

@Composable
internal fun EmergencyReadinessCard(accountId: String, localMode: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requestLocation = LocalLocationPermissionRequest.current
    val requestBackground = LocalBackgroundLocationPermissionRequest.current
    val openSystem = LocalSafeSystemLauncher.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("olive_emergency_readiness", Context.MODE_PRIVATE)
    }
    val snoozeKey = "snoozed_until:$accountId"
    var remindAt by remember(snoozeKey) { mutableLongStateOf(preferences.getLong(snoozeKey, 0L)) }
    var issues by remember(accountId, localMode) { mutableStateOf(emptyList<ReadinessIssue>()) }
    var opening by remember(accountId) { mutableStateOf(false) }
    var settingsError by remember(accountId) { mutableStateOf(false) }
    fun check() {
        issues = if (System.currentTimeMillis() < remindAt) emptyList()
        else context.emergencyReadinessIssues(localMode)
    }
    LaunchedEffect(context, lifecycle, accountId, localMode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                check()
                delay(5.minutes)
            }
        }
    }
    if (issues.isEmpty()) return

    fun openSettings(intent: Intent) {
        settingsError = !openSystem(intent)
    }
    fun requestAccess(request: suspend () -> Boolean) {
        opening = true
        scope.launch {
            try {
                request()
                check()
            } finally {
                opening = false
            }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.emergency_readiness_title), style = MaterialTheme.typography.titleMedium)
            issues.forEach { issue ->
                Text(stringResource(issue.explanation), style = MaterialTheme.typography.bodyMedium)
                if (issue == ReadinessIssue.Background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Text(
                        stringResource(
                            R.string.emergency_readiness_location_settings_path,
                            context.packageManager.backgroundPermissionOptionLabel.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val colors = MaterialTheme.colorScheme
                OutlinedButton(
                    enabled = !opening,
                    border = if (!opening) BorderStroke(2.dp, colors.outlineVariant)
                        else ButtonDefaults.outlinedButtonBorder(enabled = !opening),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ),
                    onClick = {
                        settingsError = false
                        when (issue) {
                            ReadinessIssue.Location -> requestAccess(requestLocation)
                            ReadinessIssue.Background -> requestBackground?.let { requestAccess(it) } ?: check()
                            ReadinessIssue.DeviceLocation -> openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            ReadinessIssue.AppNotifications -> openSettings(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                            ReadinessIssue.TrackingNotifications -> openSettings(
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, OliveNotificationManager.CHANNEL_LOCATION),
                            )
                        }
                    },
                ) { Text(stringResource(issue.action)) }
            }
            if (settingsError) Text(
                stringResource(R.string.app_permissions_settings_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(enabled = !opening, onClick = {
                remindAt = System.currentTimeMillis() + 1.days.inWholeMilliseconds
                preferences.edit { putLong(snoozeKey, remindAt) }
                issues = emptyList()
            }) { Text(stringResource(R.string.emergency_readiness_remind_later)) }
        }
    }
}
