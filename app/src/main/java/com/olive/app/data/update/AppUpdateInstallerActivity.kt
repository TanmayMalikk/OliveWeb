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

internal fun installUpdatesPermissionIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

/** Prepares the signed APK off the UI thread, then hands it to Android for approval. */
class AppUpdateInstallerActivity : ComponentActivity() {
    private enum class Stage { Preparing, Permission, Installing, Failed }

    private var stage by mutableStateOf(Stage.Preparing)
    private lateinit var release: AppReleaseManifest
    private var preparedFile: File? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) continueInstall()
    }
    private val packageInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = intent.getStringExtra(EXTRA_RELEASE_JSON)?.let { raw ->
            runCatching { AppReleaseManifest.fromJson(raw) }.getOrNull()
        }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE } ?: run { finish(); return }
        setContent {
            OliveTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.app_update_version, release.versionName),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(when (stage) {
                            Stage.Preparing -> R.string.app_update_preparing
                            Stage.Permission -> R.string.app_update_permission
                            Stage.Installing -> R.string.app_update_installing
                            Stage.Failed -> R.string.app_update_failed
                        }))
                        when (stage) {
                            Stage.Preparing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            Stage.Permission -> Button(onClick = ::openPermission) {
                                Text(stringResource(R.string.app_permissions_install))
                            }
                            Stage.Failed -> Button(onClick = ::prepare) { Text(stringResource(R.string.retry)) }
                            Stage.Installing -> Unit
                        }
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
        // Android reconnects any system activity result following a configuration change.
        if (savedInstanceState?.getBoolean(INSTALLING) == true) stage = Stage.Installing else prepare()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(INSTALLING, stage == Stage.Installing)
        super.onSaveInstanceState(outState)
    }

    private fun prepare() {
        stage = Stage.Preparing
        lifecycleScope.launch {
            try {
                preparedFile = withContext(Dispatchers.IO) { AppUpdateClient(applicationContext).prepare(release) }
                continueInstall()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stage = Stage.Failed
            }
        }
    }

    private fun openPermission() {
        try {
            permission.launch(installUpdatesPermissionIntent(this))
        } catch (_: android.content.ActivityNotFoundException) {
            stage = Stage.Failed
        }
    }

    private fun continueInstall() {
        val file = preparedFile ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            stage = Stage.Permission
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.documents", file)
        stage = Stage.Installing
        try {
            packageInstaller.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: android.content.ActivityNotFoundException) {
            stage = Stage.Failed
        }
    }

    companion object {
        const val EXTRA_RELEASE_JSON = "release-json"
        private const val INSTALLING = "installing"
    }
}
