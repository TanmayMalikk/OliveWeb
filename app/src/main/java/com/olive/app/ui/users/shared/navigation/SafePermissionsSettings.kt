package com.olive.app.ui.users.shared.navigation

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.olive.app.R
import com.olive.app.data.update.installUpdatesPermissionIntent
import com.olive.app.ui.covers.fitness.FitnessHealthConnect
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.users.user.account.SettingsSection
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class PermissionFeature(val title: Int, val reason: Int, val runtime: List<String> = emptyList()) {
    Camera(R.string.app_permissions_camera, R.string.app_permissions_camera_why, listOf(Manifest.permission.CAMERA)),
    Microphone(R.string.app_permissions_microphone, R.string.app_permissions_microphone_why, listOf(Manifest.permission.RECORD_AUDIO)),
    Location(R.string.app_permissions_location, R.string.app_permissions_location_why,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    BackgroundLocation(R.string.app_permissions_background_location, R.string.app_permissions_background_location_why),
    Notifications(R.string.app_permissions_notifications, R.string.app_permissions_notifications_why,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
    IncomingCalls(R.string.app_permissions_incoming_calls, R.string.app_permissions_incoming_calls_why),
    Biometrics(R.string.app_permissions_biometrics, R.string.app_permissions_biometrics_why),
    Activity(R.string.app_permissions_activity, R.string.app_permissions_activity_why, if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()),
    Health(R.string.app_permissions_health, R.string.app_permissions_health_why),
}

private data class PermissionAction(val label: Int, val description: Int, val run: () -> Unit)

private enum class PermissionStatus(val label: Int) {
    Allowed(R.string.app_permissions_allowed),
    Denied(R.string.app_permissions_disallowed),
    Limited(R.string.app_permissions_limited),
    Unavailable(R.string.app_permissions_unavailable),
    Checking(R.string.app_permissions_checking),
    Automatic(R.string.app_permissions_automatic_status),
}

private fun permissionStatus(allowed: Boolean) = if (allowed) PermissionStatus.Allowed else PermissionStatus.Denied

private data class HealthAccess(val requested: Set<String>, val missing: Set<String>) {
    val status get() = when {
        requested.isEmpty() -> PermissionStatus.Unavailable
        missing.isEmpty() -> PermissionStatus.Allowed
        missing.size == requested.size -> PermissionStatus.Denied
        else -> PermissionStatus.Limited
    }
}

private val automaticPermissions = listOf(
    R.string.app_permissions_internet to R.string.app_permissions_internet_why,
    R.string.app_permissions_services to R.string.app_permissions_services_why,
    R.string.app_permissions_capture to R.string.app_permissions_capture_why,
    R.string.app_permissions_system to R.string.app_permissions_system_why,
)

@Composable
internal fun SafePermissionsSettings() {
    val context = LocalContext.current
    val openSystem = LocalSafeSystemLauncher.current
    val actions = LocalSafePermissionActions.current
    val requestBackground = LocalBackgroundLocationPermissionRequest.current
    val scope = rememberCoroutineScope()
    val packageUri = "package:${context.packageName}".toUri()
    var resumed by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    LifecycleResumeEffect(context) {
        resumed++
        onPauseOrDispose {}
    }
    val canInstall = remember(resumed, actions.revision) { context.packageManager.canRequestPackageInstalls() }
    val health by produceState<HealthAccess?>(null, context, resumed, actions.revision) {
        value = try {
            if (!FitnessHealthConnect.available(context)) HealthAccess(emptySet(), emptySet()) else {
                val client = HealthConnectClient.getOrCreate(context)
                val requested = FitnessHealthConnect.permissionsFor(client)
                HealthAccess(requested, requested - client.permissionController.getGrantedPermissions())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HealthAccess(emptySet(), emptySet())
        }
    }
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun status(feature: PermissionFeature): PermissionStatus = when (feature) {
        PermissionFeature.Notifications -> permissionStatus(NotificationManagerCompat.from(context).areNotificationsEnabled())
        PermissionFeature.Location -> when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionStatus.Allowed
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionStatus.Limited
            else -> PermissionStatus.Denied
        }
        PermissionFeature.BackgroundLocation -> permissionStatus(
            if (Build.VERSION.SDK_INT >= 29) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        PermissionFeature.Activity -> if (Build.VERSION.SDK_INT < 29) PermissionStatus.Automatic
            else permissionStatus(granted(Manifest.permission.ACTIVITY_RECOGNITION))
        PermissionFeature.IncomingCalls -> if (Build.VERSION.SDK_INT < 34) PermissionStatus.Automatic
            else permissionStatus(context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
        PermissionFeature.Biometrics -> when (BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> PermissionStatus.Allowed
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> PermissionStatus.Denied
            else -> PermissionStatus.Unavailable
        }
        PermissionFeature.Health -> health?.status ?: PermissionStatus.Checking
        else -> permissionStatus(feature.runtime.all(::granted))
    }
    fun open(intent: Intent) {
        feedback = if (openSystem(intent)) null else R.string.app_permissions_settings_unavailable
    }
    fun permissionAction(feature: PermissionFeature, status: PermissionStatus): PermissionAction? {
        if (feature == PermissionFeature.Notifications && status == PermissionStatus.Denied &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        ) return PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        if (feature.runtime.isNotEmpty()) {
            if (feature == PermissionFeature.Notifications && Build.VERSION.SDK_INT < 33 ||
                feature == PermissionFeature.Activity && Build.VERSION.SDK_INT < 29) return null
            return if (status == PermissionStatus.Allowed) null
            else PermissionAction(R.string.app_permissions_allow_short, R.string.app_permissions_request) {
                feedback = if (actions.request(feature.runtime.toTypedArray())) null
                    else R.string.app_permissions_settings_unavailable
            }
        }
        return when (feature) {
            PermissionFeature.BackgroundLocation -> requestBackground?.let { request ->
                PermissionAction(R.string.app_permissions_allow_short, R.string.app_permissions_request) { scope.launch { request() } }
            }
            PermissionFeature.IncomingCalls -> if (Build.VERSION.SDK_INT >= 34 && status == PermissionStatus.Denied) {
                PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
                }
            } else null
            PermissionFeature.Biometrics -> PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
                open(Intent(if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_BIOMETRIC_ENROLL else Settings.ACTION_SECURITY_SETTINGS))
            }
            PermissionFeature.Health -> health?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
                PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
                    feedback = if (actions.requestHealth(missing)) null else R.string.app_permissions_settings_unavailable
                }
            }
            else -> null
        }
    }
    SettingsSection(stringResource(R.string.app_permissions_install)) {
        Text(stringResource(R.string.app_permissions_install_why))
        Text(stringResource(permissionStatus(canInstall).label),
            style = MaterialTheme.typography.labelLarge)
        Button(
            onClick = { open(installUpdatesPermissionIntent(context)) },
            modifier = Modifier.fillMaxWidth().testTag("settings_install_permission"),
        ) { Text(stringResource(R.string.app_permissions_manage_install)) }
    }
    feedback?.let { AccessibleMessage(stringResource(it)) }
    SettingsSection(stringResource(R.string.app_permissions_features)) {
        PermissionFeature.entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            val status = remember(entry, resumed, actions.revision, health) { status(entry) }
            PermissionExplanation(entry.title, entry.reason, status, permissionAction(entry, status), id = entry.name)
        }
        OutlinedButton(
            onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.app_permissions_open_android)) }
    }
    SettingsSection(stringResource(R.string.app_permissions_automatic)) {
        automaticPermissions.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            PermissionExplanation(entry.first, entry.second, PermissionStatus.Automatic)
        }
    }
}

