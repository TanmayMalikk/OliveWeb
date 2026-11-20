package com.olive.app.data.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.ui.shared.OliveTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Computes install updates permission intent.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @return The resulting install updates permission intent.
 */
internal fun installUpdatesPermissionIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

internal enum class AppUpdateStage { Preparing, Permission, Installing, Failed }

/** Prepares the signed APK off the UI thread, then hands it to Android for approval. */
class AppUpdateInstallerActivity : ComponentActivity() {
    private var stage by mutableStateOf(AppUpdateStage.Preparing)
    private lateinit var release: AppReleaseManifest
    private var preparedFile: File? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) continueInstall()
    }
    private val packageInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish()
    }

    /**
     * Initializes the component and sets up its state or UI when created by Android.
     *
     * @param savedInstanceState Previously saved instance state bundle, or null on a fresh creation.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = intent.getStringExtra(EXTRA_RELEASE_JSON)?.let { raw ->
            runCatching { AppReleaseManifest.fromJson(raw) }.getOrNull()
        }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE } ?: run { finish(); return }
        setContent {
            OliveTheme {
                AppUpdateInstallerContent(release.versionName, stage, ::openPermission, ::prepare, ::finish)
            }
        }
        // Android reconnects any system activity result following a configuration change.
        if (savedInstanceState?.getBoolean(INSTALLING) == true) stage = AppUpdateStage.Installing else prepare()
    }

    /**
     * Handles the save instance state event.
     *
     * @param outState Out state supplied to [onSaveInstanceState].
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(INSTALLING, stage == AppUpdateStage.Installing)
        super.onSaveInstanceState(outState)
    }

    /**
     * Prepares the app update installer activity.
     */
    private fun prepare() {
        stage = AppUpdateStage.Preparing
        lifecycleScope.launch {
            try {
                preparedFile = withContext(Dispatchers.IO) { AppUpdateClient(applicationContext).prepare(release) }
                continueInstall()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stage = AppUpdateStage.Failed
            }
        }
    }

    /**
     * Opens permission.
     */
    private fun openPermission() {
        try {
            permission.launch(installUpdatesPermissionIntent(this))
        } catch (_: android.content.ActivityNotFoundException) {
            stage = AppUpdateStage.Failed
        }
    }

    /**
     * Computes continue install.
     */
    private fun continueInstall() {
        val file = preparedFile ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            stage = AppUpdateStage.Permission
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.documents", file)
        stage = AppUpdateStage.Installing
        try {
            packageInstaller.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: android.content.ActivityNotFoundException) {
            stage = AppUpdateStage.Failed
        }
    }

    companion object {
        const val EXTRA_RELEASE_JSON = "release-json"
        private const val INSTALLING = "installing"
    }
}

/**
 * Composes the app update installer content UI element.
 *
 * @param version Version supplied to [AppUpdateInstallerContent].
 * @param stage Stage supplied to [AppUpdateInstallerContent].
 * @param onPermission Callback invoked when permission changes or occurs.
 * @param onRetry Callback invoked when retry changes or occurs.
 * @param onCancel Callback invoked when cancel changes or occurs.
 */
@Composable
internal fun AppUpdateInstallerContent(
    version: String,
    stage: AppUpdateStage,
    onPermission: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_update_version, version), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(when (stage) {
                AppUpdateStage.Preparing -> R.string.app_update_preparing
                AppUpdateStage.Permission -> R.string.app_update_permission
                AppUpdateStage.Installing -> R.string.app_update_installing
                AppUpdateStage.Failed -> R.string.app_update_failed
            }))
            when (stage) {
                AppUpdateStage.Preparing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                AppUpdateStage.Permission -> Button(onClick = onPermission) {
                    Text(stringResource(R.string.app_permissions_install))
                }
                AppUpdateStage.Failed -> Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                AppUpdateStage.Installing -> Unit
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
