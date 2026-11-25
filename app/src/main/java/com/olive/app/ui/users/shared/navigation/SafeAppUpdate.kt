package com.olive.app.ui.users.shared.navigation

import kotlin.time.Duration.Companion.milliseconds

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.olive.app.R
import com.olive.app.data.update.AppUpdateClient
import com.olive.app.data.update.AppUpdateInstallerActivity
import com.olive.app.data.update.AppUpdateStore
import com.olive.app.ui.shared.AccessibleMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LocalSafeUpdateReminder = compositionLocalOf<SafeAppUpdateUi?> { null }

internal data class SafeAppUpdateUi(
    val required: Boolean,
    val launchFailed: Boolean,
    val onDownload: () -> Unit,
)

/**
 * Observes releases while the private workspace is active and exposes the installer action.
 *
 * @param enabled Whether the private workspace can show the update label.
 * @param launch Launches the installer through the safe system activity handler.
 * @return The available update action, or null when there is no newer release.
 */
@Composable
internal fun rememberSafeAppUpdate(
    enabled: Boolean,
    launch: (Intent) -> Boolean,
): SafeAppUpdateUi? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { AppUpdateStore(context) }
    var offer by remember(store, enabled) { mutableStateOf(if (enabled) store.offer() else null) }
    DisposableEffect(store, enabled) {
        val subscription = if (enabled) store.observe { offer = it } else null
        onDispose { subscription?.close() }
    }
    LaunchedEffect(enabled, store, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = AppUpdateClient(context)
            while (true) {
                withContext(Dispatchers.IO) { runCatching { store.refresh(client) } }
                delay(60_000.milliseconds)
            }
        }
    }
    val current = offer ?: return null
    var launchFailed by remember(current.release.versionCode) { mutableStateOf(false) }
    return SafeAppUpdateUi(current.required, launchFailed) {
        launchFailed = !launch(
            Intent(context, AppUpdateInstallerActivity::class.java)
                .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, current.release.toJson())
        )
    }
}

/**
 * Shows the available update as an inline action below the dashboard header.
 *
 * @param modifier Positioning and padding supplied by the dashboard.
 */
@Composable
internal fun SafeAppUpdateReminder(modifier: Modifier = Modifier) {
    val update = LocalSafeUpdateReminder.current ?: return
    Column(modifier) {
        TextButton(onClick = update.onDownload, modifier = Modifier.testTag("safe_update_reminder")) {
            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (update.required) R.string.app_update_required else R.string.ui_a_new_update_is_available))
        }
        if (update.launchFailed) AccessibleMessage(stringResource(R.string.app_update_failed))
    }
}