@Composable
private fun PermissionExplanation(
    title: Int, reason: Int, status: PermissionStatus, action: PermissionAction? = null, id: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(reason), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(status.label), style = MaterialTheme.typography.labelMedium,
                color = if (status == PermissionStatus.Denied) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (id == null) Modifier else Modifier.testTag("settings_permission_status_$id"))
            action?.let {
                val description = stringResource(it.description, stringResource(title))
                TextButton(onClick = it.run,
                    modifier = Modifier.testTag("settings_permission_$id").semantics { contentDescription = description }) {
                    Text(stringResource(it.label))
                }
            }
        }
    }
}

internal val LocalSafeSystemLauncher = staticCompositionLocalOf<(Intent) -> Boolean> { { false } }
internal val LocalSafePermissionActions = androidx.compose.runtime.compositionLocalOf {
    SafePermissionActions(0, { false }, { false })
}

internal data class SafePermissionActions(
    val revision: Int,
    val request: (Array<String>) -> Boolean,
    val requestHealth: (Set<String>) -> Boolean,
)

internal data class SafeSystemLaunchers(
    val open: (Intent) -> Boolean,
    val permissions: SafePermissionActions,
)

@Composable
internal fun rememberSafeSystemLaunchers(session: SessionViewModel): SafeSystemLaunchers {
    val view = LocalView.current
    var revision by remember { mutableIntStateOf(0) }
    fun returned() {
        revision++
        session.safeSystemActivityResult(view)
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { returned() }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { returned() }
    return SafeSystemLaunchers(
        open = { launchWithSafeSession(session) { system.launch(it) } },
        permissions = SafePermissionActions(
            revision,
            request = { launchWithSafeSession(session) { runtime.launch(it) } },
            requestHealth = { launchWithSafeSession(session) { health.launch(it) } },
        ),
    )
}

private inline fun launchWithSafeSession(session: SessionViewModel, launch: () -> Unit): Boolean {
    val marker = session.beginSafeSystemActivity() ?: return false
    try {
        launch()
        return true
    } catch (_: ActivityNotFoundException) {
        session.finishSafeSystemActivity(marker)
    } catch (_: SecurityException) {
        session.finishSafeSystemActivity(marker)
    }
    return false
}
